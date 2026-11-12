package com.offerpilot.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 任务注册表 + SSE 事件广播中心（需求说明书 6.4 / 6.5）。
 * <ul>
 *     <li>每个事件分配任务内单调递增的 seq 并写为 SSE id，事件同时落入内存历史；</li>
 *     <li>客户端重连时带 Last-Event-ID，服务端只补发 seq &gt; lastEventId 的事件；</li>
 *     <li>每 15 秒推送 heartbeat（不带 id，避免污染 Last-Event-ID 语义）；</li>
 *     <li>评估 Agent 执行期间每 20 秒推送 agent.progress 心跳，避免用户误判卡死。</li>
 * </ul>
 */
@Component
public class TaskStore {

    private static final Logger log = LoggerFactory.getLogger(TaskStore.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS");
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;
    private static final int MAX_TASKS = 50;

    private final Map<String, AnalysisTask> tasks = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong emitterSeq = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "offerpilot-task-scheduler");
        t.setDaemon(true);
        return t;
    });

    /** 全局心跳间隔（秒）。 */
    @Value("${offerpilot.latency.heartbeat-seconds:15}")
    private long heartbeatSeconds = 15;

    /** 评估 Agent 执行期间的进度心跳间隔（秒）：缩短后可让前端时间线在等待期间保持可见进展。 */
    @Value("${offerpilot.latency.agent-progress-seconds:5}")
    private long agentProgressSeconds = 5;

    @PostConstruct
    void start() {
        long heartbeat = Math.max(1, heartbeatSeconds);
        long progress = Math.max(1, agentProgressSeconds);
        log.info("[task-store] 心跳间隔 {}s，评估进度间隔 {}s", heartbeat, progress);
        scheduler.scheduleAtFixedRate(this::broadcastHeartbeat, heartbeat, heartbeat, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::broadcastAgentProgress, progress, progress, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    // ------------------------------------------------------------------ 任务注册表

    /** 注册新任务；同名任务已存在时返回已存在的实例（幂等，不重复执行）。 */
    public AnalysisTask registerIfAbsent(AnalysisTask task) {
        synchronized (tasks) {
            AnalysisTask exists = tasks.get(task.getTaskNo());
            if (exists != null) {
                return exists;
            }
            tasks.put(task.getTaskNo(), task);
            evictIfNeeded();
            return task;
        }
    }

    public AnalysisTask get(String taskNo) {
        synchronized (tasks) {
            return tasks.get(taskNo);
        }
    }

    public List<AnalysisTask> list() {
        synchronized (tasks) {
            return new ArrayList<>(tasks.values());
        }
    }

    private void evictIfNeeded() {
        while (tasks.size() > MAX_TASKS) {
            String oldest = null;
            for (AnalysisTask t : tasks.values()) {
                if (t.isTerminal()) {
                    oldest = t.getTaskNo();
                    break;
                }
            }
            if (oldest == null) {
                return;
            }
            tasks.remove(oldest);
        }
    }

    // ------------------------------------------------------------------ 事件发布

    /** 发布事件：分配 seq、序列化 data、写入历史并推送给全部在线连接。 */
    public void publish(AnalysisTask task, String name, Map<String, Object> payload) {
        List<SseEmitter> dead = new ArrayList<>();
        synchronized (task) {
            long seq = ++task.seq;
            String ts = now();
            Map<String, Object> data = new LinkedHashMap<>(payload);
            data.put("task_no", task.getTaskNo());
            data.put("seq", seq);
            data.put("ts", ts);
            String json;
            try {
                json = mapper.writeValueAsString(data);
            } catch (Exception e) {
                json = "{}";
            }
            task.events.add(new TaskEvent(seq, name, json, ts));

            for (Map.Entry<Long, SseEmitter> entry : task.emitters.entrySet()) {
                if (!send(entry.getValue(), name, seq, json)) {
                    dead.add(entry.getValue());
                }
            }
            for (SseEmitter d : dead) {
                task.emitters.values().remove(d);
            }
            if ("task.finished".equals(name)) {
                for (SseEmitter emitter : task.emitters.values()) {
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                        // 连接已断开，忽略
                    }
                }
                task.emitters.clear();
            }
        }
        log.debug("[sse] task={} event={} seq={}", task.getTaskNo(), name, task.seq);
    }

    /** 任务的当前事件序号（供状态查询与客户端事件对账使用）。 */
    public long lastSeq(AnalysisTask task) {
        synchronized (task) {
            return task.seq;
        }
    }

    /** 事件历史中 seq &gt; afterSeq 的部分（供 REST 补齐通道使用）。 */
    public List<TaskEvent> eventsAfter(String taskNo, long afterSeq) {
        AnalysisTask task = get(taskNo);
        if (task == null) {
            return List.of();
        }
        synchronized (task) {
            List<TaskEvent> result = new ArrayList<>();
            for (TaskEvent e : task.events) {
                if (e.seq() > afterSeq) {
                    result.add(e);
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ SSE 连接

    /**
     * 绑定一个 SSE 连接：先补发 seq &gt; lastEventId 的历史事件；
     * 任务已结束时补发后立即关闭连接，否则登记为活跃连接继续接收后续事件。
     */
    public boolean attach(String taskNo, long lastEventId, SseEmitter emitter) {
        AnalysisTask task = get(taskNo);
        if (task == null) {
            return false;
        }
        synchronized (task) {
            for (TaskEvent e : task.events) {
                if (e.seq() > lastEventId && !send(emitter, e.name(), e.seq(), e.dataJson())) {
                    emitter.complete();
                    return true;
                }
            }
            if (task.isTerminal()) {
                emitter.complete();
                return true;
            }
            long key = emitterSeq.incrementAndGet();
            task.emitters.put(key, emitter);
            emitter.onCompletion(() -> detach(task, key));
            emitter.onTimeout(() -> {
                detach(task, key);
                emitter.complete();
            });
            emitter.onError(t -> detach(task, key));
        }
        return true;
    }

    private void detach(AnalysisTask task, long key) {
        synchronized (task) {
            task.emitters.remove(key);
        }
    }

    private boolean send(SseEmitter emitter, String name, Long id, String json) {
        try {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            builder.name(name);
            if (id != null) {
                builder.id(String.valueOf(id));
            }
            builder.data(json, MediaType.APPLICATION_JSON);
            emitter.send(builder);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 心跳：不带 id，避免把 Last-Event-ID 推进到非业务事件上。 */
    private void broadcastHeartbeat() {
        String json = "{\"ts\":\"" + now() + "\"}";
        List<AnalysisTask> snapshot = list();
        for (AnalysisTask task : snapshot) {
            if (task.isTerminal()) {
                continue;
            }
            List<SseEmitter> dead = new ArrayList<>();
            synchronized (task) {
                if (task.emitters.isEmpty()) {
                    continue;
                }
                for (SseEmitter emitter : task.emitters.values()) {
                    try {
                        emitter.send(SseEmitter.event().name("heartbeat").data(json, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        dead.add(emitter);
                    }
                }
                for (SseEmitter d : dead) {
                    task.emitters.values().remove(d);
                }
            }
        }
    }

    /** 评估 Agent 长任务心跳（agent.progress）。 */
    private void broadcastAgentProgress() {
        List<AnalysisTask> snapshot = list();
        for (AnalysisTask task : snapshot) {
            if (task.isTerminal() || task.getCurrentAgent() == null) {
                continue;
            }
            long elapsed = System.currentTimeMillis() - task.getCurrentAgentStartedAt();
            publish(task, "agent.progress", Map.of(
                    "agent_name", task.getCurrentAgent(),
                    "elapsed_ms", elapsed));
        }
    }

    public SseEmitter newEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }

    public static String now() {
        return LocalDateTime.now().format(TS);
    }
}
