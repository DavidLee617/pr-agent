package com.lee.pragent.console;

import com.lee.pragent.agent.AgentLoop;
import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.mcp.McpToolGateway;
import com.lee.pragent.report.TaskRecorder;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 终端 REPL：循环读取输入，/save /clear /exit 是命令，其余输入交给 AgentLoop。
 * C# 对照：CommandLineRunner ≈ 在 Host 启动后执行的 IHostedService.StartAsync；
 * 读输入 ≈ while ((line = Console.ReadLine()) != null)。
 */
@Component
public class ReplRunner implements CommandLineRunner {

    private final McpToolGateway gateway;
    private final AgentLoop agent;
    private final TaskRecorder recorder;

    public ReplRunner(McpToolGateway gateway, AgentLoop agent, TaskRecorder recorder) {
        this.gateway = gateway;
        this.agent = agent;
        this.recorder = recorder;
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
                String arg = line.substring("/save".length()).trim();
                save(arg.isEmpty() ? null : Path.of(arg));
                continue;
            }
            System.out.println();
            TaskResult result = agent.runTask(line);
            recorder.record(result);
            autoSave();
        }
    }

    private void autoSave() {
        try {
            recorder.autoSave().ifPresent(p -> System.out.println("   报告已自动保存：" + p));
        } catch (IOException | RuntimeException e) {
            System.out.println("   报告生成 / 保存失败：" + e.getMessage());
        }
    }

    private void save(Path path) {
        try {
            System.out.println("报告已保存：" + recorder.save(path));
        } catch (IOException | RuntimeException e) {
            System.out.println("报告生成 / 保存失败：" + e.getMessage());
        }
    }
}
