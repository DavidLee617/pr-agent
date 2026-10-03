package com.lee.pragent.agent;

import com.lee.pragent.model.PrReviewerTypes.CallMetrics;
import com.lee.pragent.model.PrReviewerTypes.FileReviewResult;
import com.lee.pragent.model.ToolResults;

import java.util.List;

/**
 * 一次任务（一条用户指令 → Agent 最终回答）的记录，由 AgentLoop 产生，ConsolePrinter 打印、A3 的报告也从这里取数。
 * C# 对照：≈ 一组不可变 DTO（record）。
 */
public final class TaskRecords {

    private TaskRecords() {
    }

    /**
     * 一次工具调用。
     *
     * @param result 工具返回的原文；出错时是错误原文（同样交回给了 Agent）
     */
    public record ToolInvocation(String id, String name, String arguments, String result, boolean error,
                                 long latencyMs) {}

    /**
     * Agent 的一轮：一次外层 LLM 调用，加上它要求的工具调用（按顺序执行）。
     *
     * @param text 本轮回复中的文本（含工具调用时可能为空或是简短说明；最后一轮是最终回答）
     */
    public record Step(int index, int inputTokens, int outputTokens, long latencyMs, String text,
                       List<ToolInvocation> tools) {
        public Step {
            tools = List.copyOf(tools);
        }
    }

    public enum Status {
        /** Agent 给出了纯文本回答。 */
        COMPLETED,
        /** 达到 max-steps 被强制停止。 */
        STEP_LIMIT,
        /** 外层 LLM 调用失败（重试后仍失败），本条指令的对话历史已回滚。 */
        FAILED
    }

    /**
     * @param answer  Agent 的最终回答；未正常结束时为 null
     * @param failure 失败原因；仅 FAILED 时非 null
     * @param wallMs  任务墙钟时间
     */
    public record TaskResult(String userInput, Status status, String answer, String failure, List<Step> steps,
                             long wallMs) {
        public TaskResult {
            steps = List.copyOf(steps);
        }

        public List<ToolInvocation> toolInvocations() {
            return steps.stream().flatMap(s -> s.tools().stream()).toList();
        }

        public int outerInputTokens() {
            return steps.stream().mapToInt(Step::inputTokens).sum();
        }

        public int outerOutputTokens() {
            return steps.stream().mapToInt(Step::outputTokens).sum();
        }

        /** 各次 review_file 内部（内层 DeepSeek）的 LLM 调用。 */
        public List<CallMetrics> innerCalls() {
            return toolInvocations().stream().flatMap(inv -> innerCallsOf(inv).stream()).toList();
        }
    }

    /** review_file 返回值里的 calls；其他工具或出错时为空。 */
    public static List<CallMetrics> innerCallsOf(ToolInvocation inv) {
        if (inv.error() || !"review_file".equals(inv.name())) {
            return List.of();
        }
        return ToolResults.fileReview(inv.result()).map(FileReviewResult::calls).orElse(List.of());
    }
}
