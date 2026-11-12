package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 匹配评估报告（对外输出，对应需求说明书 3.4 的 match_report）。
 * <p>
 * overallScore 由服务端按固定权重对维度分加权计算（非模型直出）；
 * 证据无法在原文中定位的维度会被剔除出总分并重新归一化剩余权重。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MatchReport(
        double overallScore,
        String level,
        List<DimensionScore> dimensions,
        List<HitItem> hits,
        List<GapItem> gaps,
        List<RiskItem> risks,
        List<MatchEvidence> evidenceIndex,
        List<String> uncertaintyFlags,
        List<String> notes,
        List<RewriteSuggestion> rewriteSuggestions
) {

    /**
     * 维度评分：maxScore 等于该维度权重（各维度权重之和为 100），
     * evidence 仅保留通过原文子串校验的条目，uncertainty 为 true 表示信息不足。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DimensionScore(
            String name,
            double weight,
            double score,
            double maxScore,
            String conclusion,
            List<MatchEvidence> evidence,
            boolean uncertainty
    ) {
    }

    /** 证据索引条目：evidenceId 供 risks[].evidenceRef 等下游引用。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchEvidence(
            String evidenceId,
            String source,
            String quote
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HitItem(
            String requirement,
            String resumeQuote,
            String comment
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GapItem(
            String requirement,
            String gapType,
            String severity,
            String suggestion
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskItem(
            String risk,
            String reason,
            String evidenceRef
    ) {
    }

    /**
     * 简历片段改写建议：由推理模型基于匹配报告与简历原文生成，
     * original 必须为简历原文逐字片段；reference 关联对应缺口/风险或维度名称，可为 null。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RewriteSuggestion(
            String original,
            String rewritten,
            String reason,
            String reference
    ) {
    }
}
