package com.offerpilot.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 结构化输出解析：从大模型原始返回中提取 JSON 片段并反序列化为目标类型。
 * 兼容 ```json 代码块包裹、前后多余文字等常见情况；失败时抛出带明确错误信息的异常，
 * 供上层 Agent 将错误信息回传给模型重试。
 */
public class StructuredJsonParser {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 解析成功返回目标对象；失败抛出 IllegalArgumentException（消息即校验错误信息）。 */
    public <T> T parse(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("模型返回内容为空，无法解析为 " + type.getSimpleName());
        }
        String json = extractJson(raw);
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "JSON 校验失败（目标类型 " + type.getSimpleName() + "）：" + e.getMessage()
                            + "；实际片段=" + brief(json), e);
        }
    }

    /** 从原始文本中提取 JSON 片段：先剥离 Markdown 代码块围栏，再截取首「{」到末「}」。 */
    public static String extractJson(String raw) {
        String text = raw.trim();

        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = text.indexOf('\n', fenceStart);
            if (contentStart >= 0) {
                int fenceEnd = text.indexOf("```", contentStart);
                if (fenceEnd > contentStart) {
                    text = text.substring(contentStart + 1, fenceEnd).trim();
                } else {
                    text = text.substring(contentStart + 1).trim();
                }
            }
        }

        int first = text.indexOf('{');
        int last = text.lastIndexOf('}');
        if (first >= 0 && last > first) {
            text = text.substring(first, last + 1);
        }
        return text.trim();
    }

    private static String brief(String s) {
        String compact = s.replaceAll("\\s+", " ");
        return compact.length() > 200 ? compact.substring(0, 200) + "..." : compact;
    }
}
