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

    @Test
    void unwrapsMcpTextContent() {
        assertThat(McpToolGateway.unwrapContent("[{\"type\":\"text\",\"text\":\"{\\\"a\\\":1}\"}]"))
                .isEqualTo("{\"a\":1}");
        assertThat(McpToolGateway.unwrapContent("not json")).isEqualTo("not json");
        assertThat(McpToolGateway.unwrapContent("[1,2]")).isEqualTo("[1,2]");
    }

    @Test
    void callRejectsNonObjectArguments() {
        McpToolGateway gateway = new McpToolGateway(() -> new ToolCallback[]{tool("review_file")});

        assertThat(gateway.call("review_file", "[]").error()).isTrue();
        assertThat(gateway.call("review_file", "{bad").text()).contains("不是合法的 JSON");
        assertThat(gateway.call("review_file", "{}").error()).isFalse();
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
