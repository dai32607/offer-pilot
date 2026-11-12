package com.offerpilot.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * DeepSeek 客户端：基于 JDK 原生 HttpClient 调用 OpenAI 兼容的
 * /chat/completions 接口，支持连接/读取超时、失败重试 1 次、token 用量解析。
 */
public class DeepSeekClient {

    private static final int MAX_ATTEMPTS = 2; // 首次调用 + 失败重试 1 次

    /** 默认读取超时（秒），保持既有调用行为不变。 */
    public static final int DEFAULT_READ_TIMEOUT_SECONDS = 60;

    /** 默认温度。 */
    private static final double DEFAULT_TEMPERATURE = 0.7;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    /** 单次请求的读取超时（秒）：解析类任务较短，推理类任务（reasoner）需更长。 */
    private final int readTimeoutSeconds;
    /** 请求级最大尝试次数：默认 2（失败重试 1 次）；面试官单轮硬超时场景置 1（禁止重试）。 */
    private final int maxAttempts;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public DeepSeekClient(String baseUrl, String apiKey, String model) {
        this(baseUrl, apiKey, model, DEFAULT_READ_TIMEOUT_SECONDS);
    }

    /** 可指定读取超时的构造器：供推理模型（长链路推理，耗时更长）使用。 */
    public DeepSeekClient(String baseUrl, String apiKey, String model, int readTimeoutSeconds) {
        this(baseUrl, apiKey, model, readTimeoutSeconds, MAX_ATTEMPTS);
    }

    /**
     * 可指定超时与重试次数的构造器。
     * 面试官链路使用 {@code maxAttempts=1}：需求 3.1 规定单轮硬超时 60s，
     * 若保留默认重试，超时后立即重发会把单轮最坏时长翻倍到 120s，破坏硬超时边界。
     */
    public DeepSeekClient(String baseUrl, String apiKey, String model, int readTimeoutSeconds, int maxAttempts) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String getModel() {
        return model;
    }

    /** 仅携带用户消息的单轮对话。 */
    public ChatResponse chat(String userPrompt) {
        ChatRequest request = new ChatRequest(
                model,
                List.of(new ChatRequest.Message("user", userPrompt)),
                0.7,
                false
        );
        return execute(request);
    }

    /** 携带 system 提示词与用户消息的单轮对话（默认温度 0.7）。 */
    public ChatResponse chat(String systemPrompt, String userPrompt) {
        return chat(systemPrompt, userPrompt, DEFAULT_TEMPERATURE);
    }

    /** 携带 system 提示词、用户消息与指定温度的单轮对话。 */
    public ChatResponse chat(String systemPrompt, String userPrompt, double temperature) {
        ChatRequest request = new ChatRequest(
                model,
                List.of(
                        new ChatRequest.Message("system", systemPrompt),
                        new ChatRequest.Message("user", userPrompt)
                ),
                temperature,
                false
        );
        return execute(request);
    }

    /**
     * 不携带采样参数的单轮对话：用于推理模型（如 deepseek-v4-pro）。
     * 推理模型对采样参数不敏感，规格要求「不传采样参数」，
     * 故 temperature 置 null，由 ChatRequest 的 NON_NULL 策略在序列化时省略该字段。
     */
    public ChatResponse chatNoSampling(String systemPrompt, String userPrompt) {
        return chatNoSampling(systemPrompt, userPrompt, null);
    }

    /**
     * 不携带采样参数、可携带固定 seed 的单轮对话：用于评测固定种子重跑。
     * seed 非空时随请求体透传（OpenAI 兼容协议支持该字段；DeepSeek 官方可能忽略，
     * 冒烟可通过重复调用比对结果验证是否生效）。
     */
    public ChatResponse chatNoSampling(String systemPrompt, String userPrompt, Integer seed) {
        ChatRequest request = new ChatRequest(
                model,
                List.of(
                        new ChatRequest.Message("system", systemPrompt),
                        new ChatRequest.Message("user", userPrompt)
                ),
                null,
                false,
                seed
        );
        return execute(request);
    }

    /**
     * 流式单轮对话（不传采样参数）：逐块回调增量文本，返回汇总后的完整响应（含 token 用量）。
     * <p>
     * 用于评估阶段：等待期间可向前端持续推送「已生成字符数」进度，改善感知时延；
     * 最终 content 与一次性调用完全一致，不改变下游解析与校验逻辑。
     */
    public ChatResponse chatStreamNoSampling(String systemPrompt, String userPrompt,
                                             Consumer<String> onDelta) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userPrompt)));
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        return executeStream(body, onDelta);
    }

    private ChatResponse executeStream(Map<String, Object> body, Consumer<String> onDelta) {
        try {
            String json = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "text/event-stream")
                    .timeout(Duration.ofSeconds(readTimeoutSeconds))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String err = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("DeepSeek API 返回非 2xx 状态码: "
                        + response.statusCode() + ", body=" + err);
            }

            StringBuilder content = new StringBuilder();
            int promptTokens = 0;
            int completionTokens = 0;
            int totalTokens = 0;
            // 流式响应体行级硬超时：JDK HttpClient 的 request timeout 仅约束到响应头，
            // 响应头到达后 body 流无内置超时；此处按 readTimeoutSeconds 做总时长截止检查。
            long deadlineNanos = System.nanoTime()
                    + Duration.ofSeconds(readTimeoutSeconds).toNanos();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (System.nanoTime() > deadlineNanos) {
                        throw new java.net.http.HttpTimeoutException(
                                "DeepSeek 流式调用超过 " + readTimeoutSeconds + "s 硬超时，model=" + model);
                    }
                    if (line.isEmpty() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) {
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(data);
                    JsonNode choices = node.get("choices");
                    if (choices != null && choices.isArray() && !choices.isEmpty()) {
                        JsonNode delta = choices.get(0).path("delta").path("content");
                        if (delta != null && !delta.isNull() && !delta.asText().isEmpty()) {
                            String text = delta.asText();
                            content.append(text);
                            if (onDelta != null) {
                                onDelta.accept(text);
                            }
                        }
                    }
                    JsonNode usage = node.get("usage");
                    if (usage != null && !usage.isNull()) {
                        promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                        completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                        totalTokens = usage.path("total_tokens").asInt(totalTokens);
                    }
                }
            }
            if (totalTokens == 0) {
                totalTokens = promptTokens + completionTokens;
            }
            return new ChatResponse("stream", "chat.completion.chunk", 0L, model,
                    List.of(new ChatResponse.Choice(0,
                            new ChatResponse.Choice.Message("assistant", content.toString()), "stop")),
                    new ChatResponse.Usage(promptTokens, completionTokens, totalTokens));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("DeepSeek 流式调用失败，model=" + model, e);
        }
    }

    private ChatResponse execute(ChatRequest request) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String body = objectMapper.writeValueAsString(request);
                HttpRequest httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/chat/completions"))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + apiKey)
                        .timeout(Duration.ofSeconds(readTimeoutSeconds)) // 读取超时
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();

                HttpResponse<String> response;
                try {
                    // 总时长硬超时：request timeout 仅约束响应头阶段，
                    // 使用 sendAsync + get(timeout) 对「响应头 + body 下载」整体限时。
                    response = httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                            .get(readTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException e) {
                    throw new java.net.http.HttpTimeoutException(
                            "DeepSeek API 调用超过 " + readTimeoutSeconds + "s 硬超时，model=" + model);
                }

                if (response.statusCode() / 100 != 2) {
                    throw new IllegalStateException(
                            "DeepSeek API 返回非 2xx 状态码: " + response.statusCode()
                                    + ", body=" + response.body());
                }
                return objectMapper.readValue(response.body(), ChatResponse.class);
            } catch (Exception e) {
                lastError = e;
                // 失败则重试（最多重试 1 次），重试前不 sleep，直接发起下一次调用
            }
        }
        throw new IllegalStateException("DeepSeek API 调用失败（已重试 "
                + (maxAttempts - 1) + " 次），model=" + model, lastError);
    }
}
