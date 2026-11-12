package com.offerpilot.controller;

import com.offerpilot.common.ApiResponse;
import com.offerpilot.common.ErrorCode;
import com.offerpilot.dto.MatchRequest;
import com.offerpilot.dto.MatchResponse;
import com.offerpilot.service.MatchService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 匹配评估接口：POST /api/match。
 * 输入 JD 原文与简历原文，服务端内部编排「并行解析 → 匹配评估 → 证据校验与计分」。
 */
@RestController
@RequestMapping("/api/match")
public class MatchController {

    private static final Logger log = LoggerFactory.getLogger(MatchController.class);

    private final MatchService matchService;

    public MatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    @PostMapping
    public ApiResponse<MatchResponse> match(@Valid @RequestBody MatchRequest request) {
        long start = System.currentTimeMillis();
        MatchResponse response = matchService.match(request.jdText(), request.resumeText(), request.seed());
        MatchResponse.MatchMeta meta = response.meta();
        log.info("[match] overallScore={} level={} uncertainty={}/{} invalidDimensions={} renormalized={} | "
                        + "jdParse(elapsedMs={}, tokens={}) resumeParse(elapsedMs={}, tokens={}) | "
                        + "matchEval(model={}, elapsedMs={}, tokens={}, attempts={}, retried={}) | totalElapsedMs={}",
                response.report().overallScore(), response.report().level(),
                meta.uncertaintyCount(), meta.dimensionCount(), meta.invalidDimensions(), meta.renormalized(),
                meta.jdParse().elapsedMs(), meta.jdParse().totalTokens(),
                meta.resumeParse().elapsedMs(), meta.resumeParse().totalTokens(),
                meta.matchModel(), meta.matchEval().elapsedMs(), meta.matchEval().totalTokens(),
                meta.matchEval().attempts(), meta.matchEval().retried(),
                System.currentTimeMillis() - start);
        return ApiResponse.success(response);
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
        log.warn("[match] 参数校验失败: {}", detail);
        return ApiResponse.failure(ErrorCode.INVALID_PARAM.getCode(), "参数校验失败：" + detail);
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("[match] 匹配评估失败", e);
        return ApiResponse.failure(ErrorCode.LLM_CALL_FAILED.getCode(), "匹配评估失败：" + e.getMessage());
    }
}
