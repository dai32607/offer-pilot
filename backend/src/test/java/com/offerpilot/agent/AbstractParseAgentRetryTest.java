package com.offerpilot.agent;

import com.offerpilot.dto.JdStruct;
import com.offerpilot.llm.ChatResponse;
import com.offerpilot.llm.DeepSeekClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1 结构校验重试的单元测试（不依赖真实链路）：
 * 通过 mock DeepSeekClient 构造「首次输出非法 JSON」的失败输入，
 * 主动触发 AbstractParseAgent 的「错误信息回灌重试」分支。
 */
class AbstractParseAgentRetryTest {

    /** 测试用最小解析 Agent：固定 prompt，直接复用 AbstractParseAgent 的解析与重试骨架。 */
    private static class StubParseAgent extends AbstractParseAgent<JdStruct> {

        StubParseAgent(DeepSeekClient client) {
            super(client, JdStruct.class);
        }

        @Override
        protected String systemPrompt() {
            return "STUB-SYSTEM";
        }

        @Override
        protected String userPrompt(String input) {
            return "STUB-USER:" + input;
        }
    }

    private static final String VALID_JSON = """
            {"jobTitle":"AI 应用开发实习生","requiredSkills":[{"name":"Java","level":"必须"}],
            "bonusItems":[],"implicitRequirements":[],"experienceYears":null,"educationRequirement":"本科及以上"}""";

    private static ChatResponse response(String content, int promptTokens, int completionTokens) {
        return new ChatResponse("resp-stub", "chat.completion", 0L, "stub-model",
                List.of(new ChatResponse.Choice(0,
                        new ChatResponse.Choice.Message("assistant", content), "stop")),
                new ChatResponse.Usage(promptTokens, completionTokens, promptTokens + completionTokens));
    }

    private static DeepSeekClient mockedClient(ChatResponse first, ChatResponse second) {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.getModel()).thenReturn("deepseek-flash");
        when(client.chat(anyString(), anyString(), anyDouble())).thenReturn(first, second);
        return client;
    }

    @Test
    @DisplayName("C1：首次输出非法 JSON → 回灌错误信息重试 1 次并成功")
    void retriesOnceWhenFirstOutputInvalid() {
        DeepSeekClient client = mockedClient(
                response("这不是 JSON。我的结论是：候选人与岗位较为匹配。", 100, 20),
                response(VALID_JSON, 120, 30));

        AgentResult<JdStruct> result = new StubParseAgent(client).parseDetailed("JD 原文");

        assertEquals(2, result.attempts());
        assertTrue(result.retried());
        assertNotNull(result.firstError());
        assertTrue(result.firstError().contains("JSON 校验失败"),
                "首次错误信息应来自 StructuredJsonParser 的校验异常：" + result.firstError());
        assertEquals("AI 应用开发实习生", result.data().jobTitle());
        // 重试后的 token 与耗时按两次调用累计
        assertEquals(220, result.promptTokens());
        assertEquals(50, result.completionTokens());
        assertEquals(270, result.totalTokens());
        assertTrue(result.elapsedMs() >= 0);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).chat(anyString(), promptCaptor.capture(), anyDouble());
        List<String> prompts = promptCaptor.getAllValues();
        assertEquals("STUB-USER:JD 原文", prompts.get(0));
        assertTrue(prompts.get(1).contains("STUB-USER:JD 原文"), "重试 prompt 应保留原始用户输入");
        assertTrue(prompts.get(1).contains("【上一次输出未通过 JSON 校验】"), "重试 prompt 应回灌校验失败标记");
        assertTrue(prompts.get(1).contains(result.firstError()), "重试 prompt 应包含首次校验错误信息");
    }

    @Test
    @DisplayName("C1：首次输出合法 → 不触发重试")
    void noRetryWhenFirstOutputValid() {
        DeepSeekClient client = mockedClient(response(VALID_JSON, 100, 20), null);

        AgentResult<JdStruct> result = new StubParseAgent(client).parseDetailed("JD 原文");

        assertEquals(1, result.attempts());
        assertFalse(result.retried());
        assertNull(result.firstError());
        assertEquals("AI 应用开发实习生", result.data().jobTitle());
        verify(client, times(1)).chat(anyString(), anyString(), anyDouble());
    }

    @Test
    @DisplayName("C1：两次输出均非法 → 抛出异常，不返回残缺数据")
    void failsWhenBothAttemptsInvalid() {
        DeepSeekClient client = mockedClient(
                response("第一次：直接给结论，不输出 JSON。", 100, 20),
                response("第二次仍然不是 JSON。", 110, 25));

        StubParseAgent agent = new StubParseAgent(client);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> agent.parseDetailed("JD 原文"));
        assertTrue(ex.getMessage().contains("JSON 校验失败"));
        verify(client, times(2)).chat(anyString(), anyString(), anyDouble());
    }
}
