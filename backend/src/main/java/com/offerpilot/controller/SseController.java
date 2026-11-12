package com.offerpilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SSE 进度推送。
 * S1 阶段：仅每 15 秒发送一次心跳占位，后续版本在此推送真实分析进度。
 */
@RestController
@RequestMapping("/api/analysis")
public class SseController {

    @GetMapping("/events")
    public SseEmitter streamEvents() {
        SseEmitter emitter = new SseEmitter(0L);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

        // 占位心跳：每 15 秒发送一次 ping，保持连接存活
        scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name("heartbeat").data("ping"));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        }, 15, 15, TimeUnit.SECONDS);

        emitter.onCompletion(scheduler::shutdown);
        emitter.onTimeout(scheduler::shutdown);
        emitter.onError(t -> scheduler.shutdown());
        return emitter;
    }
}
