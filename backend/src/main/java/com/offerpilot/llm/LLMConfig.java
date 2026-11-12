package com.offerpilot.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 配置：提供 chatModel（deepseek-flash）与 reasonerModel（deepseek-v4-pro）
 * 两个命名 Bean，供 JD 解析、简历解析、匹配评估、改写建议、面试官等 Agent 按需注入。
 * flash 用于解析/轻量任务，v4-pro 用于评估/推理任务。
 */
@Configuration
public class LLMConfig {

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.model.chat:deepseek-flash}")
    private String chatModelName;

    @Value("${deepseek.model.reasoner:deepseek-v4-pro}")
    private String reasonerModelName;

    /** 解析类调用（flash）单次读取超时（秒）。 */
    @Value("${deepseek.timeout.chat-seconds:60}")
    private int chatTimeoutSeconds;

    /** 推理类调用（v4-pro 长链路推理）单次读取超时（秒），需显著长于解析类模型。 */
    @Value("${deepseek.timeout.reasoner-seconds:180}")
    private int reasonerTimeoutSeconds;

    /** 面试官单轮调用读取超时（秒）：需求 3.1 规定面试官硬超时 60 秒 / 轮。 */
    @Value("${deepseek.timeout.interview-seconds:60}")
    private int interviewTimeoutSeconds;

    @Bean(name = "chatModel")
    public DeepSeekClient chatModel() {
        return new DeepSeekClient(baseUrl, apiKey, chatModelName, chatTimeoutSeconds);
    }

    @Bean(name = "reasonerModel")
    public DeepSeekClient reasonerModel() {
        return new DeepSeekClient(baseUrl, apiKey, reasonerModelName, reasonerTimeoutSeconds);
    }

    /** 面试官模型（与评估同档：v4-pro），逐轮串行调用，单轮超时 60s；请求级禁止重试，保证硬超时边界。 */
    @Bean(name = "interviewerModel")
    public DeepSeekClient interviewerModel() {
        return new DeepSeekClient(baseUrl, apiKey, reasonerModelName, interviewTimeoutSeconds, 1);
    }
}
