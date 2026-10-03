package com.lee.pragent.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.assertj.core.api.Assertions.assertThat;

class McpToolGatewayTest {

    @Test
    void filtersOutReviewPrAndKeepsTheOtherThree() {
        ToolCallbackProvider provider = () -> new ToolCallback[]{
                tool("list_pr_files"), tool("review_file"), tool("aggregate_findings"), tool("review_pr")};

        McpToolGateway gateway = new McpToolGateway(provider);

        assertThat(gateway.toolNames()).containsExactly("list_pr_files", "review_file", "aggregate_findings");
        assertThat(gateway.find("review_pr")).isEmpty();
        assertThat(gateway.find("no_such_tool")).isEmpty();
        assertThat(gateway.find("review_file")).isPresent();
    }

    private static ToolCallback tool(String name) {
        ToolDefinition definition = ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                return "";
            }
        };
    }
}
