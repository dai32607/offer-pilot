package com.offerpilot.agent;

import com.offerpilot.dto.MatchReport;
import com.offerpilot.llm.ChatResponse;
import com.offerpilot.llm.DeepSeekClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RewriteSuggestionAgent 降级契约测试：
 * 正常解析、解析失败降级空列表、模型调用异常降级空列表、空输入不调用模型。
 */
class RewriteSuggestionAgentTest {

    private static final String RESUME_RAW = "系统学习过 C 语言与 Java，通过课程实践完成过基础算法与小型程序的编写；"
            + "暂无项目、暂无比赛经历。";

    private static final MatchReport REPORT = new MatchReport(27.4, "一般",
            List.of(new MatchReport.DimensionScore("硬性技能匹配", 30.0, 9.0, 30.0,
                    "Java命中；Spring Boot/MySQL未体现", List.of(), false)),
            List.of(new MatchReport.HitItem("熟悉 Java 或 Python", "系统学习过 C 语言与 Java", "Java满足语言要求")),
            List.of(new MatchReport.GapItem("Spring Boot、MySQL", "技能缺失", "高", "需补充后端框架和数据库能力")),
            List.of(new MatchReport.RiskItem("缺乏后端与LLM项目经验", "简历无项目且未提Spring", null)),
            List.of(), List.of(), List.of(), null);

    private static ChatResponse response(String content) {
        return new ChatResponse("resp-stub", "chat.completion", 0L, "deepseek-v4-pro",
                List.of(new ChatResponse.Choice(0,
                        new ChatResponse.Choice.Message("assistant", content), "stop")),
                new ChatResponse.Usage(100, 200, 300));
    }

    private static DeepSeekClient clientReturning(String content) {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.getModel()).thenReturn("deepseek-v4-pro");
        when(client.chatNoSampling(anyString(), anyString())).thenReturn(response(content));
        return client;
    }

    @Test
    @DisplayName("正常：模型返回合法 JSON 时解析出改写建议，过滤空片段与超出上限")
    void parsesSuggestionsFromModelOutput() {
        String json = "{\"rewriteSuggestions\":["
                + "{\"original\":\"系统学习过 C 语言与 Java\",\"rewritten\":\"熟悉 Java，具备课程实践基础\","
                + "\"reason\":\"贴合 JD 语言要求\",\"reference\":\"硬性技能匹配\"},"
                + "{\"original\":\"\",\"rewritten\":\"空原文片段应被过滤\",\"reason\":\"x\",\"reference\":null},"
                + "{\"original\":\"暂无项目\",\"rewritten\":\"\",\"reason\":\"x\",\"reference\":null}"
                + "]}";
        RewriteSuggestionAgent agent = new RewriteSuggestionAgent(clientReturning(json));
        List<MatchReport.RewriteSuggestion> suggestions = agent.generate(REPORT, RESUME_RAW);
        assertEquals(1, suggestions.size(), "空 original/rewritten 的条目应被过滤");
        assertEquals("系统学习过 C 语言与 Java", suggestions.get(0).original());
        assertEquals("硬性技能匹配", suggestions.get(0).reference());
    }

    @Test
    @DisplayName("降级：模型返回非 JSON 文本（解析失败）→ 空列表且不抛异常")
    void degradesToEmptyOnParseFailure() {
        RewriteSuggestionAgent agent =
                new RewriteSuggestionAgent(clientReturning("抱歉，我无法生成改写建议。"));
        List<MatchReport.RewriteSuggestion> suggestions = agent.generate(REPORT, RESUME_RAW);
        assertTrue(suggestions.isEmpty(), "解析失败应降级为空列表");
    }

    @Test
    @DisplayName("降级：模型返回空 rewriteSuggestions 数组 → 空列表")
    void degradesToEmptyOnEmptyArray() {
        RewriteSuggestionAgent agent = new RewriteSuggestionAgent(clientReturning("{\"rewriteSuggestions\":[]}"));
        List<MatchReport.RewriteSuggestion> suggestions = agent.generate(REPORT, RESUME_RAW);
        assertTrue(suggestions.isEmpty());
    }

    @Test
    @DisplayName("降级：模型调用抛异常 → 空列表且不抛异常")
    void degradesToEmptyOnClientException() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.chatNoSampling(anyString(), anyString()))
                .thenThrow(new IllegalStateException("DeepSeek API 调用失败"));
        RewriteSuggestionAgent agent = new RewriteSuggestionAgent(client);
        List<MatchReport.RewriteSuggestion> suggestions = agent.generate(REPORT, RESUME_RAW);
        assertTrue(suggestions.isEmpty(), "模型调用失败应降级为空列表");
    }

    @Test
    @DisplayName("降级：简历原文为空时不调用模型，直接返回空列表")
    void skipsModelCallOnBlankResume() {
        DeepSeekClient client = mock(DeepSeekClient.class);
        RewriteSuggestionAgent agent = new RewriteSuggestionAgent(client);
        assertTrue(agent.generate(REPORT, "").isEmpty());
        assertTrue(agent.generate(REPORT, null).isEmpty());
        verify(client, never()).chatNoSampling(anyString(), anyString());
    }
}
