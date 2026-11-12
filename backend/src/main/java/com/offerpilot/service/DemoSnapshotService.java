package com.offerpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.dto.MatchResponse;
import com.offerpilot.task.AnalysisTask;
import com.offerpilot.task.TaskEvent;
import com.offerpilot.task.TaskStore;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * S7 演示快照服务：录制真实任务的完整 SSE 事件序列 + 报告，离线时无需调用外部 API 即可回放。
 * <p>
 * 快照内容：输入摘要（JD / 简历）、模型、端到端耗时、token 总量、完整报告、全部 SSE 事件（含相对时间偏移）。
 * 回放时按原始事件顺序与压缩后的时间节奏重新发布事件，并直接给出报告，实现「断网也能完整演示」。
 */
@Component
public class DemoSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(DemoSnapshotService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final boolean enabled;
    private final Path dir;
    private final double speed;
    private final long maxGapMs;
    private final boolean fallbackToReal;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 输入摘要 → 快照 索引（懒加载）。 */
    private final Map<String, Snapshot> index = new ConcurrentHashMap<>();
    private volatile boolean indexLoaded = false;

    public DemoSnapshotService(@Value("${offerpilot.demo.snapshot.enabled:false}") boolean enabled,
                               @Value("${offerpilot.demo.snapshot.dir:demo-snapshots}") String dir,
                               @Value("${offerpilot.demo.snapshot.speed:0.05}") double speed,
                               @Value("${offerpilot.demo.snapshot.max-gap-ms:1200}") long maxGapMs,
                               @Value("${offerpilot.demo.snapshot.fallback-to-real:true}") boolean fallbackToReal) {
        this.enabled = enabled;
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        this.speed = speed <= 0 ? 1.0 : speed;
        this.maxGapMs = maxGapMs <= 0 ? 1200L : maxGapMs;
        this.fallbackToReal = fallbackToReal;
        if (enabled) {
            try {
                Files.createDirectories(this.dir);
            } catch (Exception e) {
                log.warn("[demo-snapshot] 快照目录创建失败：{}", e.getMessage());
            }
        }
        log.info("[demo-snapshot] enabled={} dir={} speed={} fallbackToReal={}", enabled, this.dir, this.speed,
                fallbackToReal);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isFallbackToReal() {
        return fallbackToReal;
    }

    public Path dir() {
        return dir;
    }

    public double speed() {
        return speed;
    }

    /** 按输入文本摘要查找快照。 */
    public Optional<Snapshot> findByInput(String jdText, String resumeText) {
        reloadIndexIfNeeded();
        String key = inputKey(jdText, resumeText);
        Snapshot hit = index.get(key);
        if (hit != null) {
            return Optional.of(hit);
        }
        // 摘要不一致时退回逐条摘要比对（例如快照由不同空白格式录制）
        return index.values().stream()
                .filter(s -> DemoCacheService.sha256(normalize(jdText)).equals(s.jdDigest())
                        && DemoCacheService.sha256(normalize(resumeText)).equals(s.resumeDigest()))
                .findFirst();
    }

    public Optional<Snapshot> byCaseId(String caseId) {
        reloadIndexIfNeeded();
        return index.values().stream().filter(s -> s.caseId().equalsIgnoreCase(caseId)).findFirst();
    }

    /** 录制快照：由已完成任务的完整报告 + SSE 事件历史生成，落盘为 JSON。 */
    public Snapshot record(String caseId, String jdText, String resumeText, MatchResponse response,
                           List<TaskEvent> events, long durationMs, int tokenTotal) {
        List<EventRec> recs = new ArrayList<>();
        long firstTs = firstTs(events);
        for (TaskEvent e : events) {
            Map<String, Object> payload = readMap(e.dataJson());
            payload.remove("task_no");
            payload.remove("seq");
            payload.remove("ts");
            long offset = Math.max(0L, parseTs(e.ts()) - firstTs);
            try {
                recs.add(new EventRec(e.name(), mapper.writeValueAsString(payload), offset));
            } catch (Exception ex) {
                log.warn("[demo-snapshot] 事件序列化失败：{}", ex.getMessage());
            }
        }
        Snapshot snapshot = new Snapshot(caseId, DemoCacheService.sha256(normalize(jdText)),
                DemoCacheService.sha256(normalize(resumeText)), now(),
                response == null || response.meta() == null ? "" : response.meta().matchModel(),
                durationMs, tokenTotal, response, recs);
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(caseId + ".json");
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(snapshot),
                    StandardCharsets.UTF_8);
            index.put(inputKey(jdText, resumeText), snapshot);
            log.info("[demo-snapshot] 已录制快照 {} → {}（事件 {} 条，耗时 {}ms）", caseId, file, recs.size(), durationMs);
        } catch (Exception e) {
            throw new IllegalStateException("快照写入失败：" + e.getMessage(), e);
        }
        return snapshot;
    }

    /**
     * 离线回放：按录制的事件顺序重新发布事件（时间节奏按 speed 压缩），
     * 期间不进行任何外部 API 调用。
     */
    public long replay(AnalysisTask task, Snapshot snapshot, TaskStore store) {
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
            if (task.getResult() == null && "report.ready".equals(ev.name())) {
                task.setResult(snapshot.response());
            }
            store.publish(task, ev.name(), payload);
        }
        return System.currentTimeMillis() - start;
    }

    /** 快照清单（供 /api/v1/demo/status 展示）。 */
    public List<Map<String, Object>> listSnapshots() {
        reloadIndexIfNeeded();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Snapshot s : index.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("case_id", s.caseId());
            item.put("recorded_at", s.createdAt());
            item.put("match_model", s.matchModel());
            item.put("duration_ms", s.durationMs());
            item.put("token_total", s.tokenTotal());
            item.put("event_count", s.events() == null ? 0 : s.events().size());
            item.put("overall_score", s.response() == null || s.response().report() == null
                    ? null : s.response().report().overallScore());
            item.put("level", s.response() == null || s.response().report() == null
                    ? null : s.response().report().level());
            item.put("file", dir.resolve(s.caseId() + ".json").toString());
            list.add(item);
        }
        return list;
    }

    /** 扫描快照目录，重建索引。 */
    public synchronized void reloadIndex() {
        index.clear();
        try {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                        try {
                            Snapshot s = mapper.readValue(Files.readString(f, StandardCharsets.UTF_8), Snapshot.class);
                            if (s != null && s.response() != null) {
                                index.put(s.jdDigest() + "|" + s.resumeDigest(), s);
                            }
                        } catch (Exception e) {
                            log.warn("[demo-snapshot] 跳过无法解析的快照 {}：{}", f, e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[demo-snapshot] 快照索引构建失败：{}", e.getMessage());
        }
        indexLoaded = true;
        log.info("[demo-snapshot] 索引重建完成，共 {} 个快照", index.size());
    }

    private void reloadIndexIfNeeded() {
        if (!indexLoaded) {
            reloadIndex();
        }
    }

    private static String inputKey(String jdText, String resumeText) {
        return DemoCacheService.sha256(normalize(jdText)) + "|" + DemoCacheService.sha256(normalize(resumeText));
    }

    private static long firstTs(List<TaskEvent> events) {
        if (events == null || events.isEmpty()) {
            return 0L;
        }
        return parseTs(events.get(0).ts());
    }

    private static long parseTs(String ts) {
        try {
            return LocalDateTime.parse(ts).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private Map<String, Object> readMap(String json) {
        try {
            Map<String, Object> map = mapper.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            return map == null ? new LinkedHashMap<>() : new LinkedHashMap<>(map);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static String now() {
        return LocalDateTime.now().format(TS);
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    /** 一条回放事件：事件名 + 载荷（已剔除 task_no/seq/ts）+ 相对首事件的毫秒偏移。 */
    public record EventRec(String name, String payloadJson, long offsetMs) {
    }

    /** 演示快照：输入摘要 + 报告 + 完整事件序列。 */
    public record Snapshot(
            String caseId,
            String jdDigest,
            String resumeDigest,
            String createdAt,
            String matchModel,
            long durationMs,
            int tokenTotal,
            MatchResponse response,
            List<EventRec> events
    ) {
    }
}
