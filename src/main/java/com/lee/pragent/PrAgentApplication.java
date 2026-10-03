package com.lee.pragent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 程序入口。
 * C# 对照：≈ Program.cs 里的 Host.CreateApplicationBuilder(args).Build().Run()。
 * {@code @SpringBootApplication} 会扫描本包及子包下的 @Component 并注册到 DI 容器
 * （≈ 手动写一堆 services.AddSingleton&lt;T&gt;()）。
 */
@SpringBootApplication
@ConfigurationPropertiesScan // 自动注册所有 @ConfigurationProperties（≈ services.Configure<T>(config.GetSection(...))）
public class PrAgentApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context;
        try {
            context = SpringApplication.run(PrAgentApplication.class, args);
        } catch (Throwable e) {
            // 原因已由 Spring 打印到日志（如缺 llm.api-key）。启动失败时 MCP stdio 传输的读线程（非守护线程）
            // 可能已经启动，不会随容器关闭，JVM 会挂住不退出，所以这里直接退出；
            // pr-reviewer 子进程读到 stdin 关闭后自行结束
            System.exit(1);
            return;
        }
        // ReplRunner 返回后关闭容器：MCP 客户端随之关闭，pr-reviewer 子进程退出
        System.exit(SpringApplication.exit(context));
    }
}
