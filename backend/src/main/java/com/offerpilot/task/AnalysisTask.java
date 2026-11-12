package com.offerpilot.task;

import com.offerpilot.dto.MatchResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存态分析任务：状态、进度、事件历史与活跃 SSE 连接。
 * <p>
 * 事件历史的读写均在 {@code synchronized (task)} 内完成，seq 严格单调递增，
 * 供断线重连时按 Last-Event-ID 补齐缺失事件。
 */
public class AnalysisTask {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private final String taskNo;
    private final String jdText;
    private final String resumeText;
    private final String createdAt;
    private final long createdAtMs;
    private final int totalAgents;

    private volatile String status = STATUS_RUNNING;
    private volatile int progress;
    private volatile int finishedAgents;
    private volatile String currentAgent;
    private volatile long currentAgentStartedAt;
    private volatile String failedAgent;
    private volatile int tokenTotal;
    private volatile long durationMs;
    private volatile MatchResponse result;

    /** 事件序号（受本对象锁保护）。 */
    long seq;
    /** 事件历史，按 seq 升序（受本对象锁保护）。 */
    final List<TaskEvent> events = new ArrayList<>();
    /** 活跃 SSE 连接（受本对象锁保护）。 */
    final Map<Long, SseEmitter> emitters = new LinkedHashMap<>();

    public AnalysisTask(String taskNo, String jdText, String resumeText, String createdAt,
                        long createdAtMs, int totalAgents) {
        this.taskNo = taskNo;
        this.jdText = jdText;
        this.resumeText = resumeText;
        this.createdAt = createdAt;
        this.createdAtMs = createdAtMs;
        this.totalAgents = totalAgents;
    }

    public boolean isTerminal() {
        return !STATUS_RUNNING.equals(status);
    }

    public String getTaskNo() {
        return taskNo;
    }

    public String getJdText() {
        return jdText;
    }

    public String getResumeText() {
        return resumeText;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public int getTotalAgents() {
        return totalAgents;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public int getFinishedAgents() {
        return finishedAgents;
    }

    public void setFinishedAgents(int finishedAgents) {
        this.finishedAgents = finishedAgents;
    }

    public String getCurrentAgent() {
        return currentAgent;
    }

    public void setCurrentAgent(String currentAgent) {
        this.currentAgent = currentAgent;
    }

    public long getCurrentAgentStartedAt() {
        return currentAgentStartedAt;
    }

    public void setCurrentAgentStartedAt(long currentAgentStartedAt) {
        this.currentAgentStartedAt = currentAgentStartedAt;
    }

    public String getFailedAgent() {
        return failedAgent;
    }

    public void setFailedAgent(String failedAgent) {
        this.failedAgent = failedAgent;
    }

    public int getTokenTotal() {
        return tokenTotal;
    }

    public void setTokenTotal(int tokenTotal) {
        this.tokenTotal = tokenTotal;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public MatchResponse getResult() {
        return result;
    }

    public void setResult(MatchResponse result) {
        this.result = result;
    }
}
