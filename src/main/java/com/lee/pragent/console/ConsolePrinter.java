package com.lee.pragent.console;

import com.lee.pragent.agent.AgentListener;
import com.lee.pragent.agent.TaskRecords;
import com.lee.pragent.agent.TaskRecords.Step;
import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.agent.TaskRecords.ToolInvocation;
import com.lee.pragent.config.AgentProperties;
import com.lee.pragent.model.PrReviewerTypes.CallMetrics;
import com.lee.pragent.model.PrReviewerTypes.FileReviewResult;
import com.lee.pragent.model.PrReviewerTypes.PrFiles;
import com.lee.pragent.model.PrReviewerTypes.SkippedFile;
import com.lee.pragent.model.ToolResults;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.PrintStream;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 把 Agent 每一轮、每次工具调用实时打印到终端（stdout）。工具结果只打印摘要，不打印完整 JSON。
 * C# 对照：≈ 订阅 Agent 事件、往 Console.Out 写的观察者。
 */
@Component
public class ConsolePrinter implements AgentListener {

    private static final int MAX_TEXT = 300;

    private final PrintStream out;
    private final int maxSteps;

    @Autowired // 有两个构造函数（另一个给测试用），告诉 Spring 用这个（≈ C# DI 选择 public 构造函数）
    public ConsolePrinter(AgentProperties props) {
        this(System.out, props.maxSteps());
    }

    ConsolePrinter(PrintStream out, int maxSteps) {
        this.out = out;
        this.maxSteps = maxSteps;
    }

    @Override
    public void stepStarted(int index) {
        // 不换行：外层 LLM 返回后在同一行补上 token
        out.printf("[Agent 第 %d 轮] ", index);
        out.flush();
    }

    @Override
    public void llmResponded(Step step, int toolCallCount) {
        out.printf("(外层 in %s / out %s token, %s)%n", num(step.inputTokens()), num(step.outputTokens()),
                secs(step.latencyMs()));
        if (toolCallCount > 0 && step.text() != null && !step.text().isBlank()) {
            out.println("  （Agent 说明）" + oneLine(step.text()));
        }
    }

    @Override
    public void toolStarted(String name, String arguments) {
        // 不换行：工具返回后在同一行补上耗时
        out.print("  → 调用 " + name + "(" + argumentSummary(name, arguments) + ")  ");
        out.flush();
    }

    @Override
    public void toolFinished(ToolInvocation inv) {
        StringBuilder line = new StringBuilder(secs(inv.latencyMs()));
        List<CallMetrics> inner = TaskRecords.innerCallsOf(inv);
        if (!inner.isEmpty()) {
            line.append("  内层 in ").append(num(inner.stream().mapToInt(CallMetrics::inputTokens).sum()))
                    .append(" / out ").append(num(inner.stream().mapToInt(CallMetrics::outputTokens).sum()))
                    .append(" token");
        }
        out.println(line);
        out.println("  ← " + resultSummary(inv));
    }

    @Override
    public void taskFinished(TaskResult r) {
        out.println();
        switch (r.status()) {
            case COMPLETED -> out.println("Agent：" + r.answer());
            case STEP_LIMIT -> out.println("⚠ 达到步数上限（max-steps=" + maxSteps + "），任务被强制停止");
            case FAILED -> {
                out.println("✗ 任务失败：" + oneLine(r.failure()));
                if (r.failure() != null && r.failure().toLowerCase(Locale.ROOT).contains("context")) {
                    out.println("  可能是上下文超长，可输入 /clear 清空对话后重试");
                }
                out.println("  本条指令已从对话历史中撤回，可直接重发");
            }
        }
        List<CallMetrics> inner = r.innerCalls();
        out.println();
        out.printf("── 本次任务：%d 轮 · %d 次工具调用 · 外层 in %s / out %s · 内层 in %s / out %s · %s%n",
                r.steps().size(), r.toolInvocations().size(),
                num(r.outerInputTokens()), num(r.outerOutputTokens()),
                num(inner.stream().mapToInt(CallMetrics::inputTokens).sum()),
                num(inner.stream().mapToInt(CallMetrics::outputTokens).sum()),
                secs(r.wallMs()));
    }

    static String argumentSummary(String name, String arguments) {
        return switch (name) {
            case "list_pr_files" -> ToolResults.stringArgument(arguments, "prUrl")
                    .map(url -> "prUrl=" + shortUrl(url)).orElse(oneLine(arguments));
            case "review_file" -> ToolResults.stringArgument(arguments, "filePath").orElse(oneLine(arguments));
            case "aggregate_findings" -> ToolResults.findingsArgument(arguments)
                    .map(f -> f.size() + " 条").orElse(oneLine(arguments));
            default -> oneLine(arguments);
        };
    }

    static String resultSummary(ToolInvocation inv) {
        if (inv.error()) {
            return "错误：" + oneLine(inv.result());
        }
        return switch (inv.name()) {
            case "list_pr_files" -> ToolResults.prFiles(inv.result()).map(ConsolePrinter::prFilesSummary)
                    .orElse(oneLine(inv.result()));
            case "review_file" -> ToolResults.fileReview(inv.result()).map(ConsolePrinter::fileReviewSummary)
                    .orElse(oneLine(inv.result()));
            case "aggregate_findings" -> ToolResults.findings(inv.result()).map(f -> "去重后 " + f.size() + " 条")
                    .orElse(oneLine(inv.result()));
            default -> oneLine(inv.result());
        };
    }

    private static String prFilesSummary(PrFiles pr) {
        String s = pr.files().size() + " 个待审文件，" + pr.skippedFiles().size() + " 个跳过";
        if (!pr.skippedFiles().isEmpty()) {
            s += "（" + pr.skippedFiles().stream().map(ConsolePrinter::skipped).collect(Collectors.joining("；")) + "）";
        }
        return oneLine(s);
    }

    private static String skipped(SkippedFile f) {
        String path = f.path() == null ? "?" : f.path();
        return path.substring(path.lastIndexOf('/') + 1) + "：" + f.reason();
    }

    private static String fileReviewSummary(FileReviewResult r) {
        if (r.error() != null) {
            return "审查失败：" + oneLine(r.error());
        }
        return r.findings().size() + " 个 findings";
    }

    private static String shortUrl(String url) {
        int i = url.indexOf("/pull/");
        return i >= 0 ? "…" + url.substring(i) : url;
    }

    static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() > MAX_TEXT ? t.substring(0, MAX_TEXT) + "…" : t;
    }

    private static String num(int n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }
}
