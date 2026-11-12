package com.offerpilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.dto.JdStruct;
import com.offerpilot.dto.MatchDraft;
import com.offerpilot.dto.ResumeStruct;
import com.offerpilot.llm.ChatResponse;
import com.offerpilot.llm.DeepSeekClient;
import com.offerpilot.llm.StructuredJsonParser;
import com.offerpilot.task.ProgressListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 匹配评估 Agent（需求说明书 3.4 / 3.7）：
 * 使用推理模型 deepseek-v4-pro（不传采样参数）对 JD 与简历做五维度评估，
 * 输出维度结论、命中项、缺口项、风险项与逐字证据。
 * <p>
 * 输出契约：模型只负责「事实性内容」（维度原始分 0-10、结论、原文证据、不确定标记），
 * 权重、维度得分、总分与等级一律由服务端计算，禁止模型直接给出总分。
 * <p>
 * 校验闭环：
 * <ul>
 *     <li>C1：JSON 与结构校验失败 → 错误信息回灌重试；</li>
 *     <li>C2：标记为不确定或缺少有效原文证据的维度数 &gt; 维度总数 1/5 → 判定异常并重试；</li>
 *     <li>总调用次数上限 2 次（首次 + 重试 1 次）。</li>
 * </ul>
 */
@Component
public class MatchEvalAgent {

    /** 固定五维度名称与顺序，必须与 MatchService 的评分策略严格一致。 */
    public static final List<String> DIMENSIONS = List.of(
            "硬性技能匹配",
            "经验年限与职级",
            "项目与成果相关度",
            "教育与证书",
            "加分项与潜力");

    private static final int MAX_ATTEMPTS = 2;
    /** 不确定维度占比阈值分母：超过维度总数的 1/5 即判定异常（约束 C2）。 */
    private static final int UNCERTAINTY_RATIO_DENOMINATOR = 5;

    private final DeepSeekClient client;
    private final StructuredJsonParser parser = new StructuredJsonParser();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 评估阶段是否改为流式消费（默认 false：Spring 环境由配置注入；
     * 单测手动 new 实例时保持非流式，既有调用契约不变）。
     */
    @Value("${offerpilot.latency.stream-eval:false}")
    private boolean streamEval;

    public MatchEvalAgent(@Qualifier("reasonerModel") DeepSeekClient reasonerModel) {
        this.client = reasonerModel;
    }

    public String modelName() {
        return client.getModel();
    }

    /**
     * 执行匹配评估。
     *
     * @param jdRaw         JD 原文（证据校验基准）
     * @param resumeRaw     简历原文（证据校验基准）
     * @param jdParsed      JD 解析结果（辅助参考）
     * @param resumeParsed  简历解析结果（辅助参考）
     * @return 评估草稿与执行元数据（firstError 承载重试原因，未重试时为 null）
     */
    public AgentResult<MatchDraft> evaluate(String jdRaw, String resumeRaw,
                                           JdStruct jdParsed, ResumeStruct resumeParsed) {
        return evaluate(jdRaw, resumeRaw, jdParsed, resumeParsed, ProgressListener.NOOP);
    }

    /**
     * 执行匹配评估（带进度回调，seed 为 null，兼容既有调用）。
     */
    public AgentResult<MatchDraft> evaluate(String jdRaw, String resumeRaw,
                                           JdStruct jdParsed, ResumeStruct resumeParsed,
                                           ProgressListener listener) {
        return evaluate(jdRaw, resumeRaw, jdParsed, resumeParsed, listener, null);
    }

    /**
     * 执行匹配评估（带进度回调与固定 seed）。
     * <p>
     * 流式模式下，等待期间按增量回调「已生成字符数」，使前端时间线保持可见进展；
     * 汇总后的 content 与一次性调用完全一致，下游解析、校验与计分逻辑不变。
     *
     * @param listener 进度监听器（可为 {@link ProgressListener#NOOP}）
     * @param seed     固定随机种子（可为 null，null 时与既有行为一致）
     */
    public AgentResult<MatchDraft> evaluate(String jdRaw, String resumeRaw,
                                           JdStruct jdParsed, ResumeStruct resumeParsed,
                                           ProgressListener listener, Integer seed) {
        long start = System.currentTimeMillis();
        String basePrompt = userPrompt(jdRaw, resumeRaw, jdParsed, resumeParsed);

        int attempts = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        String firstError = null;
        String retryReason = null;

        for (int round = 1; round <= MAX_ATTEMPTS; round++) {
            String prompt = retryReason == null ? basePrompt : basePrompt + retryFeedback(retryReason);
            long roundStart = System.currentTimeMillis();
            ChatResponse response;
            if (streamEval) {
                StringBuilder inflight = new StringBuilder();
                response = client.chatStreamNoSampling(systemPrompt(), prompt, delta -> {
                    inflight.append(delta);
                    listener.onAgentProgress("MATCH_EVAL", System.currentTimeMillis() - roundStart,
                            inflight.length());
                });
            } else {
                response = client.chatNoSampling(systemPrompt(), prompt, seed);
            }
            attempts++;
            promptTokens += tokens(response, TokenKind.PROMPT);
            completionTokens += tokens(response, TokenKind.COMPLETION);
            totalTokens += tokens(response, TokenKind.TOTAL);

            try {
                MatchDraft draft = parser.parse(response.content(), MatchDraft.class);

                List<String> problems = shapeProblems(draft);
                if (!problems.isEmpty()) {
                    retryReason = "输出结构校验未通过：" + String.join("；", problems);
                    if (firstError == null) {
                        firstError = retryReason;
                    }
                    continue; // 错误信息回灌重试（约束 C1）
                }

                List<String> uncertain = uncertainDimensions(draft, jdRaw, resumeRaw);
                if (uncertain.size() * UNCERTAINTY_RATIO_DENOMINATOR > DIMENSIONS.size()) {
                    retryReason = "标记为不确定或缺少有效原文证据的维度达 " + uncertain.size() + "/"
                            + DIMENSIONS.size() + "（" + String.join("、", uncertain)
                            + "），超过维度总数的 1/5，判定为异常输出。请复核：原文确有依据的维度须补全"
                            + "逐字摘抄的证据（quote 必须是原文子串，不得改写或拼接）；原文确实没有依据的，"
                            + "保持 evidence 为空数组且 uncertainty=true，严禁为凑证据而编造或改写原文。"
                            + "被拒示例：某组「经验年限与职级」的 evidence.quote 写成「近 1 年开发经验」，"
                            + "而原文实为「2023.07-2024.06 任 Java 开发实习生」，概括表述不是原文子串，被判无效；"
                            + "正确写法是摘抄「2023.07-2024.06」这类含起止时间的原文片段。";
                    if (firstError == null) {
                        firstError = retryReason;
                    }
                    continue; // 约束 C2：不确定维度占比超阈值触发重试
                }

                return new AgentResult<>(draft, promptTokens, completionTokens, totalTokens,
                        System.currentTimeMillis() - start, attempts, attempts > 1, firstError);
            } catch (RuntimeException e) {
                firstError = e.getMessage();
                retryReason = "JSON 解析失败：" + e.getMessage()
                        + "。请只输出 JSON 本身，不要任何解释文字与 Markdown 代码块标记。";
            }
        }

        // 重试后仍不达标：交由接口层统一兜底（不返回残缺数据）
        throw new IllegalStateException("匹配评估失败（已重试 1 次）：" + firstError);
    }

    /** 结构校验：dimensions 必须恰好覆盖 5 个维度、名称合法、无重复且带 rawScore。 */
    private static List<String> shapeProblems(MatchDraft draft) {
        List<String> problems = new ArrayList<>();
        if (draft.dimensions() == null || draft.dimensions().isEmpty()) {
            problems.add("dimensions 为空，必须包含全部 5 个维度");
            return problems;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (MatchDraft.DraftDimension d : draft.dimensions()) {
            if (d == null || d.name() == null || d.name().isBlank()) {
                problems.add("存在缺失 name 的维度");
                continue;
            }
            String name = d.name().trim();
            if (!DIMENSIONS.contains(name)) {
                problems.add("维度名不合法：" + name);
            }
            if (!seen.add(name)) {
                problems.add("维度重复出现：" + name);
            }
            if (d.rawScore() == null) {
                problems.add("维度缺少 rawScore：" + name);
            }
        }
        for (String expected : DIMENSIONS) {
            if (!seen.contains(expected)) {
                problems.add("缺少维度：" + expected);
            }
        }
        return problems;
    }

    /** 判定「不确定维度」集合：模型标记 uncertainty=true、缺该维度、或全部证据无法在原文中定位。 */
    private static List<String> uncertainDimensions(MatchDraft draft, String jdRaw, String resumeRaw) {
        Map<String, MatchDraft.DraftDimension> byName = indexByName(draft);
        List<String> uncertain = new ArrayList<>();
        for (String name : DIMENSIONS) {
            MatchDraft.DraftDimension d = byName.get(name);
            if (d == null) {
                uncertain.add(name);
                continue;
            }
            boolean flagged = Boolean.TRUE.equals(d.uncertainty());
            boolean hasValidEvidence = false;
            if (d.evidence() != null) {
                for (MatchDraft.DraftEvidence e : d.evidence()) {
                    if (e != null && EvidenceValidator.supports(e.quote(), e.source(), jdRaw, resumeRaw)) {
                        hasValidEvidence = true;
                        break;
                    }
                }
            }
            if (flagged || !hasValidEvidence) {
                uncertain.add(name);
            }
        }
        return uncertain;
    }

    public static Map<String, MatchDraft.DraftDimension> indexByName(MatchDraft draft) {
        Map<String, MatchDraft.DraftDimension> byName = new LinkedHashMap<>();
        if (draft != null && draft.dimensions() != null) {
            for (MatchDraft.DraftDimension d : draft.dimensions()) {
                if (d == null || d.name() == null || d.name().isBlank()) {
                    continue;
                }
                byName.putIfAbsent(d.name().trim(), d);
            }
        }
        return byName;
    }

    private static String retryFeedback(String reason) {
        return "\n\n【上一次输出未通过校验，请修正后重新输出完整 JSON】\n" + reason;
    }

    private String userPrompt(String jdRaw, String resumeRaw, JdStruct jdParsed, ResumeStruct resumeParsed) {
        return """
                【JD 原文】
                -----
                %s
                -----

                【简历原文】
                -----
                %s
                -----

                【JD 结构化解析结果（仅供快速定位，证据摘抄一律以原文为准）】
                %s

                【简历结构化解析结果（仅供快速定位，证据摘抄一律以原文为准）】
                %s

                【任务】按 system 的 JSON Schema 直接输出严格 JSON：5 个维度各 1 次，
                evidence.quote 必须是上文原文中的逐字片段，禁止任何 JSON 之外的文字与代码块标记。
                """.formatted(jdRaw, resumeRaw, toJson(jdParsed), toJson(resumeParsed));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String systemPrompt() {
        return """
                你是资深技术招聘评估专家，对 JD 与简历做匹配评估。直接比对原文给出结论，不要复述原文、不要解释评分理由。

                【维度（name 必须逐字照抄，各出现 1 次；权重由系统计算，勿输出）】
                1 硬性技能匹配：JD 必须技能与简历技能/技术栈的交集
                2 经验年限与职级：JD 年限职级要求与简历经历的匹配
                3 项目与成果相关度：岗位职责与简历项目的对应
                4 教育与证书：JD 学历专业要求与简历学历证书的匹配
                5 加分项与潜力：JD 加分项命中情况与成长潜力

                【输出 JSON（紧凑单行，无缩进与多余空格）】
                {"dimensions":[{"name":"维度名","rawScore":0-10,"conclusion":"≤40字","evidence":[{"source":"jd 或 resume","quote":"原文逐字片段≤25字"}],"uncertainty":false}],"hits":[{"requirement":"≤20字","resumeQuote":"原文逐字片段","comment":"≤20字"}],"gaps":[{"requirement":"≤20字","gapType":"技能缺失/经验不足/学历不符/其他","severity":"高/中/低","suggestion":"≤20字"}],"risks":[{"risk":"≤20字","reason":"≤20字","evidenceRef":null}]}

                【硬性上限（超出视为输出无效）】每维度 evidence 最多 2 条；hits ≤ 3；gaps ≤ 3；risks ≤ 2；只保留最关键者。

                【证据】quote 必须逐字摘自原文，不得改写、概括、拼接、补充或翻译；原文无依据的维度 evidence 为空数组且 uncertainty=true，conclusion 写「原文信息不足」；不得推断原文没有的信息。
                「经验年限与职级」维度必须摘抄含起止时间的原文连续片段（如「2023.07-2024.06」「2022年3月至今」），不得改写为「近 1 年」「约 2 年经验」等概括表述；多段经历择其一段原文片段即可，字数紧张时优先保留时间区间本身。

                【禁止】输出总分、等级或加权计算；输出 JSON 之外的任何文字、注释与 Markdown 代码块标记；遗漏或重复维度。
                """;
    }

    private static int tokens(ChatResponse response, TokenKind kind) {
        if (response == null || response.usage() == null) {
            return 0;
        }
        return switch (kind) {
            case PROMPT -> response.usage().prompt_tokens();
            case COMPLETION -> response.usage().completion_tokens();
            case TOTAL -> response.usage().total_tokens();
        };
    }

    private enum TokenKind {
        PROMPT, COMPLETION, TOTAL
    }
}
