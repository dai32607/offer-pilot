package com.offerpilot.task;

import com.offerpilot.dto.MatchResponse;
import com.offerpilot.service.DemoCacheService;
import com.offerpilot.service.DemoSnapshotService;
import com.offerpilot.service.MatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 异步分析任务的编排入口（需求说明书 6.2 A03/A06、6.4 事件表）。
 * <p>
 * 提交流程：注册任务 → 虚拟线程异步执行 → 逐阶段发布 SSE 事件：
 * task.started → agent.started/finished（JD_PARSE、RESUME_PARSE 并行，MATCH_EVAL 依赖两者）
 * → report.ready → task.finished。
 */
@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private static final String AGENT_JD = "JD_PARSE";
    private static final String AGENT_RESUME = "RESUME_PARSE";
    private static final String AGENT_MATCH = "MATCH_EVAL";

    public static final List<String> AGENTS = List.of(AGENT_JD, AGENT_RESUME, AGENT_MATCH);

    private final TaskStore store;
    private final MatchService matchService;
    private final DemoCacheService demoCache;
    private final DemoSnapshotService demoSnapshot;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 模型档位标识：参与缓存键，避免不同档位的报告互相命中。 */
    @Value("${offerpilot.match-eval.model-tier:pro}")
    private String modelTier = "pro";

    public TaskService(TaskStore store, MatchService matchService,
                       DemoCacheService demoCache, DemoSnapshotService demoSnapshot) {
        this.store = store;
        this.matchService = matchService;
        this.demoCache = demoCache;
        this.demoSnapshot = demoSnapshot;
    }

    /** 创建并异步启动任务；taskNo 重复提交时返回既有任务（幂等）。 */
    public AnalysisTask submit(String taskNo, String jdText, String resumeText) {
        AnalysisTask task = new AnalysisTask(taskNo, jdText, resumeText,
                TaskStore.now(), System.currentTimeMillis(), AGENTS.size());
        AnalysisTask registered = store.registerIfAbsent(task);
        if (registered != task) {
            return registered;
        }
        executor.submit(() -> run(registered));
        return registered;
    }

    private void run(AnalysisTask task) {
        long start = System.currentTimeMillis();
        store.publish(task, "task.started", Map.of("total_agents", AGENTS.size()));

        // ① 离线演示模式：输入命中已录制快照 → 不调用任何外部 API，直接回放报告与进度序列
        if (demoSnapshot.isEnabled()) {
            Optional<DemoSnapshotService.Snapshot> hit =
                    demoSnapshot.findByInput(task.getJdText(), task.getResumeText());
            if (hit.isPresent()) {
                replaySnapshot(task, hit.get());
                return;
            }
            if (!demoSnapshot.isFallbackToReal()) {
                log.warn("[task] {} 离线模式未命中快照，且已关闭真实调用回退", task.getTaskNo());
                task.setStatus(AnalysisTask.STATUS_FAILED);
                task.setDurationMs(System.currentTimeMillis() - start);
                store.publish(task, "task.finished", Map.of(
                        "status", AnalysisTask.STATUS_FAILED,
                        "error_code", "40901",
                        "message", "离线演示模式未命中快照（已关闭真实调用回退）",
                        "token_total", 0,
                        "duration_ms", task.getDurationMs(),
                        "offline_replay", true));
                return;
            }
            log.warn("[task] {} 离线模式未命中快照，回退真实调用链路", task.getTaskNo());
        }

        // ② 结果缓存：相同 JD+简历（输入摘要 + 模型档位参与缓存键）命中 → 秒级返回已完成报告
        if (demoCache.isEnabled()) {
            Optional<MatchResponse> cached =
                    demoCache.get(task.getJdText(), task.getResumeText(), modelTier);
            if (cached.isPresent()) {
                replayCache(task, cached.get());
                return;
            }
        }

        ProgressListener listener = new ProgressListener() {
            @Override
            public void onAgentStarted(String agentName, String model) {
                task.setCurrentAgent(agentName);
                task.setCurrentAgentStartedAt(System.currentTimeMillis());
                store.publish(task, "agent.started", Map.of(
                        "agent_name", agentName,
                        "model", model == null ? "" : model));
            }

            @Override
            public void onAgentFinished(String agentName, long latencyMs, boolean retried,
                                        int attempts, int totalTokens) {
                task.setFinishedAgents(task.getFinishedAgents() + 1);
                task.setProgress(task.getFinishedAgents() * 100 / AGENTS.size());
                task.setCurrentAgent(null);
                store.publish(task, "agent.finished", Map.of(
                        "agent_name", agentName,
                        "latency_ms", latencyMs,
                        "status", "SUCCEEDED",
                        "retried", retried,
                        "attempts", attempts,
                        "total_tokens", totalTokens));
            }

            @Override
            public void onAgentFailed(String agentName, String errorCode, int retryCount) {
                task.setFailedAgent(agentName);
                task.setCurrentAgent(null);
                store.publish(task, "agent.failed", Map.of(
                        "agent_name", agentName,
                        "error_code", errorCode,
                        "retry_count", retryCount));
            }
        };

        try {
            MatchResponse response = matchService.match(task.getJdText(), task.getResumeText(), listener);
            task.setResult(response);
            task.setTokenTotal(totalTokens(response));
            task.setProgress(100);
            task.setStatus(AnalysisTask.STATUS_SUCCEEDED);
            task.setDurationMs(System.currentTimeMillis() - start);

            if (demoCache.isEnabled()) {
                demoCache.put(task.getJdText(), task.getResumeText(), modelTier, response);
            }

            store.publish(task, "report.ready", Map.of(
                    "overall_score", response.report().overallScore(),
                    "level", response.report().level(),
                    "report_url", "/api/v1/tasks/" + task.getTaskNo() + "/report",
                    "cache_hit", false));
            store.publish(task, "task.finished", Map.of(
                    "status", AnalysisTask.STATUS_SUCCEEDED,
                    "token_total", task.getTokenTotal(),
                    "duration_ms", task.getDurationMs()));
            log.info("[task] {} 完成，用时 {}ms，token {}，总分 {}", task.getTaskNo(),
                    task.getDurationMs(), task.getTokenTotal(), response.report().overallScore());
        } catch (Exception e) {
            task.setStatus(AnalysisTask.STATUS_FAILED);
            task.setDurationMs(System.currentTimeMillis() - start);
            if (task.getFailedAgent() == null) {
                task.setFailedAgent(task.getCurrentAgent());
            }
            log.error("[task] {} 失败: {}", task.getTaskNo(), e.getMessage(), e);
            store.publish(task, "task.finished", Map.of(
                    "status", AnalysisTask.STATUS_FAILED,
                    "error_code", "40501",
                    "message", e.getMessage() == null ? "" : e.getMessage(),
                    "token_total", task.getTokenTotal(),
                    "duration_ms", task.getDurationMs()));
        }
    }

    /**
     * 离线快照回放：事件序列与报告均来自录制内容，全程不调用任何外部 API；
     * 回放结束后任务转入终态（快照缺 task.finished 时兜底补发）。
     */
    private void replaySnapshot(AnalysisTask task, DemoSnapshotService.Snapshot snapshot) {
        log.info("[task] {} 命中离线快照 {}，进入离线回放（不调用外部 API）", task.getTaskNo(), snapshot.caseId());
        long replayMs = demoSnapshot.replay(task, snapshot, store);
        task.setResult(snapshot.response());
        task.setTokenTotal(snapshot.tokenTotal());
        task.setDurationMs(snapshot.durationMs());
        task.setProgress(100);
        task.setStatus(AnalysisTask.STATUS_SUCCEEDED);

        boolean finishedPublished = snapshot.events() != null && snapshot.events().stream()
                .anyMatch(e -> "task.finished".equals(e.name()));
        if (!finishedPublished) {
            store.publish(task, "task.finished", Map.of(
                    "status", AnalysisTask.STATUS_SUCCEEDED,
                    "token_total", task.getTokenTotal(),
                    "duration_ms", task.getDurationMs(),
                    "offline_replay", true));
        }
        log.info("[task] {} 离线回放完成：回放耗时 {}ms（录制时端到端 {}ms，事件 {} 条）",
                task.getTaskNo(), replayMs, snapshot.durationMs(),
                snapshot.events() == null ? 0 : snapshot.events().size());
    }

    /**
     * 缓存命中回放：直接给出已完成报告，并用缓存中各阶段的原始耗时重建时间线事件，
     * 命中路径为毫秒级（不调用外部 API）。
     */
    private void replayCache(AnalysisTask task, MatchResponse response) {
        long start = System.currentTimeMillis();
        MatchResponse.MatchMeta meta = response.meta();
        if (meta != null) {
            publishStageFromMeta(task, AGENT_JD, meta.jdParse());
            publishStageFromMeta(task, AGENT_RESUME, meta.resumeParse());
            publishStageFromMeta(task, AGENT_MATCH, meta.matchEval());
        }
        task.setResult(response);
        task.setTokenTotal(totalTokens(response));
        task.setProgress(100);
        task.setStatus(AnalysisTask.STATUS_SUCCEEDED);
        task.setDurationMs(System.currentTimeMillis() - start);

        store.publish(task, "report.ready", Map.of(
                "overall_score", response.report().overallScore(),
                "level", response.report().level(),
                "report_url", "/api/v1/tasks/" + task.getTaskNo() + "/report",
                "cache_hit", true));
        store.publish(task, "task.finished", Map.of(
                "status", AnalysisTask.STATUS_SUCCEEDED,
                "token_total", task.getTokenTotal(),
                "duration_ms", task.getDurationMs(),
                "cache_hit", true));
        log.info("[task] {} 缓存命中，报告秒级返回，用时 {}ms", task.getTaskNo(), task.getDurationMs());
    }

    /**
     * 用缓存报告的阶段元数据重建 agent.started/finished 事件：
     * latency_ms 记为 0（本次未真实调用），source_latency_ms 承载录制时的真实阶段耗时供前端展示。
     */
    private void publishStageFromMeta(AnalysisTask task, String agentName, MatchResponse.StageMeta meta) {
        store.publish(task, "agent.started", Map.of(
                "agent_name", agentName,
                "model", meta == null || meta.model() == null ? "" : meta.model(),
                "cache_hit", true));
        task.setFinishedAgents(task.getFinishedAgents() + 1);
        task.setProgress(task.getFinishedAgents() * 100 / AGENTS.size());
        store.publish(task, "agent.finished", Map.of(
                "agent_name", agentName,
                "latency_ms", 0L,
                "source_latency_ms", meta == null ? 0L : meta.elapsedMs(),
                "status", "SUCCEEDED",
                "retried", meta != null && meta.retried(),
                "attempts", meta == null ? 1 : meta.attempts(),
                "total_tokens", meta == null ? 0 : meta.totalTokens(),
                "cache_hit", true));
    }

    private static int totalTokens(MatchResponse response) {
        if (response == null || response.meta() == null) {
            return 0;
        }
        return sum(response.meta().jdParse()) + sum(response.meta().resumeParse())
                + sum(response.meta().matchEval());
    }

    private static int sum(MatchResponse.StageMeta meta) {
        return meta == null ? 0 : meta.totalTokens();
    }
}
