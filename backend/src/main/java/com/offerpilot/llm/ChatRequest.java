package com.offerpilot.llm;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * OpenAI 兼容协议的 Chat Completion 请求体。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<Message> messages,
        Double temperature,
        Boolean stream,
        Integer seed
) {
    /** 兼容旧调用：不传 seed。 */
    public ChatRequest(String model, List<Message> messages, Double temperature, Boolean stream) {
        this(model, messages, temperature, stream, null);
    }

    public record Message(String role, String content) {
    }
}
