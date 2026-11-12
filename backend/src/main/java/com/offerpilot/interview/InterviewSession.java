package com.offerpilot.interview;

import com.offerpilot.dto.MatchResponse;
import com.offerpilot.task.TaskEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟面试会话状态机（需求说明书 3.6 / 5.6 / 6.4）。
 * <p>
 * 状态流转：{@code ONGOING}（进行中）→ {@code FINISHED}（已出总结）/ {@code ABORTED}（中断）。
 * 每轮问答串行执行（同一会话内同一时刻只允许一个 Agent 调用），逐轮追加 transcript 并累加 round_no。
 * 会话自带 SSE 事件历史与 seq，支持 Last-Event-ID 断线补齐；离线快照回放会话复用同一结构，
 * 只是事件由快照文件驱动重新发布。
 */
public class InterviewSession {

    public static final String ONGOING = "ONGOING";
    public static final String FINISHED = "FINISHED";
    public static final String ABORTED = "ABORTED";

    private final long id;
    private final String taskNo;
    private final int maxRound;
    private final String startedAt;
    private final boolean replayMode;
    private final String replaySnapshotId;

    private String status = ONGOING;
    private int roundNo = 0;
    private final List<InterviewTurn> transcript = new ArrayList<>();
    private Map<String, Object> score;
    private String summaryMd;
    private String finishedAt;
    private String caseId;
    /** 关联匹配报告快照：持久化后会话恢复可脱离内存 TaskStore 独立续传。 */
    private MatchResponse report;
    /** JD / 简历原文快照：构建面试官上下文与总结时使用（恢复后不依赖 TaskStore）。 */
    private String jdText;
    private String resumeText;

    /** SSE 事件历史（含 seq 与 ts），用于 Last-Event-ID 补齐与快照录制。 */
    private final List<TaskEvent> events = new ArrayList<>();
    private final Map<Long, SseEmitter> emitters = new LinkedHashMap<>();
    private long seq = 0;
    /** 会话级互斥锁：保证「逐轮串行」在同一会话内成立。 */
    private final Object lock = new Object();
    /** 当前正在执行的 Agent 名（用于 interview.progress 心跳）。 */
    private volatile String runningAgent;
    private volatile long runningSince;
    /** 快照回放是否已启动（同一会话只回放一次）。 */
    private volatile boolean replayed = false;

    public InterviewSession(long id, String taskNo, int maxRound, boolean replayMode, String replaySnapshotId) {
        this(id, taskNo, maxRound, replayMode, replaySnapshotId, null);
    }

    /** 可指定开始时间的构造器：会话持久化恢复路径使用（startedAt 保留原始值）。 */
    public InterviewSession(long id, String taskNo, int maxRound, boolean replayMode, String replaySnapshotId,
                            String startedAt) {
        this.id = id;
        this.taskNo = taskNo;
        this.maxRound = maxRound <= 0 ? 5 : maxRound;
        this.replayMode = replayMode;
        this.replaySnapshotId = replaySnapshotId;
        this.startedAt = startedAt == null || startedAt.isBlank()
                ? com.offerpilot.task.TaskStore.now() : startedAt;
    }

    public long getId() {
        return id;
    }

    public String getTaskNo() {
        return taskNo;
    }

    public int getMaxRound() {
        return maxRound;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public boolean isReplayMode() {
        return replayMode;
    }

    public String getReplaySnapshotId() {
        return replaySnapshotId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getRoundNo() {
        return roundNo;
    }

    public void setRoundNo(int roundNo) {
        this.roundNo = roundNo;
    }

    public List<InterviewTurn> getTranscript() {
        return transcript;
    }

    public Map<String, Object> getScore() {
        return score;
    }

    public void setScore(Map<String, Object> score) {
        this.score = score;
    }

    public String getSummaryMd() {
        return summaryMd;
    }

    public void setSummaryMd(String summaryMd) {
        this.summaryMd = summaryMd;
    }

    public String getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(String finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getCaseId() {
        return caseId;
    }

    public void setCaseId(String caseId) {
        this.caseId = caseId;
    }

    public MatchResponse getReport() {
        return report;
    }

    public void setReport(MatchResponse report) {
        this.report = report;
    }

    public String getJdText() {
        return jdText;
    }

    public void setJdText(String jdText) {
        this.jdText = jdText;
    }

    public String getResumeText() {
        return resumeText;
    }

    public void setResumeText(String resumeText) {
        this.resumeText = resumeText;
    }

    public List<TaskEvent> getEvents() {
        return events;
    }

    public Map<Long, SseEmitter> getEmitters() {
        return emitters;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    /** 分配下一个事件序号（调用方需持有会话锁）。 */
    public long nextSeq() {
        return ++seq;
    }

    public Object lock() {
        return lock;
    }

    public String getRunningAgent() {
        return runningAgent;
    }

    public long getRunningSince() {
        return runningSince;
    }

    public void markRunning(String agent) {
        this.runningAgent = agent;
        this.runningSince = System.currentTimeMillis();
    }

    public void markIdle() {
        this.runningAgent = null;
    }

    public boolean isReplayed() {
        return replayed;
    }

    public void markReplayed() {
        this.replayed = true;
    }

    /** 是否已结束（终态）。 */
    public boolean isTerminal() {
        return FINISHED.equals(status) || ABORTED.equals(status);
    }

    /** 当前待回答的轮次（最后一次已追加的轮次）。 */
    public InterviewTurn currentTurn() {
        return transcript.isEmpty() ? null : transcript.get(transcript.size() - 1);
    }

    /** 已考察过的维度（按轮次顺序去重），用于「连续两轮不得考察同一维度」与 Prompt 约束。 */
    public List<String> focusDimensions() {
        List<String> result = new ArrayList<>();
        for (InterviewTurn turn : transcript) {
            if (turn.getFocusDimension() != null && !turn.getFocusDimension().isBlank()
                    && !result.contains(turn.getFocusDimension())) {
                result.add(turn.getFocusDimension());
            }
        }
        return result;
    }

    /** 持久化镜像：仅业务可恢复字段（SSE 连接、锁、回放标记等瞬态不落盘）。 */
    public InterviewSessionRecord toRecord() {
        InterviewSessionRecord r = new InterviewSessionRecord();
        r.setId(id);
        r.setTaskNo(taskNo);
        r.setMaxRound(maxRound);
        r.setStartedAt(startedAt);
        r.setReplayMode(replayMode);
        r.setReplaySnapshotId(replaySnapshotId);
        r.setStatus(status);
        r.setRoundNo(roundNo);
        r.setTranscript(transcript);
        r.setScore(score);
        r.setSummaryMd(summaryMd);
        r.setFinishedAt(finishedAt);
        r.setCaseId(caseId);
        r.setEvents(events);
        r.setSeq(seq);
        r.setReport(report);
        r.setJdText(jdText);
        r.setResumeText(resumeText);
        return r;
    }

    /** 从持久化镜像恢复会话：status/transcript/score/summary/事件历史与 seq 原样恢复，瞬态清空。 */
    public static InterviewSession fromRecord(InterviewSessionRecord r) {
        InterviewSession s = new InterviewSession(r.getId(), r.getTaskNo(), r.getMaxRound(),
                r.isReplayMode(), r.getReplaySnapshotId(), r.getStartedAt());
        s.status = r.getStatus() == null ? ONGOING : r.getStatus();
        s.roundNo = r.getRoundNo();
        if (r.getTranscript() != null) {
            s.transcript.addAll(r.getTranscript());
        }
        s.score = r.getScore();
        s.summaryMd = r.getSummaryMd();
        s.finishedAt = r.getFinishedAt();
        s.caseId = r.getCaseId();
        if (r.getEvents() != null) {
            s.events.addAll(r.getEvents());
        }
        s.seq = r.getSeq();
        s.report = r.getReport();
        s.jdText = r.getJdText();
        s.resumeText = r.getResumeText();
        return s;
    }
}
