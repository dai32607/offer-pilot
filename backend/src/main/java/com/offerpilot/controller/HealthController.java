package com.offerpilot.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查：返回服务状态与当前使用的 chat 模型名。
 */
@RestController
public class HealthController {

    @Value("${deepseek.model.chat:deepseek-flash}")
    private String chatModel;

    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("model", chatModel);
        return body;
    }
}
