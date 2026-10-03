package com.lee.pragent.console;

import com.lee.pragent.agent.AgentLoop;
import com.lee.pragent.mcp.McpToolGateway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 终端 REPL：循环读取输入，/save /clear /exit 是命令，其余输入交给 AgentLoop。
 * C# 对照：CommandLineRunner ≈ 在 Host 启动后执行的 IHostedService.StartAsync；
 * 读输入 ≈ while ((line = Console.ReadLine()) != null)。
 */
@Component
public class ReplRunner implements CommandLineRunner {

    private final McpToolGateway gateway;
    private final AgentLoop agent;

    public ReplRunner(McpToolGateway gateway, AgentLoop agent) {
        this.gateway = gateway;
        this.agent = agent;
    }

    @Override
    public void run(String... args) throws IOException {
        System.out.println("已连接 pr-reviewer，可用工具：" + String.join(", ", gateway.toolNames()));
        System.out.println("输入指令开始对话；命令：/save [路径]  /clear  /exit");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.print("\n> ");
            System.out.flush();
            String line = in.readLine();
            if (line == null) { // Ctrl-D
                System.out.println();
                return;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals("/exit")) {
                return;
            }
            if (line.equals("/clear")) {
                agent.clear();
                System.out.println("对话历史已清空");
                continue;
            }
            if (line.equals("/save") || line.startsWith("/save ")) {
                System.out.println("/save 将在 A3 实现");
                continue;
            }
            System.out.println();
            agent.runTask(line);
        }
    }
}
