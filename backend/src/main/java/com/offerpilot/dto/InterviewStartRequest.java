package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A10 开启模拟面试入参（需求说明书 6.2 / 9 章 S6）。
 *
 * @param maxRound        最大轮次（缺省取配置 offerpilot.interview.default-max-round，上限 5）
 * @param focusDimension  可选，指定首轮考察维度；缺省由面试官 Agent 依据匹配缺口自选
 * @param caseId          可选，演示快照标识（如 C01）；会话结束时自动录制同名快照
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewStartRequest(
        @JsonProperty("max_round") Integer maxRound,
        @JsonProperty("focus_dimension") String focusDimension,
        @JsonProperty("case_id") String caseId
) {
}
