package com.offerpilot.controller;

import com.offerpilot.agent.AgentResult;
import com.offerpilot.agent.JdParseAgent;
import com.offerpilot.agent.ResumeParseAgent;
import com.offerpilot.common.ApiResponse;
import com.offerpilot.common.ErrorCode;
import com.offerpilot.dto.JdStruct;
import com.offerpilot.dto.ParseRequest;
import com.offerpilot.dto.ResumeStruct;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 解析层接口：JD 解析与简历解析。
 */
@RestController
@RequestMapping("/api/parse")
public class ParseController {

    private static final Logger log = LoggerFactory.getLogger(ParseController.class);

    private final JdParseAgent jdParseAgent;
    private final ResumeParseAgent resumeParseAgent;

    public ParseController(JdParseAgent jdParseAgent, ResumeParseAgent resumeParseAgent) {
        this.jdParseAgent = jdParseAgent;
        this.resumeParseAgent = resumeParseAgent;
    }

    @PostMapping("/jd")
    public ApiResponse<JdStruct> parseJd(@Valid @RequestBody ParseRequest request) {
        AgentResult<JdStruct> result = jdParseAgent.parseDetailed(request.text());
        log.info("[parse-jd] elapsedMs={} attempts={} retried={} tokens(prompt={}, completion={}, total={})",
                result.elapsedMs(), result.attempts(), result.retried(),
                result.promptTokens(), result.completionTokens(), result.totalTokens());
        return ApiResponse.success(result.data());
    }

    @PostMapping("/resume")
    public ApiResponse<ResumeStruct> parseResume(@Valid @RequestBody ParseRequest request) {
        AgentResult<ResumeStruct> result = resumeParseAgent.parseDetailed(request.text());
        log.info("[parse-resume] elapsedMs={} attempts={} retried={} tokens(prompt={}, completion={}, total={})",
                result.elapsedMs(), result.attempts(), result.retried(),
                result.promptTokens(), result.completionTokens(), result.totalTokens());
        return ApiResponse.success(result.data());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("[parse] 解析失败", e);
        return ApiResponse.failure(ErrorCode.LLM_CALL_FAILED.getCode(),
                "解析失败：" + e.getMessage());
    }
}
