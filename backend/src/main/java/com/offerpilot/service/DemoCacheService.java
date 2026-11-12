package com.offerpilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.dto.MatchResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S7 演示结果缓存：相同 JD + 简历输入命中缓存后直接返回已完成报告，命中路径为毫秒级。
 * <p>
 * 设计要点：
 * <ul>
 *     <li>缓存键 = SHA-256(JD 原文摘要 + 简历原文摘要 + 模型档位 + 缓存版本)，避免不同输入误命中；</li>
 *     <li>内存 LRU（accessOrder）+ 磁盘 JSON 落盘，进程重启后仍可复用；</li>
 *     <li>可开关（offerpilot.demo.cache.enabled），关闭后完全不参与调用链路；</li>
 *     <li>只缓存通过校验的完整报告，不缓存失败结果。</li>
 * </ul>
 */
@Component
public class DemoCacheService {

    private static final Logger log = LoggerFactory.getLogger(DemoCacheService.class);

    /** 缓存版本：报告结构或计分逻辑变更时递增，避免命中语义过期的缓存。 */
    public static final String CACHE_VERSION = "s7-v2";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final boolean enabled;
    private final Path dir;
    private final int maxEntries;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 内存缓存：accessOrder=true 实现 LRU 淘汰。 */
    private final Map<String, MatchResponse> memory = new LinkedHashMap<>(16, 0.75f, true);

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong diskStores = new AtomicLong();

    public DemoCacheService(@Value("${offerpilot.demo.cache.enabled:true}") boolean enabled,
                            @Value("${offerpilot.demo.cache.dir:demo-cache}") String dir,
                            @Value("${offerpilot.demo.cache.max-entries:200}") int maxEntries) {
        this.enabled = enabled;
        this.dir = Paths.get(dir).toAbsolutePath().normalize();
        this.maxEntries = Math.max(1, maxEntries);
        if (enabled) {
            try {
                Files.createDirectories(this.dir);
                log.info("[demo-cache] 启用，目录={}，上限={} 条", this.dir, this.maxEntries);
            } catch (IOException e) {
                log.warn("[demo-cache] 缓存目录创建失败：{}", e.getMessage());
            }
        } else {
            log.info("[demo-cache] 未启用");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 缓存键：SHA-256(jd | resume | variant | version)，含输入文本摘要防止误命中。 */
    public String key(String jdText, String resumeText, String variant) {
        String raw = normalize(jdText) + "\u0001" + normalize(resumeText) + "\u0001"
                + (variant == null ? "default" : variant) + "\u0001" + CACHE_VERSION;
        return sha256(raw);
    }

    /** 查缓存：内存 → 磁盘；命中返回已完成报告。 */
    public Optional<MatchResponse> get(String jdText, String resumeText, String variant) {
        if (!enabled) {
            return Optional.empty();
        }
        String key = key(jdText, resumeText, variant);
        synchronized (memory) {
            MatchResponse cached = memory.get(key);
            if (cached != null) {
                hits.incrementAndGet();
                return Optional.of(cached);
            }
        }
        Path file = fileFor(key);
        if (Files.exists(file)) {
            try {
                CacheEntry entry = mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), CacheEntry.class);
                if (entry != null && entry.response() != null && CACHE_VERSION.equals(entry.version())
                        && key.equals(entry.key())) {
                    synchronized (memory) {
                        memory.put(key, entry.response());
                        trim();
                    }
                    hits.incrementAndGet();
                    return Optional.of(entry.response());
                }
            } catch (Exception e) {
                log.warn("[demo-cache] 磁盘缓存读取失败 key={}：{}", key, e.getMessage());
            }
        }
        misses.incrementAndGet();
        return Optional.empty();
    }

    /** 写缓存：内存 + 磁盘落盘（磁盘失败不影响主链路）。 */
    public void put(String jdText, String resumeText, String variant, MatchResponse response) {
        if (!enabled || response == null) {
            return;
        }
        String key = key(jdText, resumeText, variant);
        synchronized (memory) {
            memory.put(key, response);
            trim();
        }
        try {
            CacheEntry entry = new CacheEntry(key, sha256(normalize(jdText)), sha256(normalize(resumeText)),
                    variant == null ? "default" : variant, CACHE_VERSION, now(), response);
            Files.writeString(fileFor(key), mapper.writeValueAsString(entry), StandardCharsets.UTF_8);
            diskStores.incrementAndGet();
        } catch (Exception e) {
            log.warn("[demo-cache] 磁盘缓存写入失败 key={}：{}", key, e.getMessage());
        }
    }

    /** 运行统计（供 /api/v1/demo/status 展示）。 */
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("enabled", enabled);
        stats.put("dir", dir.toString());
        stats.put("version", CACHE_VERSION);
        stats.put("memory_entries", memory.size());
        stats.put("max_entries", maxEntries);
        stats.put("hits", hits.get());
        stats.put("misses", misses.get());
        stats.put("disk_stores", diskStores.get());
        return stats;
    }

    private void trim() {
        Iterator<String> it = memory.keySet().iterator();
        while (memory.size() > maxEntries && it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    private Path fileFor(String key) {
        return dir.resolve(key + ".json");
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

    static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 磁盘缓存条目：含输入摘要与版本，便于校验与人工排查。 */
    public record CacheEntry(
            String key,
            String jdDigest,
            String resumeDigest,
            String variant,
            String version,
            String createdAt,
            MatchResponse response
    ) {
    }
}
