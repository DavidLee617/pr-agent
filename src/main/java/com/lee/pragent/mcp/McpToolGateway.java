package com.lee.pragent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * MCP 工具的唯一入口：Agent 只能看到、调用这里开放的工具。
 * <p>
 * MCP 客户端 starter 自动注册一个 {@link ToolCallbackProvider}（{@code SyncMcpToolCallbackProvider}），
 * 里面是 pr-reviewer 的全部 4 个 tool；这里过滤掉 {@code review_pr}（一键完整审查，开放的话 Agent 多半直接调它，
 * 体现不出自主编排）。工具名不带前缀（DefaultMcpToolNamePrefixGenerator 只在重名时加 alt_N_）。
 * <p>
 * C# 对照：≈ 一个包装 IMcpClient 的服务类，对外只暴露白名单之外的 McpClientTool。
 */
@Component
public class McpToolGateway {

    /** 不开放给 Agent 的工具。 */
    static final String HIDDEN_TOOL = "review_pr";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<ToolCallback> tools;

    public McpToolGateway(ToolCallbackProvider mcpToolCallbacks) {
        // provider 内部会缓存 listTools 结果；这里在启动时取一次，连不上 pr-reviewer 则启动失败
        this.tools = Arrays.stream(mcpToolCallbacks.getToolCallbacks())
                .filter(t -> !HIDDEN_TOOL.equals(t.getToolDefinition().name()))
                .toList();
    }

    /**
     * 一次工具调用的结果。
     *
     * @param text  成功时是工具返回的文本（pr-reviewer 返回的是 JSON）；失败时是错误原文，同样回传给 Agent
     * @param error 是否出错（工具不存在、参数非法、MCP 返回 isError、调用异常）
     */
    public record ToolOutcome(String text, boolean error) {
        static ToolOutcome failure(String message) {
            return new ToolOutcome(message, true);
        }
    }

    /** 开放给 Agent 的工具（调用外层 LLM 时作为工具定义传入）。 */
    public List<ToolCallback> tools() {
        return tools;
    }

    public List<String> toolNames() {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    /** 按名称查找开放的工具；被过滤的 review_pr 和不存在的工具都返回 empty。 */
    public Optional<ToolCallback> find(String name) {
        return tools.stream().filter(t -> t.getToolDefinition().name().equals(name)).findFirst();
    }

    /**
     * 调用工具。所有失败都转成 {@link ToolOutcome#error()} = true，不向外抛异常：
     * 错误原文要作为工具结果交回给 Agent，由它决定重试或跳过（DESIGN 第 9 节）。
     */
    public ToolOutcome call(String name, String argumentsJson) {
        Optional<ToolCallback> tool = find(name);
        if (tool.isEmpty()) {
            return ToolOutcome.failure("工具不存在或未开放：" + name + "。可用工具：" + String.join(", ", toolNames()));
        }
        // SyncMcpToolCallback 内部的 jsonToMap 遇到非法 JSON 会抛出含义不清的异常，这里先自己校验
        try {
            if (!JSON.readTree(argumentsJson).isObject()) {
                return ToolOutcome.failure("参数必须是 JSON 对象：" + argumentsJson);
            }
        } catch (Exception e) {
            return ToolOutcome.failure("参数不是合法的 JSON：" + argumentsJson);
        }
        try {
            return new ToolOutcome(unwrapContent(tool.get().call(argumentsJson)), false);
        } catch (RuntimeException e) {
            // MCP 返回 isError=true 或调用异常时，SyncMcpToolCallback 抛 ToolExecutionException，原因在 cause 里
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            return ToolOutcome.failure("工具调用失败：" + cause.getMessage());
        }
    }

    /**
     * SyncMcpToolCallback 返回的是 MCP content 列表的 JSON（如 [{"type":"text","text":"..."}]），
     * 取出其中的 text 拼起来；不是这种格式就原样返回。
     */
    static String unwrapContent(String raw) {
        try {
            JsonNode node = JSON.readTree(raw);
            if (node != null && node.isArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : node) {
                    JsonNode text = item.get("text");
                    if (text == null || !text.isTextual()) {
                        return raw;
                    }
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(text.asText());
                }
                return sb.toString();
            }
        } catch (Exception ignored) {
            // 不是 JSON，原样返回
        }
        return raw;
    }
}
