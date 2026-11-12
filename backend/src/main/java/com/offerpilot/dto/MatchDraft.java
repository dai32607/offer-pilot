package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 匹配评估模型的原始输出草稿（LLM 侧）。
 * <p>
 * 与最终报告 {@link MatchReport} 的关键差别：草稿只包含模型可判定的事实性内容
 * （维度名、0-10 原始分、结论、证据原文摘抄、不确定标记），
 * <strong>不包含权重、维度得分、总分与等级</strong>——权重来自固定评分策略，
 * 维度得分与总分由服务端加权计算，避免模型直接给出无解释的总分。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchDraft(
        @JsonProperty("dimensions") List<DraftDimension> dimensions,
        @JsonProperty("hits") List<DraftHit> hits,
        @JsonProperty("gaps") List<DraftGap> gaps,
        @JsonProperty("risks") List<DraftRisk> risks
) {

    /** 单个维度草稿：rawScore 为 0-10 的相对匹配度，由服务端换算为加权得分。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftDimension(
            @JsonProperty("name") String name,
            @JsonProperty("rawScore") Double rawScore,
            @JsonProperty("conclusion") String conclusion,
            @JsonProperty("evidence") List<DraftEvidence> evidence,
            @JsonProperty("uncertainty") Boolean uncertainty
    ) {
    }

    /** 证据条目：source 取值 jd / resume，quote 必须为对应原文的逐字摘抄。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftEvidence(
            @JsonProperty("source") String source,
            @JsonProperty("quote") String quote
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftHit(
            @JsonProperty("requirement") String requirement,
            @JsonProperty("resumeQuote") String resumeQuote,
            @JsonProperty("comment") String comment
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftGap(
            @JsonProperty("requirement") String requirement,
            @JsonProperty("gapType") String gapType,
            @JsonProperty("severity") String severity,
            @JsonProperty("suggestion") String suggestion
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DraftRisk(
            @JsonProperty("risk") String risk,
            @JsonProperty("reason") String reason,
            @JsonProperty("evidenceRef") String evidenceRef
    ) {
    }
}
