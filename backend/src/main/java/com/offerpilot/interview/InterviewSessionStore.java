package com.offerpilot.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.task.TaskEvent;
import com.offerpilot.task.TaskStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 模拟面试会话注册表 + SSE 广播中心（需求说明书 6.4 / 6.5）。
 * 与 {@link com.offerpilot.task.TaskStore} 同构：每个事件分配会话内单调递增 seq 并写为 SSE id，
 * 客户端重连带 Last-Event-ID 时只补发 seq &gt; lastEventId 的事件；等待 Agent 期间推送
 * heartbeat / interview.progress，保证面试页在长等待时仍有可见进展。
 */
@Component
public class InterviewSessionStore {

    private static final Logger log = LoggerFactory.getLogger(InterviewSessionStore.class);
    private static final long SSE_TIMEOUT_MS = 10 * 60 * 1000L;
    private static final int MAX_SESSIONS = 50;

    private final Map<Long, InterviewSession> sessions = new LinkedHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong idSeq = new AtomicLong();
    private final AtomicLong emitterSeq = new AtomicLong();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "offerpilot-interview-scheduler");
        t.setDaemon(true);
        return t;
    });

    @Value("${offerpilot.latency.heartbeat-seconds:5}")
    private long heartbeatSeconds = 5;

    /** 会话持久化目录（相对后端工作目录），重启自动恢复，不引入外部存储依赖。 */
    @Value("${offerpilot.interview.session-dir:data/session}")
    private String sessionDir;

    private Path persistDir;

    @PostConstruct
    void start() {
        persistDir = Path.of(sessionDir).toAbsolutePath().normalize();
        loadPersisted();
        long heartbeat = Math.max(1, heartbeatSeconds);
        scheduler.scheduleAtFixedRate(this::broadcastHeartbeat, heartbeat, heartbeat, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::broadcastAgentProgress, heartbeat, heartbeat, TimeUnit.SECONDS);
        log.info("[interview-store] 心跳间隔 {}s，会话持久化目录 {}", heartbeat, persistDir);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
        // 优雅停机兜底：把内存中的全部会话再落盘一次，避免停机瞬间的最后事件丢失
        List<InterviewSession> all = list();
        for (InterviewSession session : all) {
            persist(session);
        }
        log.info("[interview-store] 停止，已兜底落盘 {} 个会话", all.size());
    }

    // ------------------------------------------------------------------ 会话持久化（文件 Jackson）

    /** 启动时从 data/session 恢复历史会话；idSeq 前移到已恢复的最大 id，避免新会话 id 冲突。 */
    private void loadPersisted() {
        try {
            Files.createDirectories(persistDir);
            long maxId = idSeq.get();
            List<InterviewSession> restored = new ArrayList<>();
            try (Stream<Path> files = Files.list(persistDir)) {
                for (Path file : files
                        .filter(p -> p.getFileName().toString().startsWith("session-")
                                && p.getFileName().toString().endsWith(".json"))
                        .toList()) {
                    try {
                        InterviewSessionRecord record = mapper.readValue(file.toFile(), InterviewSessionRecord.class);
                        InterviewSession session = InterviewSession.fromRecord(record);
                        restored.add(session);
                        maxId = Math.max(maxId, session.getId());
                    } catch (Exception e) {
                        log.warn("[interview-store] 会话文件恢复失败，跳过 {}：{}", file.getFileName(), e.getMessage());
                    }
                }
            }
            if (!restored.isEmpty()) {
                synchronized (sessions) {
                    restored.forEach(s -> sessions.put(s.getId(), s));
                }
                idSeq.set(maxId);
                log.info("[interview-store] 已从 {} 恢复 {} 个会话（最新 id={}）", persistDir, restored.size(), maxId);
            }
        } catch (Exception e) {
            log.warn("[interview-store] 会话持久化目录初始化失败，跳过恢复：{}", e.getMessage());
        }
    }

    /** 会话落盘（临时文件 + 原子移动）：业务字段快照到 data/session/session-{id}.json。 */
    public void persist(InterviewSession session) {
        try {
            if (persistDir == null || session == null) {
                return;
            }
            Files.createDirectories(persistDir);
            Path target = persistDir.resolve("session-" + session.getId() + ".json");
            Path tmp = persistDir.resolve("session-" + session.getId() + ".json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), session.toRecord());
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.warn("[interview-store] 会话 {} 落盘失败：{}", session.getId(), e.getMessage());
        }
    }

    private void removePersisted(long sessionId) {
        try {
            if (persistDir == null) {
                return;
            }
            Files.deleteIfExists(persistDir.resolve("session-" + sessionId + ".json"));
        } catch (Exception e) {
            log.warn("[interview-store] 删除会话文件失败 session={}：{}", sessionId, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ 会话注册表

    public InterviewSession create(String taskNo, int maxRound, boolean replayMode, String replaySnapshotId) {
        InterviewSession session = new InterviewSession(idSeq.incrementAndGet(), taskNo, maxRound, replayMode,
                replaySnapshotId);
        synchronized (sessions) {
            sessions.put(session.getId(), session);
            evictIfNeeded();
        }
        persist(session);
        return session;
    }

    public InterviewSession get(long id) {
        synchronized (sessions) {
            return sessions.get(id);
        }
    }

    public List<InterviewSession> list() {
        synchronized (sessions) {
            return new ArrayList<>(sessions.values());
        }
    }

    private void evictIfNeeded() {
        while (sessions.size() > MAX_SESSIONS) {
            Long oldest = null;
            for (InterviewSession s : sessions.values()) {
                if (s.isTerminal()) {
                    oldest = s.getId();
                    break;
                }
            }
            if (oldest == null) {
                return;
            }
            sessions.remove(oldest);
            removePersisted(oldest);
        }
    }

    // ------------------------------------------------------------------ 事件发布

    /** 发布事件：分配 seq、写入历史并推送给全部在线连接。 */
    public void publish(InterviewSession session, String name, Map<String, Object> payload) {
        List<SseEmitter> dead = new ArrayList<>();
        synchronized (session) {
            long seq = session.nextSeq();
            String ts = TaskStore.now();
            Map<String, Object> data = new LinkedHashMap<>(payload);
            data.put("session_id", session.getId());
            data.put("task_no", session.getTaskNo());
            data.put("seq", seq);
            data.put("ts", ts);
            String json;
            try {
                json = mapper.writeValueAsString(data);
            } catch (Exception e) {
                json = "{}";
            }
            session.getEvents().add(new TaskEvent(seq, name, json, ts));

            for (Map.Entry<Long, SseEmitter> entry : session.getEmitters().entrySet()) {
                if (!send(entry.getValue(), name, seq, json)) {
                    dead.add(entry.getValue());
                }
            }
            for (SseEmitter d : dead) {
                session.getEmitters().values().remove(d);
            }
        }
        persist(session);
        log.debug("[interview-sse] session={} event={} seq={}", session.getId(), name, session.getSeq());
    }

    /** 会话当前事件序号。 */
    public long lastSeq(InterviewSession session) {
        synchronized (session) {
            return session.getSeq();
        }
    }

    /** 事件历史中 seq &gt; afterSeq 的部分（供 REST 补齐通道使用）。 */
    public List<TaskEvent> eventsAfter(long sessionId, long afterSeq) {
        InterviewSession session = get(sessionId);
        if (session == null) {
            return List.of();
        }
        synchronized (session) {
            List<TaskEvent> result = new ArrayList<>();
            for (TaskEvent e : session.getEvents()) {
                if (e.seq() > afterSeq) {
                    result.add(e);
                }
            }
            return result;
        }
    }

    // ------------------------------------------------------------------ SSE 连接

    /**
     * 绑定 SSE 连接：先补发 seq &gt; lastEventId 的历史事件；会话已结束时补发后立即关闭，
     * 否则登记为活跃连接继续接收后续事件。
     * 离线回放会话（replayMode）不补发历史，交由 {@link #markReplayPending} 标记后由回放线程推送，
     * 以保证「离线回放」有可见的时间线节奏。
     */
    public boolean attach(long sessionId, long lastEventId, SseEmitter emitter) {
        InterviewSession session = get(sessionId);
        if (session == null) {
            return false;
        }
        synchronized (session) {
            if (!session.isReplayMode()) {
                for (TaskEvent e : session.getEvents()) {
                    if (e.seq() > lastEventId && !send(emitter, e.name(), e.seq(), e.dataJson())) {
                        emitter.complete();
                        return true;
                    }
                }
            }
            if (session.isTerminal() && !session.isReplayMode()) {
                emitter.complete();
                return true;
            }
            long key = emitterSeq.incrementAndGet();
            session.getEmitters().put(key, emitter);
            emitter.onCompletion(() -> detach(session, key));
            emitter.onTimeout(() -> {
                detach(session, key);
                emitter.complete();
            });
            emitter.onError(t -> detach(session, key));
        }
        return true;
    }

    private void detach(InterviewSession session, long key) {
        synchronized (session) {
            session.getEmitters().remove(key);
        }
    }

    /** 结束全部在线连接（离线回放播完后调用，避免前端 EventSource 长期挂起）。 */
    public void completeEmitters(InterviewSession session) {
        List<SseEmitter> all;
        synchronized (session) {
            all = new ArrayList<>(session.getEmitters().values());
            session.getEmitters().clear();
        }
        for (SseEmitter emitter : all) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 连接可能已被客户端关闭，忽略
            }
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
        String json = "{\"ts\":\"" + TaskStore.now() + "\"}";
        for (InterviewSession session : list()) {
            if (session.isTerminal()) {
                continue;
            }
            List<SseEmitter> dead = new ArrayList<>();
            synchronized (session) {
                if (session.getEmitters().isEmpty()) {
                    continue;
                }
                for (SseEmitter emitter : session.getEmitters().values()) {
                    try {
                        emitter.send(SseEmitter.event().name("heartbeat").data(json, MediaType.APPLICATION_JSON));
                    } catch (Exception e) {
                        dead.add(emitter);
                    }
                }
                for (SseEmitter d : dead) {
                    session.getEmitters().values().remove(d);
                }
            }
        }
    }

    /** 面试官 Agent 长任务心跳（interview.progress）。 */
    private void broadcastAgentProgress() {
        for (InterviewSession session : list()) {
            if (session.isTerminal() || session.getRunningAgent() == null) {
                continue;
            }
            long elapsed = System.currentTimeMillis() - session.getRunningSince();
            publish(session, "interview.progress", Map.of(
                    "agent_name", session.getRunningAgent(),
                    "elapsed_ms", elapsed,
                    "round_no", session.getRoundNo()));
        }
    }

    public SseEmitter newEmitter() {
        return new SseEmitter(SSE_TIMEOUT_MS);
    }
}
