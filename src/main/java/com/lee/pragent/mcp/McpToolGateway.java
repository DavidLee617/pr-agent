package com.lee.pragent.mcp;

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

    private final List<ToolCallback> tools;

    public McpToolGateway(ToolCallbackProvider mcpToolCallbacks) {
        // provider 内部会缓存 listTools 结果；这里在启动时取一次，连不上 pr-reviewer 则启动失败
        this.tools = Arrays.stream(mcpToolCallbacks.getToolCallbacks())
                .filter(t -> !HIDDEN_TOOL.equals(t.getToolDefinition().name()))
                .toList();
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
}
