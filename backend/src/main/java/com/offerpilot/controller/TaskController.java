package com.offerpilot.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.ApiResponse;
import com.offerpilot.common.ErrorCode;
import com.offerpilot.dto.MatchReport;
import com.offerpilot.dto.MatchResponse;
import com.offerpilot.dto.ReportView;
import com.offerpilot.dto.TaskCreateRequest;
import com.offerpilot.dto.TaskCreateResponse;
import com.offerpilot.dto.TaskStatusView;
import com.offerpilot.task.AnalysisTask;
import com.offerpilot.task.TaskEvent;
import com.offerpilot.task.TaskService;
import com.offerpilot.task.TaskStore;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 分析任务接口（需求说明书 6.2 A03/A04/A05/A06/A07）：
 * <ul>
 *     <li>POST /api/v1/tasks：创建任务，返回 taskId 与 stream/report 入口；</li>
 *     <li>GET  /api/v1/tasks/{taskNo}：任务状态（status / progress / current_agent / token 汇总）；</li>
 *     <li>GET  /api/v1/tasks/{taskNo}/stream：SSE 事件流，支持 Last-Event-ID 断线补齐；</li>
 *     <li>GET  /api/v1/tasks/{taskNo}/events：事件历史（REST 补齐通道，供前端逐 seq 对账）；</li>
 *     <li>GET  /api/v1/tasks/{taskNo}/report：最终报告。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private static final String REWRITE_NOTICE =
            "改写建议由评估链路基于匹配缺口/风险与简历原文自动生成；离线快照回放与缓存命中均保留完整建议，无需 API Key。";

    private final TaskService taskService;
    private final TaskStore store;
    private final ObjectMapper mapper;

    public TaskController(TaskService taskService, TaskStore store, ObjectMapper mapper) {
        this.taskService = taskService;
        this.store = store;
        this.mapper = mapper;
    }

    @PostMapping
    public ApiResponse<TaskCreateResponse> create(@Valid @RequestBody TaskCreateRequest request) {
        String taskNo = (request.taskNo() == null || request.taskNo().isBlank())
                ? UUID.randomUUID().toString().replace("-", "")
                : request.taskNo().trim();

        AnalysisTask exists = store.get(taskNo);
        if (exists != null && !exists.isTerminal()) {
            // 幂等：同一 task_no 重复提交时不重复计费（约束 6.1），直接禁用按钮场景返回 40009
            return ApiResponse.failure(ErrorCode.TASK_RUNNING.getCode(),
                    "任务 " + taskNo + " 正在执行，禁止重复触发");
        }

        AnalysisTask task = exists != null ? exists : taskService.submit(taskNo, request.jdText(), request.resumeText());
        return ApiResponse.success(new TaskCreateResponse(
                task.getTaskNo(), task.getStatus(), task.getProgress(),
                "/api/v1/tasks/" + taskNo + "/stream",
                "/api/v1/tasks/" + taskNo + "/report",
                "/api/v1/tasks/" + taskNo + "/events"));
    }

    @GetMapping
    public ApiResponse<List<TaskStatusView>> list() {
        List<AnalysisTask> tasks = new ArrayList<>(store.list());
        tasks.sort(Comparator.comparingLong(AnalysisTask::getCreatedAtMs).reversed());
        List<TaskStatusView> views = new ArrayList<>();
        for (AnalysisTask task : tasks) {
            views.add(view(task));
        }
        return ApiResponse.success(views);
    }

    @GetMapping("/{taskNo}")
    public ApiResponse<TaskStatusView> status(@PathVariable String taskNo) {
        AnalysisTask task = store.get(taskNo);
        if (task == null) {
            return ApiResponse.failure(ErrorCode.NOT_FOUND.getCode(), "任务不存在：" + taskNo);
        }
        return ApiResponse.success(view(task));
    }

    /**
     * SSE 事件流：客户端重连时浏览器自动带 Last-Event-ID，
     * 服务端只补发 seq &gt; lastEventId 的事件，实现「按序到达 + 断线可续」。
     */
    @GetMapping(value = "/{taskNo}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@PathVariable String taskNo,
                                             @RequestHeader(value = "Last-Event-ID", required = false)
                                             String lastEventId) {
        long last = parseSeq(lastEventId);
        SseEmitter emitter = store.newEmitter();
        if (!store.attach(taskNo, last, emitter)) {
            log.warn("[sse] 任务不存在，拒绝建流: {}", taskNo);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(emitter);
    }

    /** REST 补齐通道：返回 seq &gt; after 的事件历史，供前端断线后自行补齐与对账。 */
    @GetMapping("/{taskNo}/events")
    public ApiResponse<List<Map<String, Object>>> events(@PathVariable String taskNo,
                                                         @RequestParam(defaultValue = "0") long after) {
        AnalysisTask task = store.get(taskNo);
        if (task == null) {
            return ApiResponse.failure(ErrorCode.NOT_FOUND.getCode(), "任务不存在：" + taskNo);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskEvent event : store.eventsAfter(taskNo, after)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("seq", event.seq());
            item.put("event", event.name());
            item.put("ts", event.ts());
            item.put("data", readData(event.dataJson()));
            result.add(item);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/{taskNo}/report")
    public ApiResponse<ReportView> report(@PathVariable String taskNo,
                                          @RequestParam(defaultValue = "true") boolean includeRewrite) {
        AnalysisTask task = store.get(taskNo);
        if (task == null) {
            return ApiResponse.failure(ErrorCode.NOT_FOUND.getCode(), "任务不存在：" + taskNo);
        }
        MatchResponse response = task.getResult();
        if (response == null) {
            return ApiResponse.failure(ErrorCode.TASK_RUNNING.getCode(),
                    "报告尚未生成，任务状态：" + task.getStatus()
                            + (task.getFailedAgent() == null ? "" : "，失败节点：" + task.getFailedAgent()));
        }
        return ApiResponse.success(new ReportView(response.report(), response.meta(),
                toRewriteItems(response.report().rewriteSuggestions()), REWRITE_NOTICE,
                response.report().evidenceIndex()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ApiResponse<Void> handleInvalidParam(MethodArgumentNotValidException e) {
        StringBuilder detail = new StringBuilder();
        e.getBindingResult().getFieldErrors().forEach(f -> {
            if (detail.length() > 0) {
                detail.append("; ");
            }
            detail.append(f.getField()).append(" ").append(f.getDefaultMessage());
        });
        return ApiResponse.failure(ErrorCode.PARAM_INVALID.getCode(), "参数校验失败：" + detail);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("[task] 接口异常", e);
        return ApiResponse.failure(ErrorCode.SYSTEM_ERROR.getCode(), "服务内部错误：" + e.getMessage());
    }

    /** 将报告中的改写建议转换为接口视图条目（保持 original/rewritten/reason/reference 结构）。 */
    private List<Map<String, Object>> toRewriteItems(List<MatchReport.RewriteSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (MatchReport.RewriteSuggestion s : suggestions) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("original", s.original());
            item.put("rewritten", s.rewritten());
            item.put("reason", s.reason());
            item.put("reference", s.reference());
            items.add(item);
        }
        return items;
    }

    private TaskStatusView view(AnalysisTask task) {
        return new TaskStatusView(
                task.getTaskNo(), task.getStatus(), task.getProgress(),
                task.getCurrentAgent(), task.getFailedAgent(),
                task.getTokenTotal(), task.getDurationMs(), task.getCreatedAt(),
                task.getTotalAgents(), task.getFinishedAgents(),
                task.getResult() != null, store.lastSeq(task));
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
