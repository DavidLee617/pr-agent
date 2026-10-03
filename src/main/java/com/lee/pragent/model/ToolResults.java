package com.lee.pragent.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lee.pragent.model.PrReviewerTypes.FileReviewResult;
import com.lee.pragent.model.PrReviewerTypes.Finding;
import com.lee.pragent.model.PrReviewerTypes.PrFiles;

import java.util.List;
import java.util.Optional;

/**
 * 把工具参数 / 返回的 JSON 文本解析成镜像类型。解析失败返回 empty（Agent 可能传了不合规的参数，
 * 工具也可能返回错误文本），由调用方决定怎么显示 / 统计，不抛异常。
 * C# 对照：≈ 一组 TryParse 风格的静态方法。
 */
public final class ToolResults {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ToolResults() {
    }

    public static Optional<PrFiles> prFiles(String json) {
        return read(json, new TypeReference<>() {});
    }

    public static Optional<FileReviewResult> fileReview(String json) {
        return read(json, new TypeReference<>() {});
    }

    public static Optional<List<Finding>> findings(String json) {
        return read(json, new TypeReference<>() {});
    }

    /** aggregate_findings 的参数 {"findings": [...]} 中的 findings。 */
    public static Optional<List<Finding>> findingsArgument(String argumentsJson) {
        try {
            JsonNode node = JSON.readTree(argumentsJson).get("findings");
            if (node == null) {
                return Optional.empty();
            }
            return Optional.of(JSON.convertValue(node, new TypeReference<List<Finding>>() {}));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 参数 JSON 中某个字符串字段，如 review_file 的 filePath。 */
    public static Optional<String> stringArgument(String argumentsJson, String field) {
        try {
            JsonNode node = JSON.readTree(argumentsJson).get(field);
            return node != null && node.isTextual() ? Optional.of(node.asText()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static <T> Optional<T> read(String json, TypeReference<T> type) {
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(JSON.readValue(json, type));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
