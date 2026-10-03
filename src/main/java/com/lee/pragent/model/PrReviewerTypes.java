package com.lee.pragent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * pr-reviewer MCP tool 返回值的镜像类型，用于反序列化工具返回的 JSON。
 * <p>
 * 本工程不依赖 pr-reviewer 的 jar（两边只通过 MCP 交互），所以字段名照抄 pr-reviewer 的 record；
 * category / severity / stage 用 String 保存，两边枚举各自改动时不会反序列化失败。
 * 都加了 ignoreUnknown：pr-reviewer 加字段不影响这边。
 * C# 对照：≈ 为外部 API 单独定义的 DTO，而不是引用对方的程序集。
 */
public final class PrReviewerTypes {

    private PrReviewerTypes() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(String file, int line, String category, String severity, String message,
                          String suggestion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CallMetrics(String label, int inputTokens, int outputTokens, long latencyMs, boolean success,
                              int attempts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReviewError(String stage, String file, String callLabel, boolean recovered, String message) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkippedFile(String path, String reason) {}

    /** review_file 的返回值。error 非 null 表示该文件审查失败。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileReviewResult(String file, List<Finding> findings, List<CallMetrics> calls, String error,
                                   List<ReviewError> errors) {
        public FileReviewResult {
            findings = findings == null ? List.of() : List.copyOf(findings);
            calls = calls == null ? List.of() : List.copyOf(calls);
            errors = errors == null ? List.of() : List.copyOf(errors);
        }
    }

    /** list_pr_files 的返回值。prFiles 在 A3 由 pr-reviewer 补上，旧版本为空。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PrFiles(String prUrl, String headSha, List<File> files, List<SkippedFile> skippedFiles,
                          List<String> prFiles) {
        public PrFiles {
            files = files == null ? List.of() : List.copyOf(files);
            skippedFiles = skippedFiles == null ? List.of() : List.copyOf(skippedFiles);
            prFiles = prFiles == null ? List.of() : List.copyOf(prFiles);
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record File(String path, String changeType, int hunks, long addedLines) {}
    }
}
