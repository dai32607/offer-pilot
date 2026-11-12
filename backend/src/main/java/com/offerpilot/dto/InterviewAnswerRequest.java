package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A11 提交一轮回答入参。
 *
 * @param answer 候选人的文字回答
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InterviewAnswerRequest(
        @JsonProperty("answer") String answer
) {
}
