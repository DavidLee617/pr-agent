package com.lee.pragent.report;

import com.lee.pragent.agent.TaskRecords.TaskResult;
import com.lee.pragent.config.AgentProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

/**
 * 保存最近一个任务及其报告，供自动保存和 /save 使用。
 * <p>
 * 任务全过程（每轮 token、每次工具调用的参数和原始返回）已由 AgentLoop 记录在 {@link TaskResult} 里，
 * 这里只负责"最近一个任务"的状态和报告文件的落盘。报告生成一次后缓存，自动保存后再 /save 不会重复调用 aggregate。
 * C# 对照：≈ 一个单例服务，持有 Lazy&lt;AgentReport&gt;。
 */
@Component
public class TaskRecorder {

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ReportBuilder builder;
    private final Path reportsDir;

    private TaskResult lastTask;
    private AgentReport lastReport;

    public TaskRecorder(ReportBuilder builder, AgentProperties props) {
        this.builder = builder;
        this.reportsDir = Path.of(props.reportsDir());
    }

    public void record(TaskResult task) {
        this.lastTask = task;
        this.lastReport = null;
    }

    /** 最近一个任务调用过 aggregate_findings 时，自动保存到 reports-dir；否则返回 empty。 */
    public Optional<Path> autoSave() throws IOException {
        if (lastTask == null || !ReportBuilder.calledAggregate(lastTask)) {
            return Optional.empty();
        }
        return Optional.of(save(null));
    }

    /**
     * 保存最近一个任务的报告。
     *
     * @param path 目标路径；null 时保存到 reports-dir 下按时间命名的文件
     * @throws IllegalStateException 还没有任务
     */
    public Path save(Path path) throws IOException {
        if (lastTask == null) {
            throw new IllegalStateException("还没有任务，没有可保存的报告");
        }
        if (lastReport == null) {
            lastReport = builder.build(lastTask);
        }
        Path target = path != null ? path
                : reportsDir.resolve("agent-" + LocalDateTime.now().format(FILE_TIME) + ".json");
        builder.write(lastReport, target);
        return target;
    }
}
