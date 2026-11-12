package com.offerpilot.interview;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.agent.AgentResult;
import com.offerpilot.agent.InterviewAgent;
import com.offerpilot.common.ErrorCode;
import com.offerpilot.dto.InterviewDraft;
import com.offerpilot.dto.MatchReport;
import com.offerpilot.dto.MatchResponse;
import com.offerpilot.task.AnalysisTask;
import com.offerpilot.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * S6 模拟面试会话编排（需求说明书 3.6 / 5.6 / 6.2 A10–A13 / 9 章 S6）。
 * <p>
 * 职责：会话状态机（ONGOING → FINISHED/ABORTED）、逐轮串行调用面试官 Agent、
 * 事件广播（interview.question / interview.evaluation / interview.finished）、
 * 汇总评分与总结生成、离线快照录制与回放编排。
 * <ul>
 *     <li>逐轮串行：同一会话内由 {@link InterviewSession#lock()} 互斥，禁止并发调用模型；</li>
 *     <li>单轮时延：面试官模型读取超时 {@code deepseek.timeout.interview-seconds}（60s / 轮）；</li>
 *     <li>降级：评分失败把错误上抛（保留会话可重试）；总结失败回退为规则化总结，保证演示不中断。</li>
 * </ul>
 */
@Service
public class InterviewService {

    private static final Logger log = LoggerFactory.getLogger(InterviewService.class);

    private static final String AGENT_INTERVIEWER = "INTERVIEWER";
    private static final String FALLBACK_DIMENSION = "项目与成果相关度";

    private final TaskStore tasks;
    private final InterviewSessionStore sessions;
    private final InterviewAgent agent;
    private final InterviewSnapshotService snapshots;
    private final ObjectMapper mapper = new ObjectMapper();

    private final int defaultMaxRound;
    private final int maxRoundLimit;

    /** 离线回放线程池（守护线程，回放不占用请求线程）。 */
    private final ExecutorService replayPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "offerpilot-interview-replay");
        t.setDaemon(true);
        return t;
    });

    public InterviewService(TaskStore tasks,
                            InterviewSessionStore sessions,
                            InterviewAgent agent,
                            InterviewSnapshotService snapshots,
                            @Value("${offerpilot.interview.default-max-round:3}") int defaultMaxRound,
                            @Value("${offerpilot.interview.max-round-limit:5}") int maxRoundLimit) {
        this.tasks = tasks;
        this.sessions = sessions;
        this.agent = agent;
        this.snapshots = snapshots;
        this.defaultMaxRound = defaultMaxRound <= 0 ? 3 : defaultMaxRound;
        this.maxRoundLimit = maxRoundLimit <= 0 ? 5 : maxRoundLimit;
    }

    // ------------------------------------------------------------------ A10 开启面试

    /**
     * 开启模拟面试：校验任务与报告已就绪 → 建会话 → 同步生成首轮问题。
     * 返回 {@code session_id}、首轮 {@code question} 与会话元信息（对应 A10 契约）。
     */
    public Map<String, Object> start(String taskNo, Integer maxRound, String focusDimension, String caseId) {
        AnalysisTask task = tasks.get(taskNo);
        if (task == null) {
            throw new InterviewException(ErrorCode.NOT_FOUND.getCode(), "任务不存在：" + taskNo);
        }
        MatchResponse response = task.getResult();
        if (response == null || response.report() == null) {
            throw new InterviewException(ErrorCode.TASK_RUNNING.getCode(),
                    "匹配报告尚未生成，无法开启面试（任务状态：" + task.getStatus() + "）");
        }
        int round = clampRound(maxRound);
        InterviewSession session = sessions.create(taskNo, round, false, null);
        session.setCaseId(caseId == null || caseId.isBlank() ? null : caseId.trim());
        session.setReport(response); // 报告随会话持久化，重启后 answer/finish 可脱离 TaskStore 续传
        session.setJdText(task.getJdText()); // JD/简历原文随会话持久化，恢复后构建模型上下文
        session.setResumeText(task.getResumeText());

        interviewContext(session, task, response);
        publishStarted(session, response.report());

        // 首轮同步生成：A10 响应体需要直接带回首轮问题
        runRound(session, task, response, focusDimension);

        Map<String, Object> body = view(session);
        body.put("question", session.currentTurn() == null ? null : session.currentTurn().getQuestion());
        body.put("focus_dimension", session.currentTurn() == null ? null : session.currentTurn().getFocusDimension());
        body.put("evaluation", null);
        log.info("[interview] 开启面试 session={} task={} maxRound={} caseId={}", session.getId(), taskNo, round,
                session.getCaseId());
        return body;
    }

    // ------------------------------------------------------------------ A11 提交回答

    /**
     * 提交本轮回答：写入 transcript → 若已达最大轮次则收尾（评分 + 总结），否则生成下一轮问题。
     * 返回 {@code round_no}、上一轮 {@code evaluation} 与下一轮 {@code question}（对应 A11 契约）。
     */
    public Map<String, Object> answer(long sessionId, String answer) {
        if (answer == null || answer.isBlank()) {
            throw new InterviewException(ErrorCode.PARAM_INVALID.getCode(), "answer 不得为空");
        }
        InterviewSession session = require(sessionId);
        if (session.isReplayMode()) {
            throw new InterviewException(ErrorCode.TASK_RUNNING.getCode(), "离线回放会话不接受回答提交");
        }
        AnalysisTask task = tasks.get(session.getTaskNo());
        MatchResponse response = session.getReport() != null
                ? session.getReport()
                : (task == null ? null : task.getResult());
        if (response == null || response.report() == null) {
            throw new InterviewException(ErrorCode.NOT_FOUND.getCode(),
                    "关联任务报告不可用：" + session.getTaskNo());
        }

        synchronized (session.lock()) {
            if (session.isTerminal()) {
                throw new InterviewException(ErrorCode.TASK_RUNNING.getCode(),
                        "面试会话已结束（" + session.getStatus() + "），如需重练请开启新会话");
            }
            InterviewTurn turn = session.currentTurn();
            if (turn == null) {
                throw new InterviewException(ErrorCode.TASK_RUNNING.getCode(), "会话尚未生成问题，无法提交回答");
            }
            if (turn.answered()) {
                throw new InterviewException(ErrorCode.TASK_RUNNING.getCode(),
                        "第 " + turn.getRound() + " 轮已提交过回答，禁止重复提交");
            }
            turn.setAnswer(answer.trim());
            turn.setAnsweredAt(TaskStore.now());
            sessions.publish(session, "interview.answer", payload(
                    "round_no", turn.getRound(),
                    "answer_length", turn.getAnswer().length()));

            try {
                if (session.getRoundNo() >= session.getMaxRound()) {
                    finalize(session, task, response);
                } else {
                    runRound(session, task, response, null);
                }
            } catch (RuntimeException e) {
                // 单轮模型超时/失败边界：回滚本轮"已作答"标记并强制落盘，
                // 允许用户重试同一轮，避免会话卡死在"已作答但未评分"状态（agent.failed 事件已发布，保留在时间线）。
                turn.setAnswer(null);
                turn.setAnsweredAt(null);
                sessions.persist(session);
                log.warn("[interview] 第 {} 轮处理失败，已回滚本轮作答标记，可重试：{}", turn.getRound(), e.getMessage());
                throw e;
            }
        }
        Map<String, Object> body = view(session);
        InterviewTurn answered = lastAnswered(session);
        boolean finished = session.isTerminal();
        body.put("round_no", session.getRoundNo());
        body.put("evaluation", evaluationOf(answered));
        body.put("question", finished ? null : session.currentTurn().getQuestion());
        body.put("focus_dimension", finished ? null : session.currentTurn().getFocusDimension());
        return body;
    }

    // ------------------------------------------------------------------ A12 会话详情

    public Map<String, Object> detail(long sessionId) {
        return view(require(sessionId));
    }

    // ------------------------------------------------------------------ A13 结束面试并出总结

    /** 结束面试：对最后一轮回答补评分（如缺失）→ 汇总 → 生成总结 → 会话置 FINISHED。 */
    public Map<String, Object> finish(long sessionId) {
        InterviewSession session = require(sessionId);
        if (session.isReplayMode()) {
            throw new InterviewException(ErrorCode.TASK_RUNNING.getCode(), "离线回放会话无需结束操作");
        }
        AnalysisTask task = tasks.get(session.getTaskNo());
        MatchResponse response = session.getReport() != null
                ? session.getReport()
                : (task == null ? null : task.getResult());
        synchronized (session.lock()) {
            if (InterviewSession.FINISHED.equals(session.getStatus())) {
                return view(session);
            }
            if (InterviewSession.ABORTED.equals(session.getStatus())) {
                throw new InterviewException(ErrorCode.TASK_RUNNING.getCode(), "会话已中断，无法结束");
            }
            finalize(session, task, response);
        }
        return view(session);
    }

    // ------------------------------------------------------------------ 快照录制（S6 演示）

    /** 把当前会话录制为离线快照（caseId 缺省时用会话 taskNo 兜底）。 */
    public Map<String, Object> recordSnapshot(long sessionId, String caseId) {
        InterviewSession session = require(sessionId);
        String id = (caseId == null || caseId.isBlank()) ? session.getCaseId() : caseId.trim();
        if (id == null || id.isBlank()) {
            throw new InterviewException(ErrorCode.PARAM_INVALID.getCode(), "case_id 不得为空");
        }
        session.setCaseId(id);
        InterviewSnapshotService.Snapshot snapshot = snapshots.record(session, id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot_id", snapshot.caseId());
        body.put("kind", "interview");
        body.put("round_count", snapshot.roundCount());
        body.put("event_count", snapshot.events() == null ? 0 : snapshot.events().size());
        body.put("status", snapshot.status());
        body.put("file", snapshots.dir().resolve("interview-" + snapshot.caseId() + ".json").toString());
        return body;
    }

    /** 面试快照清单（A14）。 */
    public List<Map<String, Object>> listSnapshots() {
        return snapshots.list();
    }

    /**
     * A15 加载快照进入重放模式：建虚拟会话（预填 transcript / 评分 / 总结）→ 后台按压缩节奏重放事件。
     * 全程不调用任何外部模型接口，供断网演示使用。
     */
    public Map<String, Object> loadReplay(String snapshotId) {
        InterviewSnapshotService.Snapshot snapshot = snapshots.load(snapshotId)
                .orElseThrow(() -> new InterviewException(ErrorCode.NOT_FOUND.getCode(),
                        "快照不存在：" + snapshotId));
        InterviewSession session = sessions.create(snapshot.taskNo(), snapshot.maxRound(), true, snapshot.caseId());
        session.setCaseId(snapshot.caseId());
        session.setRoundNo(snapshot.roundCount());
        session.setStatus(InterviewSession.FINISHED.equals(snapshot.status())
                ? InterviewSession.FINISHED : InterviewSession.ONGOING);
        session.setScore(snapshot.score());
        session.setSummaryMd(snapshot.summaryMd());
        for (InterviewTurn turn : copyTranscript(snapshot.transcript())) {
            session.getTranscript().add(turn);
        }

        replayPool.submit(() -> runReplay(session, snapshot, snapshotId));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("snapshot_id", snapshot.caseId());
        body.put("kind", "interview");
        body.put("task_no", session.getTaskNo());
        body.put("session_id", session.getId());
        body.put("replay_speed", snapshots.speed());
        body.put("replay_mode", true);
        body.put("round_count", snapshot.roundCount());
        body.put("max_round", snapshot.maxRound());
        body.put("stream", "/api/v1/interviews/" + session.getId() + "/stream");
        body.put("status", session.getStatus());
        log.info("[interview] 加载面试快照 {} → 重放会话 {}（{} 事件）", snapshotId, session.getId(),
                snapshot.events() == null ? 0 : snapshot.events().size());
        return body;
    }

    // ------------------------------------------------------------------ 内部：逐轮调用

    /** 单轮调用：面试官 Agent 生成第 round 轮问题（并对上一轮回答评分）。 */
    private void runRound(InterviewSession session, AnalysisTask task, MatchResponse response, String hint) {
        int round = session.getRoundNo() + 1;
        InterviewAgent.RoundContext ctx = context(session, task, response, round, hint, false);
        session.markRunning(AGENT_INTERVIEWER);
        sessions.publish(session, "agent.started", payload(
                "agent_name", AGENT_INTERVIEWER,
                "model", agent.modelName(),
                "prompt_version", agent.promptVersion(),
                "round_no", round));
        long start = System.currentTimeMillis();
        AgentResult<InterviewDraft> result;
        try {
            result = agent.nextRound(ctx);
        } catch (RuntimeException e) {
            sessions.publish(session, "agent.failed", payload(
                    "agent_name", AGENT_INTERVIEWER,
                    "round_no", round,
                    "error_code", ErrorCode.SCHEMA_INVALID.getCode(),
                    "message", String.valueOf(e.getMessage())));
            throw new InterviewException(ErrorCode.SCHEMA_INVALID.getCode(),
                    "面试官 Agent 输出未通过校验：" + e.getMessage());
        } finally {
            session.markIdle();
        }
        InterviewDraft draft = result.data();

        // 对上一轮回答的评分：挂在上一轮 turn 上（首轮无评分）
        if (draft.evaluation() != null) {
            InterviewTurn last = session.currentTurn();
            if (last != null) {
                applyEvaluation(last, draft.evaluation());
                sessions.publish(session, "interview.evaluation", payload(
                        "round_no", last.getRound(),
                        "score", last.getScore(),
                        "strengths", last.getStrengths(),
                        "improvements", last.getImprovements()));
            }
        }

        InterviewTurn turn = new InterviewTurn(draft.round() == null ? round : draft.round());
        turn.setQuestion(draft.question());
        turn.setFocusDimension(draft.focusDimension());
        turn.setEvidenceRef(draft.evidenceRef());
        turn.setAnswerPoints(draft.answerPoints());
        turn.setFollowupHint(draft.followupHint());
        turn.setLatencyMs(result.elapsedMs());
        session.getTranscript().add(turn);
        session.setRoundNo(turn.getRound());

        sessions.publish(session, "interview.question", payload(
                "round_no", turn.getRound(),
                "question", turn.getQuestion(),
                "focus_dimension", turn.getFocusDimension(),
                "evidence_ref", turn.getEvidenceRef(),
                "max_round", session.getMaxRound()));
        sessions.publish(session, "agent.finished", payload(
                "agent_name", AGENT_INTERVIEWER,
                "round_no", turn.getRound(),
                "latency_ms", System.currentTimeMillis() - start,
                "token_total", result.totalTokens(),
                "retry_count", Math.max(0, result.attempts() - 1),
                "status", "SUCCEEDED"));
    }

    /** 收尾：补最后一轮评分 → 汇总评分 → 生成总结（失败回退规则化总结）→ 广播结束事件。 */
    private void finalize(InterviewSession session, AnalysisTask task, MatchResponse response) {
        InterviewTurn last = session.currentTurn();
        if (last != null && last.answered() && last.getScore() == null && task != null && response != null) {
            int round = session.getRoundNo() + 1;
            InterviewAgent.RoundContext ctx = context(session, task, response, round, null, true);
            session.markRunning(AGENT_INTERVIEWER);
            sessions.publish(session, "agent.started", payload(
                    "agent_name", AGENT_INTERVIEWER,
                    "model", agent.modelName(),
                    "round_no", round,
                    "mode", "FINALIZE"));
            try {
                AgentResult<InterviewDraft> result = agent.nextRound(ctx);
                if (result.data().evaluation() != null) {
                    applyEvaluation(last, result.data().evaluation());
                    sessions.publish(session, "interview.evaluation", payload(
                            "round_no", last.getRound(),
                            "score", last.getScore(),
                            "strengths", last.getStrengths(),
                            "improvements", last.getImprovements()));
                }
            } catch (RuntimeException e) {
                log.warn("[interview] 收尾评分失败，按未评分处理：{}", e.getMessage());
            } finally {
                session.markIdle();
            }
        }

        Map<String, Object> score = aggregate(session);
        session.setScore(score);
        session.setSummaryMd(buildSummary(session, score, task));
        session.setStatus(InterviewSession.FINISHED);
        session.setFinishedAt(TaskStore.now());
        sessions.publish(session, "interview.finished", payload(
                "status", session.getStatus(),
                "round_no", session.getRoundNo(),
                "avg_score", score.get("avg_score"),
                "score", score,
                "summary_md", session.getSummaryMd(),
                "finished_at", session.getFinishedAt()));

        if (session.getCaseId() != null && !session.getCaseId().isBlank()) {
            try {
                snapshots.record(session, session.getCaseId());
            } catch (RuntimeException e) {
                log.warn("[interview] 自动录制快照失败（不影响会话）：{}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 内部：上下文与评分

    private InterviewAgent.RoundContext context(InterviewSession session, AnalysisTask task,
                                                MatchResponse response, int round, String hint,
                                                boolean finalRound) {
        MatchReport report = response.report();
        List<String> visited = session.focusDimensions();
        InterviewTurn last = session.currentTurn();
        Set<String> evidenceIds = new LinkedHashSet<>();
        if (report.evidenceIndex() != null) {
            report.evidenceIndex().stream()
                    .map(MatchReport.MatchEvidence::evidenceId)
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(evidenceIds::add);
        }
        List<String> dimensionNames = new ArrayList<>();
        if (report.dimensions() != null) {
            report.dimensions().stream()
                    .map(MatchReport.DimensionScore::name)
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(name -> {
                        if (!dimensionNames.contains(name)) {
                            dimensionNames.add(name);
                        }
                    });
        }
        if (dimensionNames.isEmpty()) {
            dimensionNames.add(FALLBACK_DIMENSION);
        }
        return new InterviewAgent.RoundContext(
                session.getJdText(),
                session.getResumeText(),
                json(report),
                json(session.getTranscript()),
                round,
                last == null ? null : last.getFocusDimension(),
                visited,
                dimensionNames,
                evidenceIds,
                round == 1 ? hint : null,
                finalRound);
    }

    private void interviewContext(InterviewSession session, AnalysisTask task, MatchResponse response) {
        // 预校验上下文可用（JD / 简历原文与证据索引），缺失直接拒绝开启，避免白跑模型
        if (task.getResumeText() == null || task.getResumeText().isBlank()) {
            throw new InterviewException(ErrorCode.PARAM_INVALID.getCode(), "任务缺少简历原文，无法开启面试");
        }
        if (response.report().evidenceIndex() == null || response.report().evidenceIndex().isEmpty()) {
            log.warn("[interview] 证据索引为空（task={}），问题将不引用 evidence_ref", session.getTaskNo());
        }
    }

    /** 汇总评分：各轮 evaluation 聚合（3.6 的 score_json）。 */
    private Map<String, Object> aggregate(InterviewSession session) {
        List<InterviewTurn> scored = new ArrayList<>();
        for (InterviewTurn turn : session.getTranscript()) {
            if (turn.getScore() != null) {
                scored.add(turn);
            }
        }
        List<Map<String, Object>> perRound = new ArrayList<>();
        List<Map<String, Object>> dimensions = new ArrayList<>();
        List<Map<String, Object>> strengths = new ArrayList<>();
        List<Map<String, Object>> improvements = new ArrayList<>();
        double sum = 0;
        for (InterviewTurn turn : scored) {
            sum += turn.getScore();
            perRound.add(payload(
                    "round_no", turn.getRound(),
                    "score", turn.getScore(),
                    "focus_dimension", turn.getFocusDimension()));
            dimensions.add(payload(
                    "name", turn.getFocusDimension(),
                    "score", turn.getScore(),
                    "round_no", turn.getRound()));
            for (String s : turn.getStrengths()) {
                strengths.add(payload("round_no", turn.getRound(), "text", s));
            }
            for (String s : turn.getImprovements()) {
                improvements.add(payload("round_no", turn.getRound(), "text", s));
            }
        }
        double avg = scored.isEmpty() ? 0 : round1(sum / scored.size());
        Map<String, Object> score = payload(
                "avg_score", avg,
                "answered_rounds", scored.size(),
                "planned_rounds", session.getMaxRound(),
                "level", levelOf(avg, scored.isEmpty()),
                "per_round", perRound,
                "dimensions", dimensions,
                "strengths", strengths,
                "improvements", improvements,
                "note", scored.isEmpty()
                        ? "本次面试未产生有效评分（无已回答轮次）。"
                        : "本场共评定 " + scored.size() + " 轮，平均分 " + avg + "，档位 "
                                + levelOf(avg, false) + "；分数仅用于练习自评，不代表真实面试结论。");
        return score;
    }

    /** 总结：优先调用模型生成 Markdown，失败回退规则化总结（保证演示不中断）。 */
    private String buildSummary(InterviewSession session, Map<String, Object> score, AnalysisTask task) {
        String transcriptJson = json(session.getTranscript());
        String scoreJson = json(score);
        try {
            InterviewAgent.SummaryResult summary = agent.summarize(
                    session.getJdText() == null ? "" : session.getJdText(),
                    session.getResumeText() == null ? "" : session.getResumeText(),
                    transcriptJson, scoreJson);
            if (summary.markdown() != null && !summary.markdown().isBlank()) {
                return summary.markdown();
            }
            return fallbackSummary(session, score, "模型返回为空");
        } catch (RuntimeException e) {
            log.warn("[interview] 总结模型调用失败，回退规则化总结：{}", e.getMessage());
            return fallbackSummary(session, score, e.getMessage());
        }
    }

    private String fallbackSummary(InterviewSession session, Map<String, Object> score, String reason) {
        StringBuilder md = new StringBuilder();
        md.append("## 总体评价\n\n");
        md.append("本场面试共 ").append(session.getRoundNo()).append(" 轮，已回答 ")
                .append(score.get("answered_rounds")).append(" 轮，平均分 ")
                .append(score.get("avg_score")).append(" 分（档位 ").append(score.get("level")).append("）。\n\n");
        md.append("## 分维度表现\n\n");
        Object dims = score.get("dimensions");
        if (dims instanceof List<?> list && !list.isEmpty()) {
            for (Object d : list) {
                if (d instanceof Map<?, ?> m) {
                    md.append("- ").append(m.get("name")).append("：").append(m.get("score")).append(" 分（第 ")
                            .append(m.get("round_no")).append(" 轮）\n");
                }
            }
        } else {
            md.append("- 本次无有效评分记录\n");
        }
        md.append("\n## 薄弱环节\n\n");
        Object imps = score.get("improvements");
        if (imps instanceof List<?> list && !list.isEmpty()) {
            for (Object i : list) {
                if (i instanceof Map<?, ?> m) {
                    md.append("- 第 ").append(m.get("round_no")).append(" 轮：").append(m.get("text")).append("\n");
                }
            }
        } else {
            md.append("- 无\n");
        }
        md.append("\n## 下一步准备建议\n\n");
        md.append("- 针对上述薄弱环节各准备 1 个 STAR 案例（背景-任务-行动-结果），结果尽量量化。\n");
        md.append("- 对反复被追问的维度做一次口述计时练习，控制在 2 分钟内说清结论与依据。\n");
        md.append("\n> 注：本总结由规则生成（模型调用失败：").append(reason).append("），结论仅基于逐轮评分与改进建议。\n");
        return md.toString();
    }

    // ------------------------------------------------------------------ 内部：事件与视图

    private void publishStarted(InterviewSession session, MatchReport report) {
        sessions.publish(session, "interview.started", payload(
                "max_round", session.getMaxRound(),
                "replay_mode", session.isReplayMode(),
                "level", report == null ? null : report.level(),
                "overall_score", report == null ? null : report.overallScore()));
    }

    private Map<String, Object> view(InterviewSession session) {
        Map<String, Object> body = payload(
                "session_id", session.getId(),
                "task_no", session.getTaskNo(),
                "status", session.getStatus(),
                "round_no", session.getRoundNo(),
                "max_round", session.getMaxRound(),
                "transcript", session.getTranscript(),
                "score", session.getScore(),
                "summary_md", session.getSummaryMd(),
                "started_at", session.getStartedAt(),
                "finished_at", session.getFinishedAt(),
                "replay_mode", session.isReplayMode(),
                "replay_snapshot_id", session.getReplaySnapshotId(),
                "last_seq", sessions.lastSeq(session),
                "stream", "/api/v1/interviews/" + session.getId() + "/stream");
        return body;
    }

    private void runReplay(InterviewSession session, InterviewSnapshotService.Snapshot snapshot, String snapshotId) {
        if (session.isReplayed()) {
            return;
        }
        session.markReplayed();
        try {
            publishStarted(session, null);
            sessions.publish(session, "replay.tick", payload(
                    "step", 0,
                    "delay_ms", 200,
                    "payload", payload("kind", "interview", "snapshot_id", snapshotId,
                            "max_round", snapshot.maxRound(),
                            "round_count", snapshot.roundCount(),
                            "avg_score", snapshot.score() == null ? null : snapshot.score().get("avg_score"))));
            long elapsed = snapshots.replay(session, snapshot, sessions);
            sessions.publish(session, "replay.finished", payload(
                    "snapshot_id", snapshotId,
                    "elapsed_ms", elapsed,
                    "round_count", snapshot.roundCount(),
                    "status", session.getStatus()));
        } catch (RuntimeException e) {
            log.warn("[interview] 快照回放失败 {}：{}", snapshotId, e.getMessage());
            sessions.publish(session, "replay.failed", payload("snapshot_id", snapshotId,
                    "message", String.valueOf(e.getMessage())));
        } finally {
            sessions.completeEmitters(session);
        }
    }

    private InterviewSession require(long sessionId) {
        InterviewSession session = sessions.get(sessionId);
        if (session == null) {
            throw new InterviewException(ErrorCode.NOT_FOUND.getCode(), "面试会话不存在：" + sessionId);
        }
        return session;
    }

    private static InterviewTurn findTurn(InterviewSession session, int round) {
        for (InterviewTurn turn : session.getTranscript()) {
            if (turn.getRound() == round) {
                return turn;
            }
        }
        return null;
    }

    /** 最近一轮已提交回答的 turn：用于回填 A11 的 evaluation（下一轮已切到新 turn，故不能取 currentTurn）。 */
    private static InterviewTurn lastAnswered(InterviewSession session) {
        List<InterviewTurn> transcript = session.getTranscript();
        for (int i = transcript.size() - 1; i >= 0; i--) {
            InterviewTurn turn = transcript.get(i);
            if (turn.answered()) {
                return turn;
            }
        }
        return null;
    }

    private static Map<String, Object> evaluationOf(InterviewTurn turn) {
        if (turn == null || turn.getScore() == null) {
            return null;
        }
        return payload("score", turn.getScore(), "strengths", turn.getStrengths(),
                "improvements", turn.getImprovements());
    }

    private static void applyEvaluation(InterviewTurn turn, InterviewDraft.Evaluation evaluation) {
        turn.setScore(evaluation.score());
        turn.setStrengths(evaluation.strengths());
        turn.setImprovements(evaluation.improvements());
    }

    private List<InterviewTurn> copyTranscript(List<InterviewTurn> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        try {
            return mapper.convertValue(source, new TypeReference<List<InterviewTurn>>() {
            });
        } catch (Exception e) {
            log.warn("[interview] 快照 transcript 复制失败，改为空记录：{}", e.getMessage());
            return List.of();
        }
    }

    private int clampRound(Integer maxRound) {
        int value = maxRound == null || maxRound <= 0 ? defaultMaxRound : maxRound;
        return Math.min(Math.max(value, 1), maxRoundLimit);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }

    private static String levelOf(double avg, boolean empty) {
        if (empty) {
            return "UNRATED";
        }
        if (avg >= 80) {
            return "STRONG";
        }
        if (avg >= 60) {
            return "OK";
        }
        return "WEAK";
    }

    private static Map<String, Object> payload(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    /** 会话级异常：携带需求说明书 6.1 错误码。 */
    public static class InterviewException extends RuntimeException {
        private final int code;

        public InterviewException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }

    /** 供调试：按 taskNo 找回最近一次会话。 */
    public Optional<InterviewSession> latestOf(String taskNo) {
        return sessions.list().stream()
                .filter(s -> s.getTaskNo().equals(taskNo))
                .max(Comparator.comparingLong(InterviewSession::getId));
    }
}
