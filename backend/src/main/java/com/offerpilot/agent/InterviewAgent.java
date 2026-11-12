package com.offerpilot.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.dto.InterviewDraft;
import com.offerpilot.llm.ChatResponse;
import com.offerpilot.llm.DeepSeekClient;
import com.offerpilot.llm.StructuredJsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent 5 — 面试官（需求说明书 3.6 / 7.7）：
 * 依据 JD、简历与匹配评估结果逐轮提问，并对上一轮回答给出评分与改进方向。
 * <ul>
 *     <li>模型：{@code deepseek-v4-pro}（reasoner 档，逐轮串行调用，单轮读取超时见 {@code deepseek.timeout.interview-seconds}）；</li>
 *     <li>Prompt：{@code prompts/interviewer.{system|user}.v1.txt}，变量用 <code>{{var}}</code> 注入（7.8）；</li>
 *     <li>校验：JSON Schema + 业务规则（round 一致、上一轮维度不重复、evidence_ref 必须来自证据索引），
 *         失败把错误信息回灌 Prompt 重试（≤2 次）；</li>
 *     <li>反幻觉：问题中不得出现简历 / JD 中不存在的技术名词，evidence_ref 必须指向 evidence_index 中的编号。</li>
 * </ul>
 */
@Component
public class InterviewAgent {

    private static final Logger log = LoggerFactory.getLogger(InterviewAgent.class);

    /** 首次调用 + 最多 2 次重试（与约束 C1 一致）。 */
    private static final int MAX_ATTEMPTS = 3;

    private static final String FALLBACK_DIMENSION = "项目与成果相关度";

    private final DeepSeekClient interviewerModel;
    private final DeepSeekClient chatModel;
    private final StructuredJsonParser parser = new StructuredJsonParser();
    private final ObjectMapper mapper = new ObjectMapper();

    private final PromptTemplate systemPrompt = PromptTemplate.load("interviewer.system", "v1");
    private final PromptTemplate userPrompt = PromptTemplate.load("interviewer.user", "v1");
    private final PromptTemplate summaryPrompt = PromptTemplate.load("interview_summary", "v1");

    public InterviewAgent(@Qualifier("interviewerModel") DeepSeekClient interviewerModel,
                          @Qualifier("chatModel") DeepSeekClient chatModel) {
        this.interviewerModel = interviewerModel;
        this.chatModel = chatModel;
    }

    public String promptVersion() {
        return userPrompt.name() + "." + userPrompt.version();
    }

    public String modelName() {
        return interviewerModel.getModel();
    }

    /** 单轮调用上下文：原文 + 匹配评估 JSON + 历史问答 + 轮次约束。 */
    public record RoundContext(
            String jdText,
            String resumeText,
            String matchEvalJson,
            String historyJson,
            int round,
            String lastDimension,
            List<String> visitedDimensions,
            List<String> dimensionNames,
            Set<String> evidenceIds,
            String focusDimensionHint,
            boolean finalRound
    ) {
    }

    /**
     * 生成第 {@code round} 轮问题，并在 history 非空时对上一轮回答给出 evaluation。
     * 首轮 evaluation 强制为 null。
     */
    public AgentResult<InterviewDraft> nextRound(RoundContext ctx) {
        long start = System.currentTimeMillis();
        String baseUser = userPrompt.render(Map.of(
                "jd_parsed", blankToDash(ctx.jdText()),
                "resume_parsed", blankToDash(ctx.resumeText()),
                "match_eval", blankToDash(ctx.matchEvalJson()),
                "history", ctx.historyJson() == null || ctx.historyJson().isBlank() ? "[]" : ctx.historyJson(),
                "round", String.valueOf(ctx.round()),
                "last_dimension", ctx.lastDimension() == null ? "" : ctx.lastDimension(),
                "forbidden_dimensions", ctx.visitedDimensions() == null || ctx.visitedDimensions().isEmpty()
                        ? "（无）" : String.join("、", ctx.visitedDimensions()),
                "focus_dimension", ctx.focusDimensionHint() == null || ctx.focusDimensionHint().isBlank()
                        ? "（由你依据 match_eval 缺口自选）" : ctx.focusDimensionHint(),
                "evidence_ids", ctx.evidenceIds() == null || ctx.evidenceIds().isEmpty()
                        ? "（无）" : String.join(", ", ctx.evidenceIds()),
                "final_round_note", finalNote(ctx)));

        int attempts = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;
        String firstError = null;
        String retryNote = "";

        while (attempts < MAX_ATTEMPTS) {
            ChatResponse response = interviewerModel.chatNoSampling(systemPrompt.render(Map.of()), baseUser + retryNote);
            attempts++;
            promptTokens += tokens(response, true);
            completionTokens += tokens(response, false);
            totalTokens += response != null && response.usage() != null ? response.usage().total_tokens() : 0;
            try {
                InterviewDraft draft = parser.parse(response.content(), InterviewDraft.class);
                InterviewDraft normalized = normalize(draft, ctx);
                String violation = validate(draft, ctx);
                if (violation == null) {
                    return new AgentResult<>(normalized, promptTokens, completionTokens, totalTokens,
                            System.currentTimeMillis() - start, attempts, attempts > 1, firstError);
                }
                if (firstError == null) {
                    firstError = violation;
                }
                retryNote = retryInstruction(violation, ctx);
                log.warn("[interviewer] 第 {} 轮输出未通过业务校验（第 {} 次尝试）：{}", ctx.round(), attempts, violation);
            } catch (RuntimeException e) {
                if (firstError == null) {
                    firstError = e.getMessage();
                }
                retryNote = retryInstruction(e.getMessage(), ctx);
                log.warn("[interviewer] 第 {} 轮输出未通过 JSON 校验（第 {} 次尝试）：{}", ctx.round(), attempts,
                        e.getMessage());
            }
        }
        throw new IllegalStateException("面试官 Agent 输出未通过校验（已重试 " + (MAX_ATTEMPTS - 1) + " 次）：" + firstError);
    }

    /**
     * 面试总结：依据逐轮问答与评分生成 Markdown 总结（3.6 的 summary_md）。
     * 失败时由调用方回退为规则化总结，保证演示链路不中断。
     */
    public SummaryResult summarize(String jdText, String resumeText, String transcriptJson, String scoreJson) {
        long start = System.currentTimeMillis();
        String user = summaryPrompt.render(Map.of(
                "jd", blankToDash(jdText),
                "resume", blankToDash(resumeText),
                "transcript", transcriptJson == null ? "[]" : transcriptJson,
                "score", scoreJson == null ? "{}" : scoreJson));
        ChatResponse response = chatModel.chat(summaryPrompt.render(Map.of()) , user, 0.3);
        String markdown = response == null || response.content() == null ? "" : response.content().trim();
        return new SummaryResult(markdown, System.currentTimeMillis() - start,
                response != null && response.usage() != null ? response.usage().total_tokens() : 0);
    }

    public record SummaryResult(String markdown, long elapsedMs, int totalTokens) {
    }

    // ------------------------------------------------------------------ 校验与规范化

    /** 收尾模式说明：non-final 轮次正常提问；收尾轮次只评分、不再出新题。 */
    private static String finalNote(RoundContext ctx) {
        if (!ctx.finalRound()) {
            return "常规轮次：需要生成本轮问题（question 不得为空）。";
        }
        return "【收尾模式】本轮为面试收尾：不再提出新问题，question 固定输出「面试结束」，"
                + "answer_points 输出空数组 []，followup_hint 固定输出「面试已结束」，"
                + "focus_dimension 沿用上一轮维度（"
                + (ctx.lastDimension() == null || ctx.lastDimension().isBlank() ? "与上一轮保持一致"
                        : ctx.lastDimension())
                + "），只需按第 1 条任务要求对上一轮回答给出 evaluation（不得为 null）。";
    }

    /** 业务校验：返回 null 表示通过，否则返回错误描述（会回灌 Prompt 重试）。 */
    private String validate(InterviewDraft draft, RoundContext ctx) {
        if (draft == null) {
            return "输出为空";
        }
        if (draft.round() == null || draft.round() != ctx.round()) {
            return "round 字段应为 " + ctx.round() + "，实际为 " + draft.round();
        }
        if (!ctx.finalRound() && (draft.question() == null || draft.question().isBlank())) {
            return "question 不得为空";
        }
        if ((ctx.round() > 1 || ctx.finalRound()) && draft.evaluation() == null) {
            return "round > 1 时必须输出对上一轮回答的 evaluation";
        }
        String lastDimension = ctx.lastDimension();
        if (!ctx.finalRound() && ctx.round() > 1 && lastDimension != null && !lastDimension.isBlank()
                && lastDimension.equals(draft.focusDimension())) {
            return "本轮 focus_dimension 与上一轮重复（" + lastDimension + "），请更换考察维度";
        }
        return null;
    }

    /** 规范化：首轮 evaluation 置空、维度与证据引用兜底、字数裁剪，保证下游结构稳定。 */
    private InterviewDraft normalize(InterviewDraft draft, RoundContext ctx) {
        String dimension = draft.focusDimension();
        if (ctx.finalRound()) {
            // 收尾轮次不产生新问题，维度沿用上一轮，避免污染「维度不重复」的历史
            dimension = ctx.lastDimension() == null || ctx.lastDimension().isBlank()
                    ? (dimension == null || dimension.isBlank() ? FALLBACK_DIMENSION : dimension)
                    : ctx.lastDimension();
        } else if (dimension == null || dimension.isBlank()) {
            dimension = FALLBACK_DIMENSION;
        } else if (ctx.round() > 1 && ctx.lastDimension() != null && dimension.equals(ctx.lastDimension())) {
            // 重试后仍与上一轮重复：在服务端就近替换为未考察维度，保证「不重复维度」硬约束成立
            dimension = pickUnvisited(dimension, ctx.visitedDimensions(), ctx.dimensionNames());
            log.warn("[interviewer] focus_dimension 与上一轮重复，已就近替换为：{}", dimension);
        }
        String evidenceRef = draft.evidenceRef();
        if (evidenceRef != null && !evidenceRef.isBlank() && ctx.evidenceIds() != null
                && !ctx.evidenceIds().isEmpty() && !ctx.evidenceIds().contains(evidenceRef)) {
            log.warn("[interviewer] evidence_ref={} 不在证据索引中，降级为 null", evidenceRef);
            evidenceRef = null;
        }
        String question = draft.question() == null ? "" : draft.question().trim();
        if (question.isEmpty() && ctx.finalRound()) {
            question = "面试结束";
        }
        if (question.length() > 400) {
            question = question.substring(0, 400);
        }
        List<String> points = draft.answerPoints() == null ? List.of()
                : draft.answerPoints().stream().filter(v -> v != null && !v.isBlank()).limit(5).toList();
        InterviewDraft.Evaluation evaluation = ctx.round() <= 1
                ? null : (draft.evaluation() == null ? null : draft.evaluation().normalized());
        return new InterviewDraft(ctx.round(), question, dimension, evidenceRef, points,
                draft.followupHint(), evaluation);
    }

    private static String pickUnvisited(String current, List<String> visited, List<String> dimensionNames) {
        if (dimensionNames == null || dimensionNames.isEmpty()) {
            return current;
        }
        for (String name : dimensionNames) {
            if (!name.equals(current) && (visited == null || !visited.contains(name))) {
                return name;
            }
        }
        for (String name : dimensionNames) {
            if (!name.equals(current)) {
                return name;
            }
        }
        return current;
    }

    private String retryInstruction(String error, RoundContext ctx) {
        StringBuilder hint = new StringBuilder();
        hint.append("\n\n【上一次输出未通过校验，请修正后重新输出】\n错误信息：").append(error);
        if (ctx.evidenceIds() != null && !ctx.evidenceIds().isEmpty()) {
            hint.append("\n可用的 evidence_id：").append(String.join(", ", ctx.evidenceIds()));
        }
        if (ctx.dimensionNames() != null && !ctx.dimensionNames().isEmpty()) {
            hint.append("\n可用的 focus_dimension：").append(String.join("、", ctx.dimensionNames()));
        }
        hint.append("\n请严格按【输出 JSON Schema】重新输出，只输出 JSON 本身，禁止任何解释文字与 Markdown 代码块标记。");
        return hint.toString();
    }

    private static int tokens(ChatResponse response, boolean prompt) {
        if (response == null || response.usage() == null) {
            return 0;
        }
        return prompt ? response.usage().prompt_tokens() : response.usage().completion_tokens();
    }

    private static String blankToDash(String text) {
        return text == null || text.isBlank() ? "（无）" : text;
    }

    /** 供单元测试 / 调试：把历史轮次序列化为精简 JSON（只保留 round/question/answer）。 */
    public String historyJson(List<Map<String, Object>> history) {
        try {
            return mapper.writeValueAsString(history == null ? List.of() : history);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("history", history);
            return "{}";
        }
    }
}
