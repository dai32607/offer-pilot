package com.offerpilot.controller;

import com.offerpilot.common.ApiResponse;
import com.offerpilot.common.ErrorCode;
import com.offerpilot.service.DemoCacheService;
import com.offerpilot.service.DemoSnapshotService;
import com.offerpilot.task.AnalysisTask;
import com.offerpilot.task.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * S7 演示时延治理接口（缓存与离线快照）：
 * <ul>
 *     <li>GET  /api/v1/demo/status：缓存与快照的运行状态、命中统计、快照清单；</li>
 *     <li>POST /api/v1/demo/snapshots/record：把已完成任务的真实运行结果录制为离线快照；</li>
 *     <li>GET  /api/v1/demo/snapshots：快照清单；</li>
 *     <li>POST /api/v1/demo/snapshots/reload：重扫快照目录（无需重启进程）。</li>
 * </ul>
 * 全部为只读或幂等的演示辅助接口，不改变真实调用链路的默认行为。
 */
@RestController
@RequestMapping("/api/v1/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoCacheService cache;
    private final DemoSnapshotService snapshots;
    private final TaskStore store;

    public DemoController(DemoCacheService cache, DemoSnapshotService snapshots, TaskStore store) {
        this.cache = cache;
        this.snapshots = snapshots;
        this.store = store;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cache", cache.stats());
        Map<String, Object> snapshotInfo = new LinkedHashMap<>();
        snapshotInfo.put("enabled", snapshots.isEnabled());
        snapshotInfo.put("dir", snapshots.dir().toString());
        snapshotInfo.put("speed", snapshots.speed());
        snapshotInfo.put("fallback_to_real", snapshots.isFallbackToReal());
        body.put("snapshot", snapshotInfo);
        body.put("snapshots", snapshots.listSnapshots());
        return ApiResponse.success(body);
    }

    @GetMapping("/snapshots")
    public ApiResponse<List<Map<String, Object>>> snapshots() {
        return ApiResponse.success(snapshots.listSnapshots());
    }

    /** 把已完成任务（真实跑通）的报告与完整 SSE 事件序列录制为离线快照。 */
    @PostMapping("/snapshots/record")
    public ApiResponse<Map<String, Object>> record(@RequestBody Map<String, String> body) {
        String taskNo = body == null ? null : body.get("taskNo");
        String caseId = body == null ? null : body.get("caseId");
        if (taskNo == null || taskNo.isBlank() || caseId == null || caseId.isBlank()) {
            return ApiResponse.failure(ErrorCode.PARAM_INVALID.getCode(), "caseId 与 taskNo 均为必填");
        }
        AnalysisTask task = store.get(taskNo);
        if (task == null) {
            return ApiResponse.failure(ErrorCode.NOT_FOUND.getCode(), "任务不存在：" + taskNo);
        }
        if (!task.isTerminal() || task.getResult() == null) {
            return ApiResponse.failure(ErrorCode.TASK_RUNNING.getCode(),
                    "任务尚未产出完整报告，当前状态：" + task.getStatus());
        }

        DemoSnapshotService.Snapshot snapshot = snapshots.record(caseId, task.getJdText(), task.getResumeText(),
                task.getResult(), store.eventsAfter(taskNo, 0), task.getDurationMs(), task.getTokenTotal());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("case_id", snapshot.caseId());
        result.put("event_count", snapshot.events() == null ? 0 : snapshot.events().size());
        result.put("duration_ms", snapshot.durationMs());
        result.put("token_total", snapshot.tokenTotal());
        result.put("file", snapshots.dir().resolve(snapshot.caseId() + ".json").toString());
        log.info("[demo] 录制快照 {} ← 任务 {}（事件 {} 条）", caseId, taskNo, result.get("event_count"));
        return ApiResponse.success(result);
    }

    @PostMapping("/snapshots/reload")
    public ApiResponse<Map<String, Object>> reload() {
        snapshots.reloadIndex();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshots", snapshots.listSnapshots());
        return ApiResponse.success(result);
    }
}
