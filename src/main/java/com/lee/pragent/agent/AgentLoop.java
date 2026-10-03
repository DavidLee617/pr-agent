package com.lee.pragent.agent;

import com.lee.pragent.agent.TaskRecords.Status;
import com.lee.pragent.agent.TaskRecords.Step;
import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.agent.TaskRecords.ToolInvocation;
import com.lee.pragent.config.AgentProperties;
import com.lee.pragent.mcp.McpToolGateway;
import com.lee.pragent.mcp.McpToolGateway.ToolOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 手写的工具调用循环：外层 DeepSeek 决定 → 按顺序执行工具 → 结果放回对话 → 下一轮，最多 max-steps 轮。
 * <p>
 * 为什么不用框架自动执行工具：实验需要每一轮的 token、工具参数和原始返回，并在循环中加步数上限。
 * 这里把 {@code internalToolExecutionEnabled} 设为 false，ChatModel 只返回工具调用请求，由本类执行。
 * <p>
 * 对话历史跨任务保留（多轮对话），直到 {@link #clear()}。单线程使用（REPL）。
 * C# 对照：≈ 自己写的 while 循环调用 IChatClient.GetResponseAsync，而不是用 UseFunctionInvocation() 中间件。
 */
@Component
public class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

    private final ChatModel chatModel;
    private final McpToolGateway gateway;
    private final AgentListener listener;
    private final int maxSteps;
    private final SystemMessage systemMessage;
    private final ToolCallingChatOptions options;

    /** 对话历史（不含系统提示）。 */
    private final List<Message> history = new ArrayList<>();

    @Autowired // 有两个构造函数（另一个给测试用），告诉 Spring 用这个（≈ C# DI 选择 public 构造函数）
    public AgentLoop(ChatModel chatModel, McpToolGateway gateway, AgentListener listener, AgentProperties props,
                     @Value("classpath:prompts/agent-system.md") Resource systemPrompt) {
        this(chatModel, gateway, listener, props.maxSteps(), read(systemPrompt));
    }

    AgentLoop(ChatModel chatModel, McpToolGateway gateway, AgentListener listener, int maxSteps,
              String systemPrompt) {
        this.chatModel = chatModel;
        this.gateway = gateway;
        this.listener = listener;
        this.maxSteps = maxSteps;
        this.systemMessage = new SystemMessage(systemPrompt);
        // 运行时选项只放工具；model / temperature 用 application.yml 里的默认值（OpenAiChatModel 会合并）
        this.options = ToolCallingChatOptions.builder()
                .toolCallbacks(gateway.tools())
                .internalToolExecutionEnabled(false)
                .build();
    }

    /** 执行一条用户指令，直到 Agent 给出纯文本回答、达到步数上限或外层调用失败。 */
    public TaskResult runTask(String userInput) {
        long taskStart = System.currentTimeMillis();
        int historyMark = history.size();
        history.add(new UserMessage(userInput));
        List<Step> steps = new ArrayList<>();

        for (int index = 1; index <= maxSteps; index++) {
            listener.stepStarted(index);
            long t0 = System.currentTimeMillis();
            ChatResponse response;
            try {
                response = chatModel.call(new Prompt(messages(), options));
            } catch (RuntimeException e) {
                // 回滚到本条指令之前：否则历史里可能留下只有工具调用、没有工具结果的消息，下一次请求会被 API 拒绝
                history.subList(historyMark, history.size()).clear();
                log.warn("outer_llm_failed step={}", index, e);
                return finish(userInput, Status.FAILED, null, describe(e), steps, taskStart);
            }
            long latency = System.currentTimeMillis() - t0;
            if (response == null || response.getResult() == null) {
                history.subList(historyMark, history.size()).clear();
                return finish(userInput, Status.FAILED, null, "外层 LLM 返回为空", steps, taskStart);
            }

            AssistantMessage reply = response.getResult().getOutput();
            Usage usage = response.getMetadata() == null ? null : response.getMetadata().getUsage();
            int in = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
            int out = usage == null || usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
            history.add(reply);
            log.info("agent_step step={} inputTokens={} outputTokens={} latencyMs={} toolCalls={}",
                    index, in, out, latency, reply.getToolCalls().size());

            Step llmOnly = new Step(index, in, out, latency, reply.getText(), List.of());
            listener.llmResponded(llmOnly, reply.getToolCalls().size());

            if (!reply.hasToolCalls()) {
                steps.add(llmOnly);
                return finish(userInput, Status.COMPLETED, reply.getText(), null, steps, taskStart);
            }

            // 同一轮多个工具调用按顺序逐个执行（pr-reviewer 的 MCP server 同步处理请求）
            List<ToolInvocation> invocations = new ArrayList<>();
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : reply.getToolCalls()) {
                ToolInvocation inv = invoke(call);
                invocations.add(inv);
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), inv.result()));
            }
            history.add(ToolResponseMessage.builder().responses(responses).build());
            steps.add(new Step(index, in, out, latency, reply.getText(), invocations));
        }
        return finish(userInput, Status.STEP_LIMIT, null, null, steps, taskStart);
    }

    /** 清空对话历史（/clear）。 */
    public void clear() {
        history.clear();
    }

    private ToolInvocation invoke(AssistantMessage.ToolCall call) {
        String args = call.arguments() == null || call.arguments().isBlank() ? "{}" : call.arguments();
        listener.toolStarted(call.name(), args);
        long t0 = System.currentTimeMillis();
        ToolOutcome outcome = gateway.call(call.name(), args);
        long latency = System.currentTimeMillis() - t0;
        ToolInvocation inv = new ToolInvocation(call.id(), call.name(), args, outcome.text(), outcome.error(), latency);
        log.info("tool_call name={} error={} latencyMs={}", call.name(), outcome.error(), latency);
        listener.toolFinished(inv);
        return inv;
    }

    private List<Message> messages() {
        List<Message> all = new ArrayList<>(history.size() + 1);
        all.add(systemMessage);
        all.addAll(history);
        return all;
    }

    private TaskResult finish(String userInput, Status status, String answer, String failure, List<Step> steps,
                              long taskStart) {
        TaskResult result = new TaskResult(userInput, status, answer, failure, steps,
                System.currentTimeMillis() - taskStart);
        listener.taskFinished(result);
        return result;
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage()));
        for (Throwable c = e.getCause(); c != null && c != e; c = c.getCause()) {
            sb.append(" ← ").append(c.getMessage());
        }
        return sb.toString();
    }

    private static String read(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取系统提示失败: " + resource, e);
        }
    }
}
