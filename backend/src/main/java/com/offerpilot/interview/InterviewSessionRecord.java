package com.offerpilot.interview;

import com.offerpilot.dto.MatchResponse;
import com.offerpilot.task.TaskEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 面试会话的持久化镜像（Jackson 序列化到 {@code data/session/session-{id}.json}）。
 * <p>
 * 仅保存可恢复的业务状态（会话元信息 / transcript / score / summary / 事件历史 / seq）；
 * SSE 连接、会话锁、运行时心跳、回放标记等瞬态不落盘，恢复后由运行态重建。
 */
public class InterviewSessionRecord {

    private long id;
    private String taskNo;
    private int maxRound;
    private String startedAt;
    private boolean replayMode;
    private String replaySnapshotId;
    private String status = InterviewSession.ONGOING;
    private int roundNo;
    private List<InterviewTurn> transcript = new ArrayList<>();
    private Map<String, Object> score;
    private String summaryMd;
    private String finishedAt;
    private String caseId;
    /** JD / 简历原文快照：会话恢复后构建模型上下文不依赖内存 TaskStore。 */
    private String jdText;
    private String resumeText;
    private List<TaskEvent> events = new ArrayList<>();
    private long seq;
    /** 关联匹配报告快照（会话恢复后 answer/finish 不依赖内存 TaskStore）。 */
    private MatchResponse report;

    public InterviewSessionRecord() {
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getTaskNo() {
        return taskNo;
    }

    public void setTaskNo(String taskNo) {
        this.taskNo = taskNo;
    }

    public int getMaxRound() {
        return maxRound;
    }

    public void setMaxRound(int maxRound) {
        this.maxRound = maxRound;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public boolean isReplayMode() {
        return replayMode;
    }

    public void setReplayMode(boolean replayMode) {
        this.replayMode = replayMode;
    }

    public String getReplaySnapshotId() {
        return replaySnapshotId;
    }

    public void setReplaySnapshotId(String replaySnapshotId) {
        this.replaySnapshotId = replaySnapshotId;
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

    public void setTranscript(List<InterviewTurn> transcript) {
        this.transcript = transcript == null ? new ArrayList<>() : new ArrayList<>(transcript);
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

    public List<TaskEvent> getEvents() {
        return events;
    }

    public void setEvents(List<TaskEvent> events) {
        this.events = events == null ? new ArrayList<>() : new ArrayList<>(events);
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
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
}
