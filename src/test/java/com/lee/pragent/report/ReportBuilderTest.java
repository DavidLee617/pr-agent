package com.lee.pragent.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.pragent.agent.TaskRecords.Status;
import com.lee.pragent.agent.TaskRecords.Step;
import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.agent.TaskRecords.ToolInvocation;
import com.lee.pragent.model.PrReviewerTypes.CallMetrics;
import com.lee.pragent.model.PrReviewerTypes.FileReviewResult;
import com.lee.pragent.model.PrReviewerTypes.Finding;
import com.lee.pragent.model.PrReviewerTypes.PrFiles;
import com.lee.pragent.model.PrReviewerTypes.SkippedFile;
import com.lee.pragent.report.AgentReport.AlteredFinding;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手工构造任务记录，验证报告的各项统计。aggregate_findings 用一个与 pr-reviewer 同口径的假实现
 * （file + line + category 去重保留最高 severity，按 severity → file → line 排序）。
 */
class ReportBuilderTest {

    private static final String URL = "https://github.com/o/r/pull/1";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ReportBuilder builder = new ReportBuilder(ReportBuilderTest::fakeAggregate, JSON);

    private final Finding a1 = new Finding("A.java", 10, "LOGIC", "HIGH", "a1", "");
    private final Finding a2 = new Finding("A.java", 20, "STYLE", "LOW", "a2", "");
    private final Finding b1 = new Finding("B.java", 5, "SECURITY", "HIGH", "b1", "");

    @Test
    void detectsOrchestrationLosses() {
        Finding a2Altered = new Finding("A.java", 20, "STYLE", "MEDIUM", "a2", "");
        Finding fabricated = new Finding("B.java", 99, "LOGIC", "HIGH", "编造", "");
        TaskResult task = task(Status.COMPLETED,
                step(1, listPrFiles()),
                step(2, reviewFile("A.java", List.of(a1, a2), null),
                        reviewFile("B.java", List.of(b1), null),
                        reviewFile("B.java", List.of(b1), null),
                        toolError("review_file", "工具调用失败：文件不在 PR 中")),
                // 分批汇总：Agent 漏传 b1、把 a2 改成 MEDIUM、编造了一条
                step(3, aggregate(List.of(a1, a2Altered)), aggregate(List.of(fabricated))));

        AgentReport r = builder.build(task);

        assertThat(r.mode()).isEqualTo("AGENT");
        assertThat(r.prUrl()).isEqualTo(URL);
        assertThat(r.headSha()).isEqualTo("sha1");
        assertThat(r.prFiles()).containsExactly("A.java", "B.java", "C.java", "x.yml");
        assertThat(r.skippedFiles()).extracting(SkippedFile::path).containsExactly("x.yml");
        // 两批并集后去重排序
        assertThat(r.findings()).containsExactly(a1, fabricated, a2Altered);
        assertThat(r.agent().rawFindings()).containsExactly(a1, b1, a2);
        assertThat(r.agent().droppedFindings()).containsExactly(b1);
        assertThat(r.agent().fabricatedFindings()).containsExactly(fabricated);
        assertThat(r.agent().alteredFindings())
                .containsExactly(new AlteredFinding("A.java", 20, "STYLE", "LOW", "MEDIUM"));
        assertThat(r.agent().unreviewedFiles()).containsExactly("C.java");
        assertThat(r.agent().repeatedReviews()).containsExactly(Map.entry("B.java", 2));
        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.stage()).isEqualTo("INTERNAL");
            assertThat(e.callLabel()).isEqualTo("agent#2");
            assertThat(e.message()).contains("文件不在 PR 中");
        });
        assertThat(r.failedFiles()).isEmpty();
        assertThat(r.agent().stoppedByStepLimit()).isFalse();
    }

    @Test
    void sumsOuterAndInnerTokens() {
        TaskResult task = task(Status.COMPLETED,
                step(1, listPrFiles()),
                step(2, reviewFile("A.java", List.of(a1), null)),
                step(3, aggregate(List.of(a1))));

        AgentReport r = builder.build(task);

        // 外层每轮 100 / 10，review_file 内层 1000 / 50
        assertThat(r.calls()).extracting(CallMetrics::label)
                .containsExactly("agent#1", "agent#2", "agent#3", "A.java");
        assertThat(r.agent().outerInputTokens()).isEqualTo(300);
        assertThat(r.agent().outerOutputTokens()).isEqualTo(30);
        assertThat(r.agent().innerInputTokens()).isEqualTo(1000);
        assertThat(r.agent().innerOutputTokens()).isEqualTo(50);
        assertThat(r.totalInputTokens()).isEqualTo(1300);
        assertThat(r.totalOutputTokens()).isEqualTo(80);
        assertThat(r.totalLatencyMs()).isEqualTo(5000);
        assertThat(r.agent().steps()).hasSize(3);
    }

    @Test
    void failedFileThatSucceedsOnRetryIsNotFailed() {
        TaskResult task = task(Status.COMPLETED,
                step(1, listPrFiles()),
                step(2, reviewFile("A.java", List.of(), "LLM 超时"), reviewFile("B.java", List.of(), "LLM 超时")),
                step(3, reviewFile("A.java", List.of(a1), null)));

        AgentReport r = builder.build(task);

        assertThat(r.failedFiles()).containsExactly("B.java");
        assertThat(r.agent().repeatedReviews()).containsExactly(Map.entry("A.java", 2));
    }

    @Test
    void withoutAggregateNoAutoReportButRawFindingsStillComputed() {
        TaskResult task = task(Status.STEP_LIMIT,
                step(1, listPrFiles()),
                step(2, reviewFile("A.java", List.of(a2, a1), null)));

        assertThat(ReportBuilder.calledAggregate(task)).isFalse();
        AgentReport r = builder.build(task);
        assertThat(r.findings()).isEmpty();
        assertThat(r.agent().rawFindings()).containsExactly(a1, a2);
        assertThat(r.agent().droppedFindings()).containsExactly(a1, a2);
        assertThat(r.agent().stoppedByStepLimit()).isTrue();
    }

    @Test
    void failedAggregateCallDoesNotCount() {
        TaskResult task = task(Status.COMPLETED, step(1, toolError("aggregate_findings", "参数不是合法的 JSON")));

        assertThat(ReportBuilder.calledAggregate(task)).isFalse();
    }

    @Test
    void jsonFieldsMatchReviewReport() throws Exception {
        TaskResult task = task(Status.COMPLETED, step(1, listPrFiles()), step(2, aggregate(List.of(a1))));

        JsonNode node = JSON.readTree(JSON.writeValueAsString(builder.build(task)));

        // pr-reviewer ReviewReport 的组件（顺序一致），再加 agent
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        assertThat(names).containsExactly("mode", "prUrl", "headSha", "prFiles", "findings", "failedFiles",
                "skippedFiles", "errors", "calls", "totalLatencyMs", "totalInputTokens", "totalOutputTokens",
                "agent");
        assertThat(node.get("findings").get(0).fieldNames()).toIterable()
                .containsExactly("file", "line", "category", "severity", "message", "suggestion");
    }

    // ---- 构造任务记录 ----

    private static TaskResult task(Status status, Step... steps) {
        return new TaskResult("review", status, "done", null, List.of(steps), 5000);
    }

    private static Step step(int index, ToolInvocation... tools) {
        return new Step(index, 100, 10, 1000, "", List.of(tools));
    }

    private static ToolInvocation listPrFiles() {
        PrFiles pr = new PrFiles(URL, "sha1",
                List.of(new PrFiles.File("A.java", "MODIFIED", 1, 3), new PrFiles.File("B.java", "ADDED", 1, 5),
                        new PrFiles.File("C.java", "MODIFIED", 1, 1)),
                List.of(new SkippedFile("x.yml", "不在审查范围")),
                List.of("A.java", "B.java", "C.java", "x.yml"));
        return ok("list_pr_files", "{\"prUrl\":\"" + URL + "\"}", pr);
    }

    private static ToolInvocation reviewFile(String file, List<Finding> findings, String error) {
        FileReviewResult r = new FileReviewResult(file, findings,
                List.of(new CallMetrics(file, 1000, 50, 2000, error == null, 1)), error, List.of());
        return ok("review_file", "{\"prUrl\":\"" + URL + "\",\"filePath\":\"" + file + "\"}", r);
    }

    private static ToolInvocation aggregate(List<Finding> findings) {
        try {
            String args = JSON.writeValueAsString(Map.of("findings", findings));
            return ok("aggregate_findings", args, fakeAggregate(findings));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolInvocation toolError(String name, String message) {
        return new ToolInvocation("id", name, "{}", message, true, 10);
    }

    private static ToolInvocation ok(String name, String args, Object result) {
        try {
            return new ToolInvocation("id", name, args, JSON.writeValueAsString(result), false, 10);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static final List<String> SEVERITY_ORDER = List.of("HIGH", "MEDIUM", "LOW");

    /** 与 pr-reviewer FindingAggregator 同口径。 */
    private static List<Finding> fakeAggregate(List<Finding> findings) {
        Map<String, Finding> best = new LinkedHashMap<>();
        for (Finding f : findings) {
            String key = f.file() + "|" + f.line() + "|" + f.category();
            Finding old = best.get(key);
            if (old == null || SEVERITY_ORDER.indexOf(f.severity()) < SEVERITY_ORDER.indexOf(old.severity())) {
                best.put(key, f);
            }
        }
        return best.values().stream()
                .sorted(Comparator.comparingInt((Finding f) -> SEVERITY_ORDER.indexOf(f.severity()))
                        .thenComparing(Finding::file).thenComparingInt(Finding::line))
                .toList();
    }
}
