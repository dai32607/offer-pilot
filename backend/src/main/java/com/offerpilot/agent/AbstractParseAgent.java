package com.offerpilot.agent;

import com.offerpilot.llm.ChatResponse;
import com.offerpilot.llm.DeepSeekClient;
import com.offerpilot.llm.StructuredJsonParser;

/**
 * 解析类 Agent 的公共骨架：
 * 调用大模型（chat 模型，温度 0.2）→ 容错提取 JSON → Jackson 校验；
 * 校验失败时把错误信息回传给模型重试 1 次。
 */
public abstract class AbstractParseAgent<T> {

    /** 解析任务统一温度：低温度保证结构化输出稳定。 */
    protected static final double TEMPERATURE = 0.2;

    protected final DeepSeekClient client;
    protected final StructuredJsonParser parser = new StructuredJsonParser();
    private final Class<T> type;

    protected AbstractParseAgent(DeepSeekClient client, Class<T> type) {
        this.client = client;
        this.type = type;
    }

    /** 当前 Agent 使用的模型名（供接口层记录日志与成本统计）。 */
    public String modelName() {
        return client.getModel();
    }

    /** 专业角色 system prompt：只依据原文、不得推断或补充、无信息返回空数组或 null、输出严格 JSON。 */
    protected abstract String systemPrompt();

    /** 用户 prompt 模板，入参为待解析原文。 */
    protected abstract String userPrompt(String input);

    /** 解析并返回结构化对象（不含元数据）。 */
    public T parse(String input) {
        return parseDetailed(input).data();
    }

    /** 解析并返回结构化对象 + 执行元数据（耗时 / token / 是否重试）。 */
    public AgentResult<T> parseDetailed(String input) {
        long start = System.currentTimeMillis();
        String userPrompt = userPrompt(input);

        int attempts = 0;
        int promptTokens = 0;
        int completionTokens = 0;
        int totalTokens = 0;

        ChatResponse response = client.chat(systemPrompt(), userPrompt, TEMPERATURE);
        attempts++;
        promptTokens += tokens(response, TokenKind.PROMPT);
        completionTokens += tokens(response, TokenKind.COMPLETION);
        totalTokens += tokens(response, TokenKind.TOTAL);

        String firstError = null;
        try {
            T data = parser.parse(response.content(), type);
            return new AgentResult<>(data, promptTokens, completionTokens, totalTokens,
                    System.currentTimeMillis() - start, attempts, false, null);
        } catch (RuntimeException e) {
            firstError = e.getMessage();
        }

        if (firstError == null) {
            throw new IllegalStateException("首次解析未通过校验，且错误信息缺失");
        }

        // 校验失败：把错误信息回传给模型，重试 1 次
        String retryPrompt = userPrompt
                + "\n\n【上一次输出未通过 JSON 校验】\n错误信息：" + firstError
                + "\n请严格按照【输出 JSON Schema】重新输出，只输出 JSON 本身，"
                + "禁止任何解释文字与 Markdown 代码块标记。";
        ChatResponse retryResponse = client.chat(systemPrompt(), retryPrompt, TEMPERATURE);
        attempts++;
        promptTokens += tokens(retryResponse, TokenKind.PROMPT);
        completionTokens += tokens(retryResponse, TokenKind.COMPLETION);
        totalTokens += tokens(retryResponse, TokenKind.TOTAL);

        T data = parser.parse(retryResponse.content(), type); // 仍失败则抛出，由接口层统一兜底
        return new AgentResult<>(data, promptTokens, completionTokens, totalTokens,
                System.currentTimeMillis() - start, attempts, true, firstError);
    }

    private static int tokens(ChatResponse response, TokenKind kind) {
        if (response == null || response.usage() == null) {
            return 0;
        }
        return switch (kind) {
            case PROMPT -> response.usage().prompt_tokens();
            case COMPLETION -> response.usage().completion_tokens();
            case TOTAL -> response.usage().total_tokens();
        };
    }

    private enum TokenKind {
        PROMPT, COMPLETION, TOTAL
    }
}
