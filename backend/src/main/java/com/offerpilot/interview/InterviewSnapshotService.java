package com.offerpilot.interview;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * S6 面试链路快照服务（需求说明书 9 章 S6：断网重放模式 + 快照录制）。
 * <p>
 * 快照内容：完整逐轮问答（问题、考察维度、证据引用、回答、评分）、汇总评分、总结 Markdown，
 * 以及面试会话的完整 SSE 事件序列（含相对首事件的毫秒偏移）。
 * 回放时按压缩后的时间节奏重新发布事件，全程不调用任何外部模型接口。
 * 快照文件命名 {@code interview-{caseId}.json}，与 S7 报告快照同目录且互不干扰。
 */
@Component
public class InterviewSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(InterviewSnapshotService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SCHEMA = "offerpilot.interview.snapshot/v1";
    private static final String PREFIX = "interview-";

    private final Path dir;
    private final double speed;
    private final long maxGapMs;
    private final long tickMaxGapMs;
    private final ObjectMapper mapper = new ObjectMapper();

    public InterviewSnapshotService(@Value("${offerpilot.interview.snapshot-dir:demo-snapshots}") String dir,
                                    @Value("${offerpilot.interview.replay-speed:0.05}") double speed,
                                    @Value("${offerpilot.interview.replay-max-gap-ms:1200}") long maxGapMs,
                                    @Value("${offerpilot.interview.replay-tick-max-gap-ms:1500}") long tickMaxGapMs) {
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        this.speed = speed <= 0 ? 1.0 : speed;
        this.maxGapMs = maxGapMs <= 0 ? 1200L : maxGapMs;
        this.tickMaxGapMs = tickMaxGapMs <= 0 ? 1500L : tickMaxGapMs;
        try {
            Files.createDirectories(this.dir);
        } catch (Exception e) {
            log.warn("[interview-snapshot] 快照目录创建失败：{}", e.getMessage());
        }
        log.info("[interview-snapshot] dir={} speed={} maxGapMs={}", this.dir, this.speed, this.maxGapMs);
    }

    public Path dir() {
        return dir;
    }

    public double speed() {
        return speed;
    }

    /** 录制：把已完成（或进行中）的面试会话落盘为离线快照。 */
    public Snapshot record(InterviewSession session, String caseId) {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalStateException("录制面试快照需要 caseId");
        }
        List<EventRec> events = new ArrayList<>();
        long firstTs = firstTs(session);
        synchronized (session) {
            for (com.offerpilot.task.TaskEvent e : session.getEvents()) {
                Map<String, Object> payload = readMap(e.dataJson());
                payload.remove("task_no");
                payload.remove("session_id");
                payload.remove("seq");
                payload.remove("ts");
                long offset = Math.max(0L, parseTs(e.ts()) - firstTs);
                try {
                    events.add(new EventRec(e.name(), mapper.writeValueAsString(payload), offset));
                } catch (Exception ex) {
                    log.warn("[interview-snapshot] 事件序列化失败：{}", ex.getMessage());
                }
            }
        }
        Snapshot snapshot = new Snapshot(SCHEMA, caseId, session.getTaskNo(), now(), session.getMaxRound(),
                session.getRoundNo(), new ArrayList<>(session.getTranscript()), session.getScore(),
                session.getSummaryMd(), session.getStatus(), events);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName(caseId));
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot),
                    StandardCharsets.UTF_8);
            log.info("[interview-snapshot] 已录制面试快照 {} → {}（轮次 {}，事件 {} 条）", caseId, file,
                    snapshot.roundCount(), events.size());
        } catch (Exception e) {
            throw new IllegalStateException("面试快照写入失败：" + e.getMessage(), e);
        }
        return snapshot;
    }

    /** 快照清单（供 A14 GET /replays 展示）。 */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Snapshot s : loadAll()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("snapshot_id", s.caseId());
            item.put("kind", "interview");
            item.put("name", "模拟面试演示快照 " + s.caseId());
            item.put("task_no", s.taskNo());
            item.put("recorded_at", s.recordedAt());
            item.put("max_round", s.maxRound());
            item.put("round_count", s.roundCount());
            item.put("status", s.status());
            item.put("avg_score", averageOf(s));
            item.put("has_summary", s.summaryMd() != null && !s.summaryMd().isBlank());
            item.put("event_count", s.events() == null ? 0 : s.events().size());
            item.put("replay_speed", speed);
            item.put("stream", "/api/v1/interviews/" + s.caseId() + "/stream");
            item.put("file", dir.resolve(fileName(s.caseId())).toString());
            result.add(item);
        }
        return result;
    }

    public Optional<Snapshot> load(String snapshotId) {
        Path file = dir.resolve(fileName(snapshotId));
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            Snapshot s = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
            return Optional.ofNullable(s);
        } catch (Exception e) {
            log.warn("[interview-snapshot] 快照解析失败 {}：{}", file, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 离线回放：按录制的事件顺序重新发布事件（时间节奏按 speed 压缩），全程不调用外部 API。
     *
     * @return 回放总耗时（毫秒）
     */
    public long replay(InterviewSession session, Snapshot snapshot, InterviewSessionStore store) {
        long start = System.currentTimeMillis();
        List<EventRec> events = snapshot.events() == null ? List.of() : snapshot.events();
        long prevOffset = 0;
        for (EventRec ev : events) {
            long delta = (long) ((ev.offsetMs() - prevOffset) * speed);
            prevOffset = ev.offsetMs();
            if (delta > 0) {
                try {
                    Thread.sleep(Math.min(delta, maxGapMs));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            Map<String, Object> payload = readMap(ev.payloadJson());
            payload.put("offline_replay", true);
            store.publish(session, ev.name(), payload);
        }
        long elapsed = System.currentTimeMillis() - start;
        log.info("[interview-snapshot] 离线回放完成 {}（{} 事件，耗时 {}ms）", snapshot.caseId(), events.size(), elapsed);
        return elapsed;
    }

    /** 回放节奏：单事件最大间隔（毫秒）。 */
    public long tickMaxGapMs() {
        return tickMaxGapMs;
    }

    // ------------------------------------------------------------------ 内部工具

    private List<Snapshot> loadAll() {
        List<Snapshot> result = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                return result;
            }
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(PREFIX) && name.endsWith(".json");
                }).toList()) {
                    try {
                        Snapshot s = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), Snapshot.class);
                        if (s != null) {
                            result.add(s);
                        }
                    } catch (Exception e) {
                        log.warn("[interview-snapshot] 跳过无法解析的快照 {}：{}", f, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[interview-snapshot] 快照清单读取失败：{}", e.getMessage());
        }
        return result;
    }

    private static Object averageOf(Snapshot s) {
        if (s.transcript() == null || s.transcript().isEmpty()) {
            return null;
        }
        double sum = 0;
        int count = 0;
        for (InterviewTurn turn : s.transcript()) {
            if (turn.getScore() != null) {
                sum += turn.getScore();
                count++;
            }
        }
        return count == 0 ? null : Math.round(sum / count * 10) / 10.0;
    }

    private Map<String, Object> readMap(String json) {
        try {
            Map<String, Object> map = mapper.readValue(json, new TypeReference<>() {
            });
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String fileName(String caseId) {
        return PREFIX + caseId + ".json";
    }

    private static long firstTs(InterviewSession session) {
        synchronized (session) {
            return session.getEvents().isEmpty() ? 0L : parseTs(session.getEvents().get(0).ts());
        }
    }

    private static long parseTs(String ts) {
        try {
            return LocalDateTime.parse(ts).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** 一条回放事件：事件名 + 载荷（已剔除会话元字段）+ 相对首事件的毫秒偏移。 */
    public record EventRec(String name, String payloadJson, long offsetMs) {
    }

    /** 面试快照：会话元信息 + 完整逐轮问答 + 汇总评分 + 总结 + 事件序列。 */
    public record Snapshot(
            String schema,
            String caseId,
            String taskNo,
            String recordedAt,
            int maxRound,
            int roundCount,
            List<InterviewTurn> transcript,
            Map<String, Object> score,
            String summaryMd,
            String status,
            List<EventRec> events
    ) {
    }
}
