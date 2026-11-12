package com.offerpilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.ApiResponse;
import com.offerpilot.common.ErrorCode;
import com.offerpilot.dto.InterviewAnswerRequest;
import com.offerpilot.dto.InterviewStartRequest;
import com.offerpilot.interview.InterviewService;
import com.offerpilot.interview.InterviewSessionStore;
import com.offerpilot.task.TaskEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模拟面试会话接口（需求说明书 6.2 第 6 章接口表 A10–A15）：
 * <ul>
 *     <li>A10 POST /api/v1/tasks/{taskNo}/interviews：开启面试，返回 session_id 与首轮问题；</li>
 *     <li>A11 POST /api/v1/interviews/{sessionId}/messages：提交回答，返回上一轮评分与下一轮问题；</li>
 *     <li>A12 GET  /api/v1/interviews/{sessionId}：会话详情（transcript / score / status）；</li>
 *     <li>A13 POST /api/v1/interviews/{sessionId}/finish：结束面试并出总结；</li>
 *     <li>GET  /api/v1/interviews/{sessionId}/stream：面试事件流（含 Last-Event-ID 断线补齐）；</li>
 *     <li>GET  /api/v1/interviews/{sessionId}/events：事件历史（REST 补齐通道）；</li>
 *     <li>POST /api/v1/interviews/{sessionId}/snapshot：把会话录制为离线演示快照；</li>
 *     <li>A14 GET  /api/v1/replays：演示快照列表；</li>
 *     <li>A15 POST /api/v1/replays/{snapshotId}/load：加载快照进入离线重放模式。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class InterviewController {

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);

    private final InterviewService interviews;
    private final InterviewSessionStore store;
    private final ObjectMapper mapper;

    public InterviewController(InterviewService interviews, InterviewSessionStore store, ObjectMapper mapper) {
        this.interviews = interviews;
        this.store = store;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ A10 开启面试

    @PostMapping("/tasks/{taskNo}/interviews")
    public ApiResponse<Map<String, Object>> start(@PathVariable String taskNo,
                                                  @RequestBody(required = false) InterviewStartRequest request) {
        InterviewStartRequest req = request == null ? new InterviewStartRequest(null, null, null) : request;
        return ApiResponse.success(interviews.start(taskNo, req.maxRound(), req.focusDimension(), req.caseId()));
    }

    // ------------------------------------------------------------------ A11 提交回答

    @PostMapping("/interviews/{sessionId}/messages")
    public ApiResponse<Map<String, Object>> answer(@PathVariable long sessionId,
                                                   @RequestBody(required = false) InterviewAnswerRequest request) {
        String text = request == null ? null : request.answer();
        return ApiResponse.success(interviews.answer(sessionId, text));
    }

    // ------------------------------------------------------------------ A12 会话详情

    @GetMapping("/interviews/{sessionId}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long sessionId) {
        return ApiResponse.success(interviews.detail(sessionId));
    }

    // ------------------------------------------------------------------ A13 结束面试

    @PostMapping("/interviews/{sessionId}/finish")
    public ApiResponse<Map<String, Object>> finish(@PathVariable long sessionId) {
        return ApiResponse.success(interviews.finish(sessionId));
    }

    // ------------------------------------------------------------------ 会话事件流（SSE + REST 补齐）

    @GetMapping(value = "/interviews/{sessionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable long sessionId,
                                             @RequestHeader(value = "Last-Event-ID", required = false)
                                             String lastEventId) {
        long last = parseSeq(lastEventId);
        SseEmitter emitter = store.newEmitter();
        if (!store.attach(sessionId, last, emitter)) {
            log.warn("[interview-sse] 会话不存在，拒绝建流: {}", sessionId);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(emitter);
    }

    @GetMapping("/interviews/{sessionId}/events")
    public ApiResponse<List<Map<String, Object>>> events(@PathVariable long sessionId,
                                                         @RequestParam(defaultValue = "0") long after) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskEvent event : store.eventsAfter(sessionId, after)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq());
            item.put("event", event.name());
            item.put("ts", event.ts());
            item.put("data", readData(event.dataJson()));
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    // ------------------------------------------------------------------ S6 快照录制与断网重放

    /** 把指定会话录制为离线演示快照（body 可选 {case_id}）。 */
    @PostMapping("/interviews/{sessionId}/snapshot")
    public ApiResponse<Map<String, Object>> snapshot(@PathVariable long sessionId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        String caseId = body == null || body.get("case_id") == null ? null : String.valueOf(body.get("case_id"));
        return ApiResponse.success(interviews.recordSnapshot(sessionId, caseId));
    }

    /** A14 快照列表：面试快照与报告快照统一由 /replays 暴露。 */
    @GetMapping("/replays")
    public ApiResponse<List<Map<String, Object>>> replays() {
        return ApiResponse.success(interviews.listSnapshots());
    }

    /** A15 加载快照进入重放模式。 */
    @PostMapping("/replays/{snapshotId}/load")
    public ApiResponse<Map<String, Object>> loadReplay(@PathVariable String snapshotId) {
        return ApiResponse.success(interviews.loadReplay(snapshotId));
    }

    // ------------------------------------------------------------------ 异常与工具

    @ExceptionHandler(InterviewService.InterviewException.class)
    public ApiResponse<Void> handleInterview(InterviewService.InterviewException e) {
        return ApiResponse.failure(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("[interview] 接口异常", e);
        return ApiResponse.failure(ErrorCode.SYSTEM_ERROR.getCode(), "服务内部错误：" + e.getMessage());
    }

    private Object readData(String json) {
        try {
            return mapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static long parseSeq(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
