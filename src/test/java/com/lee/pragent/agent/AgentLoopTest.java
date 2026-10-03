package com.lee.pragent.agent;

import com.lee.pragent.agent.TaskRecords.Status;
import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.agent.TaskRecords.ToolInvocation;
import com.lee.pragent.mcp.McpToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 假 ChatModel 按剧本返回；McpToolGateway 用真实类，里面放假的工具。不调用真实 DeepSeek / MCP。
 * C# 对照：≈ xUnit 测试里手写的 fake IChatClient。
 */
class AgentLoopTest {

    private final List<String> toolCallsReceived = new ArrayList<>();

    @Test
    void completesWhenAgentRepliesWithText() {
        ScriptedChatModel model = new ScriptedChatModel(
                () -> toolCall("review_file", "{\"prUrl\":\"u\",\"filePath\":\"A.java\"}"),
                () -> text("审查完成"));
        AgentLoop loop = loop(model, 10);

        TaskResult r = loop.runTask("review 一下");

        assertThat(r.status()).isEqualTo(Status.COMPLETED);
        assertThat(r.answer()).isEqualTo("审查完成");
        assertThat(r.steps()).hasSize(2);
        assertThat(r.outerInputTokens()).isEqualTo(200);
        assertThat(r.outerOutputTokens()).isEqualTo(20);
        assertThat(toolCallsReceived).containsExactly("review_file:{\"prUrl\":\"u\",\"filePath\":\"A.java\"}");

        // 第 2 次调用 LLM 时，工具结果已作为 ToolResponseMessage 放回对话
        ToolResponseMessage toolMsg = (ToolResponseMessage) last(model.prompts.get(1));
        assertThat(toolMsg.getResponses()).singleElement()
                .satisfies(resp -> assertThat(resp.responseData()).isEqualTo("result of review_file"));

        // 关闭框架自动执行工具，且只传入开放的 3 个工具
        ToolCallingChatOptions options = (ToolCallingChatOptions) model.prompts.get(0).getOptions();
        assertThat(options.getInternalToolExecutionEnabled()).isFalse();
        assertThat(options.getToolCallbacks()).extracting(t -> t.getToolDefinition().name())
                .containsExactly("list_pr_files", "review_file", "aggregate_findings");
    }

    @Test
    void stopsAtMaxSteps() {
        ScriptedChatModel model = new ScriptedChatModel(i -> toolCall("list_pr_files", "{\"prUrl\":\"u\"}"));
        AgentLoop loop = loop(model, 3);

        TaskResult r = loop.runTask("review 一下");

        assertThat(r.status()).isEqualTo(Status.STEP_LIMIT);
        assertThat(r.steps()).hasSize(3);
        assertThat(model.prompts).hasSize(3);
        assertThat(r.answer()).isNull();
    }

    @Test
    void toolErrorIsReturnedToAgentAsToolResult() {
        ScriptedChatModel model = new ScriptedChatModel(
                () -> toolCall("review_file", "{\"prUrl\":\"u\",\"filePath\":\"Missing.java\"}"),
                () -> text("文件不存在，已跳过"));
        AgentLoop loop = loop(model, 10);

        TaskResult r = loop.runTask("review 一下");

        ToolInvocation inv = r.toolInvocations().get(0);
        assertThat(inv.error()).isTrue();
        assertThat(inv.result()).contains("文件不在 PR 中");
        ToolResponseMessage toolMsg = (ToolResponseMessage) last(model.prompts.get(1));
        assertThat(toolMsg.getResponses().get(0).responseData()).contains("文件不在 PR 中");
        assertThat(r.status()).isEqualTo(Status.COMPLETED);
    }

    @Test
    void unknownOrHiddenToolIsReportedToAgent() {
        ScriptedChatModel model = new ScriptedChatModel(
                () -> toolCall("review_pr", "{\"prUrl\":\"u\"}"),
                () -> text("好的"));
        AgentLoop loop = loop(model, 10);

        TaskResult r = loop.runTask("review 一下");

        ToolInvocation inv = r.toolInvocations().get(0);
        assertThat(inv.error()).isTrue();
        assertThat(inv.result()).contains("工具不存在或未开放：review_pr");
        assertThat(toolCallsReceived).isEmpty();
    }

    @Test
    void invalidJsonArgumentsAreReportedToAgent() {
        ScriptedChatModel model = new ScriptedChatModel(
                () -> toolCall("review_file", "{\"prUrl\": \"u\", filePath"),
                () -> text("好的"));
        AgentLoop loop = loop(model, 10);

        TaskResult r = loop.runTask("review 一下");

        ToolInvocation inv = r.toolInvocations().get(0);
        assertThat(inv.error()).isTrue();
        assertThat(inv.result()).contains("不是合法的 JSON");
        assertThat(toolCallsReceived).isEmpty();
    }

    @Test
    void outerFailureRollsBackHistoryOfThatTaskOnly() {
        ScriptedChatModel model = new ScriptedChatModel(
                () -> text("你好"),                                         // 任务 1
                () -> toolCall("list_pr_files", "{\"prUrl\":\"u\"}"),       // 任务 2 第 1 轮
                () -> { throw new RuntimeException("HTTP 500"); },          // 任务 2 第 2 轮失败
                () -> text("重发后完成"));                                    // 任务 3
        AgentLoop loop = loop(model, 10);

        loop.runTask("第一条");
        TaskResult failed = loop.runTask("第二条");
        TaskResult retried = loop.runTask("第三条");

        assertThat(failed.status()).isEqualTo(Status.FAILED);
        assertThat(failed.failure()).contains("HTTP 500");
        assertThat(failed.steps()).hasSize(1); // 失败前已完成的一轮仍然记录
        assertThat(retried.status()).isEqualTo(Status.COMPLETED);

        // 任务 3 的请求：系统提示 + 任务 1 的问答 + 第三条；任务 2 的消息全部撤回
        List<Message> messages = model.prompts.get(3).getInstructions();
        assertThat(messages).hasSize(4);
        assertThat(messages.get(1)).isInstanceOf(UserMessage.class);
        assertThat(messages.get(1).getText()).isEqualTo("第一条");
        assertThat(messages.get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(messages.get(3).getText()).isEqualTo("第三条");
    }

    @Test
    void clearDropsHistory() {
        ScriptedChatModel model = new ScriptedChatModel(() -> text("一"), () -> text("二"));
        AgentLoop loop = loop(model, 10);

        loop.runTask("第一条");
        loop.clear();
        loop.runTask("第二条");

        assertThat(model.prompts.get(1).getInstructions()).hasSize(2); // 系统提示 + 第二条
    }

    // ---- 测试辅助 ----

    private AgentLoop loop(ChatModel model, int maxSteps) {
        McpToolGateway gateway = new McpToolGateway(() -> new ToolCallback[]{
                tool("list_pr_files"), tool("review_file"), tool("aggregate_findings"), tool("review_pr")});
        return new AgentLoop(model, gateway, new AgentListener() {}, maxSteps, "system");
    }

    private ToolCallback tool(String name) {
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String input) {
                if (input.contains("Missing.java")) {
                    // 模拟 SyncMcpToolCallback：MCP 返回 isError 时抛 ToolExecutionException，原因在 cause
                    throw new RuntimeException(new IllegalStateException("Error calling tool: 文件不在 PR 中"));
                }
                toolCallsReceived.add(name + ":" + input);
                return "result of " + name;
            }
        };
    }

    private static ChatResponse toolCall(String name, String arguments) {
        AssistantMessage msg = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", name, arguments))).build();
        return response(msg);
    }

    private static ChatResponse text(String content) {
        return response(new AssistantMessage(content));
    }

    private static ChatResponse response(AssistantMessage msg) {
        return new ChatResponse(List.of(new Generation(msg)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 10)).build());
    }

    private static Message last(Prompt prompt) {
        List<Message> messages = prompt.getInstructions();
        return messages.get(messages.size() - 1);
    }

    /** 按剧本依次返回；记录收到的每个 Prompt。 */
    private static final class ScriptedChatModel implements ChatModel {
        final List<Prompt> prompts = new ArrayList<>();
        private final Deque<Supplier<ChatResponse>> script;
        private final Function<Integer, ChatResponse> fallback;

        @SafeVarargs
        ScriptedChatModel(Supplier<ChatResponse>... script) {
            this.script = new ArrayDeque<>(List.of(script));
            this.fallback = null;
        }

        ScriptedChatModel(Function<Integer, ChatResponse> always) {
            this.script = new ArrayDeque<>();
            this.fallback = always;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            // 复制消息列表：AgentLoop 之后会修改自己的历史
            prompts.add(new Prompt(new ArrayList<>(prompt.getInstructions()), prompt.getOptions()));
            if (fallback != null) {
                return fallback.apply(prompts.size());
            }
            return script.removeFirst().get();
        }
    }
}
