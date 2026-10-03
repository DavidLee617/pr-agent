package com.lee.pragent.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.pragent.agent.TaskRecords.Status;
import com.lee.pragent.agent.TaskRecords.Step;
import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.agent.TaskRecords.ToolInvocation;
import com.lee.pragent.mcp.McpToolGateway;
import com.lee.pragent.mcp.McpToolGateway.ToolOutcome;
import com.lee.pragent.model.PrReviewerTypes.CallMetrics;
import com.lee.pragent.model.PrReviewerTypes.FileReviewResult;
import com.lee.pragent.model.PrReviewerTypes.Finding;
import com.lee.pragent.model.PrReviewerTypes.PrFiles;
import com.lee.pragent.model.PrReviewerTypes.ReviewError;
import com.lee.pragent.model.ToolResults;
import com.lee.pragent.report.AgentReport.AlteredFinding;
import com.lee.pragent.report.AgentReport.StepSummary;
import com.lee.pragent.report.AgentReport.ToolSummary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * 根据一次任务的记录生成报告（DESIGN 第 7 节）。一个任务只涉及一个 PR。
 * <p>
 * 去重排序复用 pr-reviewer 的 aggregate_findings（通过 MCP 调用），保证与 Workflow 模式口径一致。
 * C# 对照：≈ 一个纯函数式的 ReportFactory，依赖一个 Func&lt;List&lt;Finding&gt;, List&lt;Finding&gt;&gt;。
 */
@Component
public class ReportBuilder {

    /** pr-reviewer ReviewStage 的取值之一；eval 会把 errors 反序列化成它的枚举，不能随意起名。 */
    private static final String STAGE_INTERNAL = "INTERNAL";

    private final UnaryOperator<List<Finding>> aggregator;
    private final ObjectMapper json;

    @Autowired // 有两个构造函数（另一个给测试用），告诉 Spring 用这个
    public ReportBuilder(McpToolGateway gateway, ObjectMapper json) {
        this(findings -> aggregateViaMcp(gateway, json, findings), json);
    }

    ReportBuilder(UnaryOperator<List<Finding>> aggregator, ObjectMapper json) {
        this.aggregator = aggregator;
        this.json = json;
    }

    /** 任务中是否成功调用过 aggregate_findings（是则自动生成报告）。 */
    public static boolean calledAggregate(TaskResult task) {
        return task.toolInvocations().stream()
                .anyMatch(inv -> !inv.error() && "aggregate_findings".equals(inv.name()));
    }

    public AgentReport build(TaskResult task) {
        List<ToolInvocation> invocations = task.toolInvocations();

        // ---- list_pr_files：PR 基本信息（取最后一次成功的）----
        Optional<PrFiles> pr = invocations.stream()
                .filter(inv -> !inv.error() && "list_pr_files".equals(inv.name()))
                .map(inv -> ToolResults.prFiles(inv.result()))
                .flatMap(Optional::stream)
                .reduce((first, second) -> second);
        String prUrl = pr.map(PrFiles::prUrl).orElseGet(() -> prUrlFromArguments(invocations));

        // ---- review_file：原始 findings、失败文件、内层 calls、errors ----
        List<Finding> rawUnaggregated = new ArrayList<>();
        Map<String, Integer> reviewCounts = new LinkedHashMap<>();
        Set<String> failed = new LinkedHashSet<>();
        Set<String> succeeded = new LinkedHashSet<>();
        List<ReviewError> errors = new ArrayList<>();
        for (Step step : task.steps()) {
            for (ToolInvocation inv : step.tools()) {
                if (inv.error()) {
                    // 工具调用层面的错误（工具不存在、参数非法、MCP isError）
                    errors.add(new ReviewError(STAGE_INTERNAL, null, "agent#" + step.index(), false,
                            "工具调用 " + inv.name() + " 失败：" + inv.result()));
                    continue;
                }
                if (!"review_file".equals(inv.name())) {
                    continue;
                }
                Optional<FileReviewResult> parsed = ToolResults.fileReview(inv.result());
                if (parsed.isEmpty()) {
                    errors.add(new ReviewError(STAGE_INTERNAL, null, "agent#" + step.index(), false,
                            "review_file 返回无法解析：" + inv.result()));
                    continue;
                }
                FileReviewResult r = parsed.get();
                reviewCounts.merge(r.file(), 1, Integer::sum);
                rawUnaggregated.addAll(r.findings());
                errors.addAll(r.errors());
                if (r.error() != null) {
                    failed.add(r.file());
                } else {
                    succeeded.add(r.file());
                }
            }
        }
        // 重审成功的文件不算失败
        List<String> failedFiles = failed.stream().filter(f -> !succeeded.contains(f)).toList();

        // ---- findings：Agent 交给 aggregate_findings 的各次结果取并集，再去重排序 ----
        List<Finding> agentUnion = invocations.stream()
                .filter(inv -> !inv.error() && "aggregate_findings".equals(inv.name()))
                .map(inv -> ToolResults.findings(inv.result()).orElse(List.of()))
                .flatMap(List::stream)
                .toList();
        List<Finding> findings = agentUnion.isEmpty() ? List.of() : aggregator.apply(agentUnion);
        List<Finding> rawFindings = rawUnaggregated.isEmpty() ? List.of() : aggregator.apply(rawUnaggregated);

        // ---- 编排损失：按 file + line + category 比对 ----
        Map<String, Finding> rawByKey = byKey(rawFindings);
        Map<String, Finding> agentByKey = byKey(findings);
        List<Finding> dropped = rawFindings.stream().filter(f -> !agentByKey.containsKey(key(f))).toList();
        List<Finding> fabricated = findings.stream().filter(f -> !rawByKey.containsKey(key(f))).toList();
        List<AlteredFinding> altered = findings.stream()
                .filter(f -> rawByKey.containsKey(key(f)))
                .filter(f -> !Objects.equals(rawByKey.get(key(f)).severity(), f.severity()))
                .map(f -> new AlteredFinding(f.file(), f.line(), f.category(),
                        rawByKey.get(key(f)).severity(), f.severity()))
                .toList();
        List<String> unreviewed = pr.map(p -> p.files().stream()
                .map(PrFiles.File::path)
                .filter(path -> !reviewCounts.containsKey(path))
                .toList()).orElse(List.of());
        Map<String, Integer> repeated = new LinkedHashMap<>();
        reviewCounts.forEach((file, n) -> {
            if (n > 1) {
                repeated.put(file, n);
            }
        });

        // ---- calls / token：外层每轮一条 agent#n，加上内层 calls ----
        List<CallMetrics> calls = new ArrayList<>();
        for (Step step : task.steps()) {
            calls.add(new CallMetrics("agent#" + step.index(), step.inputTokens(), step.outputTokens(),
                    step.latencyMs(), true, 1));
        }
        List<CallMetrics> inner = task.innerCalls();
        calls.addAll(inner);
        int outerIn = task.outerInputTokens();
        int outerOut = task.outerOutputTokens();
        int innerIn = inner.stream().mapToInt(CallMetrics::inputTokens).sum();
        int innerOut = inner.stream().mapToInt(CallMetrics::outputTokens).sum();

        List<StepSummary> steps = task.steps().stream()
                .map(s -> new StepSummary(s.index(), s.inputTokens(), s.outputTokens(), s.latencyMs(),
                        s.tools().stream()
                                .map(t -> new ToolSummary(t.name(), t.arguments(), t.error(), t.latencyMs()))
                                .toList()))
                .toList();

        AgentReport.Agent agent = new AgentReport.Agent(steps, task.status() == Status.STEP_LIMIT,
                outerIn, outerOut, innerIn, innerOut, rawFindings, dropped, fabricated, altered, unreviewed,
                repeated);
        return new AgentReport(AgentReport.MODE, prUrl, pr.map(PrFiles::headSha).orElse(null),
                pr.map(PrFiles::prFiles).orElse(List.of()), findings, failedFiles,
                pr.map(PrFiles::skippedFiles).orElse(List.of()), errors, calls, task.wallMs(),
                outerIn + innerIn, outerOut + innerOut, agent);
    }

    /** 写成格式化的 JSON，自动创建父目录。 */
    public void write(AgentReport report, Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        json.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), report);
    }

    private static String key(Finding f) {
        return f.file() + "\u0000" + f.line() + "\u0000" + f.category();
    }

    private static Map<String, Finding> byKey(List<Finding> findings) {
        Map<String, Finding> map = new LinkedHashMap<>();
        findings.forEach(f -> map.putIfAbsent(key(f), f));
        return map;
    }

    /** Agent 没调用 list_pr_files 时，从 review_file 的参数里取 prUrl。 */
    private static String prUrlFromArguments(List<ToolInvocation> invocations) {
        return invocations.stream()
                .map(inv -> ToolResults.stringArgument(inv.arguments(), "prUrl"))
                .flatMap(Optional::stream)
                .findFirst().orElse(null);
    }

    private static List<Finding> aggregateViaMcp(McpToolGateway gateway, ObjectMapper json, List<Finding> findings) {
        String args;
        try {
            args = json.writeValueAsString(Map.of("findings", findings));
        } catch (IOException e) {
            throw new IllegalStateException("序列化 findings 失败", e);
        }
        ToolOutcome outcome = gateway.call("aggregate_findings", args);
        if (outcome.error()) {
            throw new IllegalStateException("生成报告时调用 aggregate_findings 失败：" + outcome.text());
        }
        return ToolResults.findings(outcome.text())
                .orElseThrow(() -> new IllegalStateException("aggregate_findings 返回无法解析：" + outcome.text()));
    }
}
