package com.lee.pragent.console;

import com.lee.pragent.mcp.McpToolGateway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 终端交互入口。
 * C# 对照：CommandLineRunner ≈ 在 Host 启动后执行的 IHostedService.StartAsync。
 * <p>
 * A1：只打印已连接的工具后返回（程序随即退出）。A2 在这里加上读取输入的循环。
 */
@Component
public class ReplRunner implements CommandLineRunner {

    private final McpToolGateway gateway;

    public ReplRunner(McpToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void run(String... args) {
        System.out.println("已连接 pr-reviewer，可用工具：" + String.join(", ", gateway.toolNames()));
    }
}
