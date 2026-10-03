package com.lee.pragent.report;

import com.lee.pragent.model.PrReviewerTypes.CallMetrics;
import com.lee.pragent.model.PrReviewerTypes.Finding;
import com.lee.pragent.model.PrReviewerTypes.ReviewError;
import com.lee.pragent.model.PrReviewerTypes.SkippedFile;

import java.util.List;
import java.util.Map;

/**
 * Agent 模式的报告。除 {@code agent} 外，字段名、顺序与 pr-reviewer 的 {@code ReviewReport} 一致，
 * 所以 pr-reviewer 的 {@code eval} 能直接读取（它忽略未知字段 agent）。
 * C# 对照：≈ 一个用 System.Text.Json 序列化的 record，属性名即 JSON 字段名。
 *
 * @param findings Agent 实际交给 aggregate_findings 的结果（各次调用的并集，再去重排序）；eval 按它算召回
 */
public record AgentReport(
        String mode,
        String prUrl,
        String headSha,
        List<String> prFiles,
        List<Finding> findings,
        List<String> failedFiles,
        List<SkippedFile> skippedFiles,
        List<ReviewError> errors,
        List<CallMetrics> calls,
        long totalLatencyMs,
        int totalInputTokens,
        int totalOutputTokens,
        Agent agent
) {
    public static final String MODE = "AGENT";

    /**
     * Agent 编排本身的记录与统计。
     *
     * @param rawFindings        代码把所有 review_file 原始 findings 直接交给 aggregate_findings 的结果（不经过 Agent）
     * @param droppedFindings    在 rawFindings 中、不在 findings 中（Agent 漏传）
     * @param fabricatedFindings 在 findings 中、不在 rawFindings 中（Agent 编造或改了位置 / 类别）
     * @param alteredFindings    按 file + line + category 匹配上但 severity 不同（Agent 改了严重程度）
     * @param unreviewedFiles    list_pr_files 列出的待审文件中，一次 review_file 都没调用的
     * @param repeatedReviews    被 review_file 调用超过一次的文件 → 次数
     */
    public record Agent(
            List<StepSummary> steps,
            boolean stoppedByStepLimit,
            int outerInputTokens,
            int outerOutputTokens,
            int innerInputTokens,
            int innerOutputTokens,
            List<Finding> rawFindings,
            List<Finding> droppedFindings,
            List<Finding> fabricatedFindings,
            List<AlteredFinding> alteredFindings,
            List<String> unreviewedFiles,
            Map<String, Integer> repeatedReviews
    ) {}

    /** 一轮：外层 token、耗时、本轮调用的工具。 */
    public record StepSummary(int index, int inputTokens, int outputTokens, long latencyMs, List<ToolSummary> tools) {}

    public record ToolSummary(String name, String arguments, boolean error, long latencyMs) {}

    /** severity 被 Agent 改动的 finding：raw 是 review_file 原始的，agent 是 Agent 交出来的。 */
    public record AlteredFinding(String file, int line, String category, String rawSeverity, String agentSeverity) {}
}
