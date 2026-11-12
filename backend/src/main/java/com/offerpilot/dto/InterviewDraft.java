package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 面试官 Agent（3.6 / 7.7）单轮输出：本轮问题 + 对上一轮回答的评分。
 * 字段名使用下划线 JSON 命名，与需求说明书 Schema 一一对应。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewDraft(
        Integer round,
        String question,
        @JsonProperty("focus_dimension") String focusDimension,
        @JsonProperty("evidence_ref") String evidenceRef,
        @JsonProperty("answer_points") List<String> answerPoints,
        @JsonProperty("followup_hint") String followupHint,
        Evaluation evaluation
) {

    /** 对上一轮回答的评分（首轮为 null）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Evaluation(
            Double score,
            List<String> strengths,
            List<String> improvements
    ) {
        /** 规范化：分数越界钳制到 0-100，strengths / improvements 各不超过 3 条。 */
        public Evaluation normalized() {
            double s = score == null ? 0.0 : Math.max(0.0, Math.min(100.0, score));
            return new Evaluation(s, limit(strengths), limit(improvements));
        }

        private static List<String> limit(List<String> list) {
            if (list == null) {
                return List.of();
            }
            return list.stream().filter(v -> v != null && !v.isBlank()).limit(3).toList();
        }
    }
}
