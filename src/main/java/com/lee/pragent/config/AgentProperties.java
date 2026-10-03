package com.lee.pragent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * application.yml 中 agent.* 配置。
 *
 * @param maxSteps   单次任务最多几轮外层 LLM 调用
 * @param reportsDir 报告自动保存目录（相对工作目录）
 * @param prReviewer pr-reviewer 子进程的位置（MCP stdio 连接的 args 引用这里）
 */
@Validated
@ConfigurationProperties(prefix = "agent")
public record AgentProperties(
        @Positive int maxSteps,
        @NotBlank String reportsDir,
        @Valid PrReviewer prReviewer
) {
    public record PrReviewer(
            @NotBlank(message = "缺少 agent.pr-reviewer.jar") String jar,
            @NotBlank(message = "缺少 agent.pr-reviewer.config-dir") String configDir
    ) {}
}
