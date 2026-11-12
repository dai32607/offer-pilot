package com.offerpilot.controller;

import com.offerpilot.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 分析任务入口。
 * S1 阶段：POST /api/analysis 仅返回固定 stub 响应，后续版本接入真实 LLM 五 Agent 流程。
 */
@RestController
@RequestMapping("/api/analysis")
public class AnalysisController {

    @PostMapping
    public ApiResponse<Map<String, Object>> createAnalysis(
            @RequestBody(required = false) Map<String, Object> payload) {
        Map<String, Object> stub = Map.of(
                "taskId", UUID.randomUUID().toString(),
                "status", "CREATED",
                "message", "S1 骨架 stub 响应：分析任务已受理，后续版本将接入真实 LLM 流程",
                "received", payload == null ? Map.of() : payload
        );
        return ApiResponse.success(stub);
    }
}
