package com.offerpilot.service;

import com.offerpilot.agent.AgentResult;
import com.offerpilot.agent.EvidenceValidator;
import com.offerpilot.agent.JdParseAgent;
import com.offerpilot.agent.MatchEvalAgent;
import com.offerpilot.agent.ResumeParseAgent;
import com.offerpilot.agent.RewriteSuggestionAgent;
import com.offerpilot.dto.JdStruct;
import com.offerpilot.dto.MatchDraft;
import com.offerpilot.dto.MatchReport;
import com.offerpilot.dto.MatchResponse;
import com.offerpilot.dto.ResumeStruct;
import com.offerpilot.task.ProgressListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 匹配评估编排服务（需求说明书 3.4 / 3.7 / 3.8）：
 * <ol>
 *     <li>解析阶段：JD 解析与简历解析在虚拟线程上并行执行（CompletableFuture.allOf）；</li>
 *     <li>评估阶段：依赖两者的结构化结果调用匹配评估 Agent（deepseek-v4-pro）；</li>
 *     <li>校验阶段：逐条校验证据是否为原文子串（约束 C2），
 *         证据全部无效的维度剔除出总分并对剩余权重重新归一化；</li>
 *     <li>计分阶段：总分 = Σ有效维度加权得分 / Σ有效维度权重 × 100，等级按阈值映射。</li>
 * </ol>
 */
@Service
public class MatchService {

    /** 维度原始分满分。 */
    private static final double RAW_MAX = 10.0;

    /** 硬性技能锚定维度名（校准锚点）。 */
    private static final String HARD_DIMENSION = "硬性技能匹配";

    /** 固定五维度评分策略（权重合计 100），列表顺序即报告中的维度顺序。 */
    private static final List<DimensionPolicy> POLICY = List.of(
            new DimensionPolicy(HARD_DIMENSION, 30.0),
            new DimensionPolicy("经验年限与职级", 20.0),
            new DimensionPolicy("项目与成果相关度", 25.0),
            new DimensionPolicy("教育与证书", 10.0),
            new DimensionPolicy("加分项与潜力", 15.0));

    // ---- 硬性技能锚定校准配置（application.yml 可配，字段默认值与 yml 一致，保证手动实例化时行为不变）----

    /** 校准开关，默认启用。 */
    @Value("${offerpilot.match-calibration.enabled:true}")
    private boolean calibrationEnabled = true;

    /** 五维加权原始分权重 α。 */
    @Value("${offerpilot.match-calibration.alpha:0.7}")
    private double calibrationAlpha = 0.7;

    /** 硬性技能锚定权重 β（作用对象为 硬性技能原始分 × 10）。 */
    @Value("${offerpilot.match-calibration.hard-beta:0.3}")
    private double calibrationHardBeta = 0.3;

    /** 校准总分档位阈值：高度匹配。 */
    @Value("${offerpilot.match-calibration.threshold.high:81}")
    private double calibrationHigh = 81;

    /** 校准总分档位阈值：较匹配下限。 */
    @Value("${offerpilot.match-calibration.threshold.mid:47}")
    private double calibrationMid = 47;

    /** 校准总分档位阈值：一般下限。 */
    @Value("${offerpilot.match-calibration.threshold.low:20}")
    private double calibrationLow = 20;

    /** 回退阈值（校准未生效时使用）：高度匹配。 */
    @Value("${offerpilot.match-calibration.fallback-threshold.high:80}")
    private double fallbackHigh = 80;

    /** 回退阈值（校准未生效时使用）：较匹配下限。 */
    @Value("${offerpilot.match-calibration.fallback-threshold.mid:60}")
    private double fallbackMid = 60;

    /** 回退阈值（校准未生效时使用）：一般下限。 */
    @Value("${offerpilot.match-calibration.fallback-threshold.low:40}")
    private double fallbackLow = 40;

    private final JdParseAgent jdParseAgent;
    private final ResumeParseAgent resumeParseAgent;
    private final MatchEvalAgent matchEvalAgent;
    private final RewriteSuggestionAgent rewriteSuggestionAgent;

    public MatchService(JdParseAgent jdParseAgent, ResumeParseAgent resumeParseAgent,
                        MatchEvalAgent matchEvalAgent, RewriteSuggestionAgent rewriteSuggestionAgent) {
        this.jdParseAgent = jdParseAgent;
        this.resumeParseAgent = resumeParseAgent;
        this.matchEvalAgent = matchEvalAgent;
        this.rewriteSuggestionAgent = rewriteSuggestionAgent;
    }

    /** 端到端匹配评估（同步接口 /api/match）：JD 原文 + 简历原文 → 匹配报告 + 各阶段元数据。 */
    public MatchResponse match(String jdRaw, String resumeRaw) {
        return match(jdRaw, resumeRaw, ProgressListener.NOOP, null);
    }

    /** 端到端匹配评估（同步接口 /api/match，带固定随机种子，评测重跑用）。 */
    public MatchResponse match(String jdRaw, String resumeRaw, Integer seed) {
        return match(jdRaw, resumeRaw, ProgressListener.NOOP, seed);
    }

    /**
     * 带进度回调的匹配评估（异步任务接口 /api/v1/tasks 使用，需求说明书 6.4，seed 为 null）。
     */
    public MatchResponse match(String jdRaw, String resumeRaw, ProgressListener listener) {
        return match(jdRaw, resumeRaw, listener, null);
    }

    /**
     * 带进度回调与固定 seed 的匹配评估。
     * <p>
     * 回调事件顺序固定为：JD_PARSE.started → RESUME_PARSE.started（两者并行执行，完成顺序不定）
     * → MATCH_EVAL.started/finished，保证前端时间线与 SSE 事件的 seq 严格单调递增。
     *
     * @param listener 进度监听器
     * @param seed     固定随机种子（可为 null，null 时与既有行为一致）
     */
    public MatchResponse match(String jdRaw, String resumeRaw, ProgressListener listener, Integer seed) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            listener.onAgentStarted("JD_PARSE", jdParseAgent.modelName());
            listener.onAgentStarted("RESUME_PARSE", resumeParseAgent.modelName());

            CompletableFuture<AgentResult<JdStruct>> jdFuture = CompletableFuture.supplyAsync(
                    () -> runStage("JD_PARSE", () -> jdParseAgent.parseDetailed(jdRaw), listener), executor);
            CompletableFuture<AgentResult<ResumeStruct>> resumeFuture = CompletableFuture.supplyAsync(
                    () -> runStage("RESUME_PARSE", () -> resumeParseAgent.parseDetailed(resumeRaw), listener),
                    executor);
            CompletableFuture.allOf(jdFuture, resumeFuture).join();

            AgentResult<JdStruct> jdResult = jdFuture.join();
            AgentResult<ResumeStruct> resumeResult = resumeFuture.join();

            listener.onAgentStarted("MATCH_EVAL", matchEvalAgent.modelName());
            AgentResult<MatchDraft> evalResult = runStage("MATCH_EVAL",
                    () -> matchEvalAgent.evaluate(jdRaw, resumeRaw, jdResult.data(), resumeResult.data(),
                            listener, seed),
                    listener);

            Built built = build(evalResult, jdRaw, resumeRaw);

            // 改写建议：基于报告与简历原文生成，失败时降级为空列表，绝不阻塞主流程；
            // 无论是否有建议都显式赋值（空列表而非 null），保证新旧报告 JSON 语义统一。
            MatchReport report = withRewriteSuggestions(built.report(),
                    rewriteSuggestionAgent.generate(built.report(), resumeRaw));

            MatchResponse.MatchMeta meta = new MatchResponse.MatchMeta(
                    stage("jd-parse", jdParseAgent.modelName(), jdResult),
                    stage("resume-parse", resumeParseAgent.modelName(), resumeResult),
                    stage("match-eval", matchEvalAgent.modelName(), evalResult),
                    matchEvalAgent.modelName(),
                    POLICY.size(),
                    built.uncertaintyCount(),
                    built.invalidDimensions(),
                    built.renormalized(),
                    evalResult.retried() ? evalResult.firstError() : null);

            return new MatchResponse(report, meta);
        }
    }

    /**
     * 构建最终报告：证据子串校验 → 无效维度剔除与权重重归一化 → 加权计分 → 等级映射。
     */
    private Built build(AgentResult<MatchDraft> evalResult, String jdRaw, String resumeRaw) {
        MatchDraft draft = evalResult.data();
        Map<String, MatchDraft.DraftDimension> byName = MatchEvalAgent.indexByName(draft);

        List<MatchReport.DimensionScore> dimensions = new ArrayList<>();
        List<MatchReport.MatchEvidence> evidenceIndex = new ArrayList<>();
        List<String> uncertaintyFlags = new ArrayList<>();
        List<String> invalidDimensions = new ArrayList<>();
        int evidenceSeq = 0;
        double validScoreSum = 0;
        double validWeightSum = 0;
        double totalWeight = 0;
        double hardDimensionRaw = -1; // 硬性技能匹配维度原始分（0–10；-1 表示模型未返回该维度）

        for (DimensionPolicy policy : POLICY) {
            totalWeight += policy.weight();
            MatchDraft.DraftDimension draftDimension = byName.get(policy.name());

            List<MatchReport.MatchEvidence> validEvidence = new ArrayList<>();
            boolean uncertainty;
            String conclusion;
            double rawScore;

            if (draftDimension == null) {
                uncertainty = true;
                conclusion = "模型未返回该维度结论，无法评分";
                rawScore = 0;
            } else {
                rawScore = clamp(draftDimension.rawScore() == null ? 0 : draftDimension.rawScore());
                uncertainty = Boolean.TRUE.equals(draftDimension.uncertainty());
                conclusion = draftDimension.conclusion() == null ? "" : draftDimension.conclusion();
                if (draftDimension.evidence() != null) {
                    for (MatchDraft.DraftEvidence e : draftDimension.evidence()) {
                        if (e == null || !EvidenceValidator.supports(e.quote(), e.source(), jdRaw, resumeRaw)) {
                            continue; // 证据无法在原文中定位 → 判为无效证据并丢弃
                        }
                        evidenceSeq++;
                        MatchReport.MatchEvidence item = new MatchReport.MatchEvidence(
                                "E" + evidenceSeq, sourceLabel(e.source()), e.quote());
                        validEvidence.add(item);
                        evidenceIndex.add(item);
                    }
                }
            }

            if (HARD_DIMENSION.equals(policy.name()) && draftDimension != null) {
                hardDimensionRaw = rawScore;
            }

            double score = round1(rawScore / RAW_MAX * policy.weight());

            if (validEvidence.isEmpty()) {
                // 约束 C2：该维度全部结论无有效证据 → 剔除出总分计算，标记信息不足
                uncertainty = true;
                invalidDimensions.add(policy.name());
            }
            if (uncertainty) {
                uncertaintyFlags.add(policy.name());
            }

            dimensions.add(new MatchReport.DimensionScore(policy.name(), policy.weight(), score,
                    policy.weight(), conclusion, validEvidence, uncertainty));

            if (!validEvidence.isEmpty()) {
                validScoreSum += score;
                validWeightSum += policy.weight();
            }
        }

        // 权重重归一化：总分只由有效维度决定
        boolean renormalized = validWeightSum < totalWeight - 0.001;
        double weightedScore = validWeightSum > 0
                ? round1(validScoreSum / validWeightSum * 100.0)
                : 0.0;

        // 硬性技能锚定校准：校准总分 = α × 五维加权原始分 + β × 硬性技能原始分 × 10；
        // 硬性技能维度缺失或被剔除（无有效证据）时校准不生效，退回五维加权原始分与原阈值。
        boolean hardValid = hardDimensionRaw >= 0 && !invalidDimensions.contains(HARD_DIMENSION);
        boolean calibrated = calibrationEnabled && hardValid && validWeightSum > 0;
        double overallScore = calibrated
                ? round1(calibrationAlpha * weightedScore + calibrationHardBeta * hardDimensionRaw * RAW_MAX)
                : weightedScore;
        String levelLabel = calibrated
                ? level(overallScore, calibrationHigh, calibrationMid, calibrationLow)
                : level(overallScore, fallbackHigh, fallbackMid, fallbackLow);

        List<MatchReport.HitItem> hits = new ArrayList<>();
        int droppedHits = 0;
        if (draft != null && draft.hits() != null) {
            for (MatchDraft.DraftHit h : draft.hits()) {
                if (h == null || isBlank(h.requirement())) {
                    continue;
                }
                if (EvidenceValidator.supports(h.resumeQuote(), "resume", jdRaw, resumeRaw)) {
                    hits.add(new MatchReport.HitItem(h.requirement(), h.resumeQuote(), h.comment()));
                } else {
                    droppedHits++; // 命中项无有效简历引用 → 丢弃
                }
            }
        }

        List<MatchReport.GapItem> gaps = new ArrayList<>();
        if (draft != null && draft.gaps() != null) {
            for (MatchDraft.DraftGap g : draft.gaps()) {
                if (g == null || isBlank(g.requirement())) {
                    continue;
                }
                gaps.add(new MatchReport.GapItem(g.requirement(), g.gapType(), g.severity(), g.suggestion()));
            }
        }

        Set<String> evidenceIds = new LinkedHashSet<>();
        for (MatchReport.MatchEvidence e : evidenceIndex) {
            evidenceIds.add(e.evidenceId());
        }
        List<MatchReport.RiskItem> risks = new ArrayList<>();
        int invalidRefs = 0;
        if (draft != null && draft.risks() != null) {
            for (MatchDraft.DraftRisk r : draft.risks()) {
                if (r == null || isBlank(r.risk())) {
                    continue;
                }
                String ref = r.evidenceRef();
                if (!isBlank(ref) && !evidenceIds.contains(ref.trim())) {
                    ref = null; // 引用不存在的证据编号 → 置空
                    invalidRefs++;
                }
                risks.add(new MatchReport.RiskItem(r.risk(), r.reason(), ref));
            }
        }

        List<String> notes = new ArrayList<>();
        if (calibrated) {
            notes.add(String.format(Locale.ROOT,
                    "总分采用「硬性技能锚定校准」：校准总分 = %.2f × 五维加权原始分 + %.2f × 硬性技能匹配原始分 × 10；"
                            + "本次五维加权原始分 %.1f 分，硬性技能匹配原始分 %.1f/10，校准后总分 %.1f 分。"
                            + "档位阈值：高度匹配 ≥ %.1f；%.1f–%.1f 较匹配；%.1f–%.1f 一般；< %.1f 不匹配。",
                    calibrationAlpha, calibrationHardBeta, weightedScore, hardDimensionRaw, overallScore,
                    calibrationHigh, calibrationMid, calibrationHigh - 0.1,
                    calibrationLow, calibrationMid - 0.1, calibrationLow));
        } else if (calibrationEnabled && !hardValid) {
            notes.add(String.format(Locale.ROOT,
                    "「硬性技能匹配」维度缺失或已被剔除，硬性技能锚定校准未生效，总分退回五维加权原始分 %.1f 分"
                            + "（回退阈值：高度匹配 ≥ %.1f；%.1f–%.1f 较匹配；%.1f–%.1f 一般；< %.1f 不匹配）。",
                    weightedScore, fallbackHigh, fallbackMid, fallbackHigh - 0.1,
                    fallbackLow, fallbackMid - 0.1, fallbackLow));
        }
        if (!invalidDimensions.isEmpty()) {
            notes.add("以下维度因全部结论缺少可定位的原文证据，已从总分计算中剔除并按剩余权重归一化："
                    + String.join("、", invalidDimensions) + "。该维度信息不足，评分仅供参考。");
        }
        if (renormalized) {
            notes.add("总分基于 " + (POLICY.size() - invalidDimensions.size()) + "/" + POLICY.size()
                    + " 个有效维度计算，有效维度权重合计 " + trim(validWeightSum)
                    + " 分（满权重 " + trim(totalWeight) + " 分）。");
        }
        if (droppedHits > 0) {
            notes.add("命中项中有 " + droppedHits + " 条因 resume_quote 无法在简历原文中定位被丢弃。");
        }
        if (invalidRefs > 0) {
            notes.add("风险项中有 " + invalidRefs + " 条证据引用无效（编号不存在），已置空。");
        }
        if (evalResult.retried()) {
            notes.add("评估过程触发 1 次重试（共 " + evalResult.attempts() + " 次模型调用），原因："
                    + evalResult.firstError());
        }

        MatchReport report = new MatchReport(overallScore, levelLabel, dimensions, hits, gaps,
                risks, evidenceIndex, uncertaintyFlags, notes, null);
        return new Built(report, uncertaintyFlags.size(), invalidDimensions, renormalized);
    }

    /** 以新副本替换报告中的改写建议字段（record 不可变）。 */
    private static MatchReport withRewriteSuggestions(MatchReport report,
                                                       List<MatchReport.RewriteSuggestion> suggestions) {
        return new MatchReport(report.overallScore(), report.level(), report.dimensions(), report.hits(),
                report.gaps(), report.risks(), report.evidenceIndex(), report.uncertaintyFlags(),
                report.notes(), suggestions == null ? List.of() : suggestions);
    }

    /** 执行单个 Agent 阶段并回调进度：成功回 finished，异常回 failed 后原样抛出。 */
    private static <T> AgentResult<T> runStage(String agentName, Supplier<AgentResult<T>> action,
                                               ProgressListener listener) {
        try {
            AgentResult<T> result = action.get();
            listener.onAgentFinished(agentName, result.elapsedMs(), result.retried(), result.attempts(),
                    result.totalTokens());
            return result;
        } catch (RuntimeException e) {
            listener.onAgentFailed(agentName, "40501", 1);
            throw e;
        }
    }

    private static MatchResponse.StageMeta stage(String agent, String model, AgentResult<?> result) {
        return new MatchResponse.StageMeta(agent, model, result.elapsedMs(), result.promptTokens(),
                result.completionTokens(), result.totalTokens(), result.attempts(), result.retried());
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(RAW_MAX, value));
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String sourceLabel(String source) {
        if (source == null) {
            return "unknown";
        }
        String label = source.trim().toLowerCase(Locale.ROOT);
        if (label.contains("jd") || label.contains("岗位")) {
            return "jd";
        }
        if (label.contains("resume") || label.contains("简历") || label.contains("cv")) {
            return "resume";
        }
        return source.trim();
    }

    /**
     * 等级映射（阈值由配置注入）：
     * 高度匹配 ≥ high；mid ~ high-0.1 较匹配；low ~ mid-0.1 一般；&lt; low 不匹配。
     * 校准生效时使用校准阈值（默认 81/47/20），校准未生效时使用回退阈值（默认 80/60/40）。
     */
    private static String level(double score, double high, double mid, double low) {
        if (score >= high) {
            return "高度匹配";
        }
        if (score >= mid) {
            return "较匹配";
        }
        if (score >= low) {
            return "一般";
        }
        return "不匹配";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** 维度评分策略：权重之和固定为 100。 */
    private record DimensionPolicy(String name, double weight) {
    }

    /** 报告构建中间产物：报告本体 + 供 meta 复用的校验统计。 */
    private record Built(
            MatchReport report,
            int uncertaintyCount,
            List<String> invalidDimensions,
            boolean renormalized
    ) {
    }
}
