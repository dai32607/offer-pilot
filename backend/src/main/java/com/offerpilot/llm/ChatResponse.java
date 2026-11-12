package com.offerpilot.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * OpenAI 兼容协议的 Chat Completion 响应体。
 * 提供 content() 便捷方法获取首个选择的回复内容，并提供 token 消耗统计。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String id,
        String object,
        long created,
        String model,
        List<Choice> choices,
        Usage usage
) {

    public String content() {
        if (choices == null || choices.isEmpty()) {
            return null;
        }
        Choice first = choices.get(0);
        return first.message() == null ? null : first.message().content();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, Message message, String finish_reason) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Message(String role, String content) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {
    }
}
