package com.offerpilot.agent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.offerpilot.dto.MatchReport;
import com.offerpilot.llm.ChatResponse;
import com.offerpilot.llm.DeepSeekClient;
import com.offerpilot.llm.StructuredJsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 改写建议 Agent：匹配评估完成后，使用推理模型 deepseek-v4-pro 基于
 * 匹配报告（hits / gaps / risks / dimensions）与简历原文生成简历片段改写建议。
 * <p>
 * 降级契约：未配置 Key、调用失败或 JSON 解析失败时一律返回空列表，
 * 绝不抛出异常阻塞匹配主流程；original 必须是简历原文的逐字片段（尽力约束），
 * 非空校验不通过的单条建议会被过滤。
 */
@Component
public class RewriteSuggestionAgent {

    private static final Logger log = LoggerFactory.getLogger(RewriteSuggestionAgent.class);

    /** 单次生成建议条数上限。 */
    private static final int MAX_SUGGESTIONS = 5;

    private final DeepSeekClient client;
    private final StructuredJsonParser parser = new StructuredJsonParser();

    public RewriteSuggestionAgent(@Qualifier("reasonerModel") DeepSeekClient reasonerModel) {
        this.client = reasonerModel;
    }

    public String modelName() {
        return client.getModel();
    }

    /**
     * 生成改写建议。任何失败（未配置 Key、网络/超时、JSON 解析失败）均降级为空列表，
     * 不会影响匹配报告主链路。
     *
     * @param report    已构建完成的匹配报告（含 hits/gaps/risks/dimensions）
     * @param resumeRaw 简历原文（改写基准，original 须为原文子串）
     * @return 改写建议列表，失败或无可改写内容时为空列表
     */
    public List<MatchReport.RewriteSuggestion> generate(MatchReport report, String resumeRaw) {
        if (report == null || resumeRaw == null || resumeRaw.isBlank()) {
            return List.of();
        }
        try {
            ChatResponse response = client.chatNoSampling(systemPrompt(), userPrompt(report, resumeRaw));
            RewriteDraft draft = parser.parse(response.content(), RewriteDraft.class);
            if (draft == null || draft.rewriteSuggestions() == null || draft.rewriteSuggestions().isEmpty()) {
                return List.of();
            }
            return draft.rewriteSuggestions().stream()
                    .filter(s -> s != null && notBlank(s.original()) && notBlank(s.rewritten()))
                    .limit(MAX_SUGGESTIONS)
                    .toList();
        } catch (RuntimeException e) {
            log.warn("[rewrite] 改写建议生成失败，降级为空列表：{}", e.getMessage());
            return List.of();
        }
    }

    private String userPrompt(MatchReport report, String resumeRaw) {
        return """
                【简历原文】
                -----
                %s
                -----

                【匹配评估报告摘要】
                总分：%.1f（%s）
                命中项：%s
                缺口项：%s
                风险项：%s
                维度结论：%s

                【任务】面向目标岗位 JD 的匹配缺口，为简历中最值得改写的片段生成改写建议。
                直接输出严格 JSON，不要任何 JSON 之外的文字与代码块标记。
                """.formatted(resumeRaw, report.overallScore(), report.level(),
                summarize(report.hits(), "requirement"),
                summarize(report.gaps(), "requirement"),
                summarize(report.risks(), "risk"),
                dimensionsSummary(report.dimensions()));
    }

    private static String systemPrompt() {
        return """
                你是资深求职简历润色专家。根据匹配报告暴露的缺口、风险与维度短板，
                针对简历原文中可直接改进的片段给出具体可落地的改写建议。

                【输出 JSON（紧凑单行）】
                {"rewriteSuggestions":[{"original":"简历原文逐字片段，≤60字","rewritten":"改写后文本，≤80字，需与原文在事实上一致","reason":"改写理由，≤40字","reference":"关联的缺口/风险/维度名，可空，≤20字"}]}

                【硬性上限】最多 5 条；只保留最关键、最可能提升匹配度的改写点。

                【约束】
                1. original 必须逐字摘自「简历原文」，不得改写、概括或拼接；
                   改写点必须真实存在于简历文本中。
                2. rewritten 不得虚构简历没有的事实（如凭空增加项目经历、工作年限）；
                   只能在保留原意的前提下，让表达更贴合 JD 关键词、更具体、更结构清晰。
                3. reason 需指向匹配报告中的具体缺口/风险/维度短板。
                4. 找不到值得改写的片段时输出 {"rewriteSuggestions": []}，严禁编造。
                """;
    }

    /** 草稿容器：仅供反序列化模型输出，字段缺失时忽略。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RewriteDraft(
            @JsonProperty("rewriteSuggestions") List<MatchReport.RewriteSuggestion> rewriteSuggestions
    ) {
    }

    private static String summarize(List<?> items, String field) {
        if (items == null || items.isEmpty()) {
            return "无";
        }
        List<String> parts = items.stream().limit(5)
                .map(it -> String.valueOf(fieldValue(it, field)))
                .filter(RewriteSuggestionAgent::notBlank)
                .toList();
        return parts.isEmpty() ? "无" : String.join("；", parts);
    }

    private static String dimensionsSummary(List<MatchReport.DimensionScore> dimensions) {
        if (dimensions == null || dimensions.isEmpty()) {
            return "无";
        }
        return dimensions.stream().limit(5)
                .map(d -> d.name() + " " + d.score() + "/" + d.maxScore()
                        + (d.uncertainty() ? "（信息不足）" : ""))
                .reduce((a, b) -> a + "；" + b).orElse("无");
    }

    private static Object fieldValue(Object item, String field) {
        try {
            return item.getClass().getMethod(field).invoke(item);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
