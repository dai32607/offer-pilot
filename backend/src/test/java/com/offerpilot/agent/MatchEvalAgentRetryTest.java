package com.offerpilot.agent;

import com.offerpilot.dto.MatchDraft;
import com.offerpilot.llm.ChatResponse;
import com.offerpilot.llm.DeepSeekClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C1（结构校验重试）与 C2（不确定维度阈值重试）的单元测试（不依赖真实链路）：
 * 用构造的失败输出主动触发 MatchEvalAgent 的两条重试分支。
 */
class MatchEvalAgentRetryTest {

    private static final String JD_RAW = "任职要求：熟悉 Java 与 Spring Boot 后端开发，了解 RAG 与大模型应用；大三或研二优先。";
    private static final String RESUME_RAW = "西南财经大学计算机科学与技术专业本科在读（大三），掌握 Java、MySQL 与数据结构基础。";

    private static ChatResponse response(String content, int promptTokens, int completionTokens) {
        return new ChatResponse("resp-stub", "chat.completion", 0L, "deepseek-v4-pro",
                List.of(new ChatResponse.Choice(0,
                        new ChatResponse.Choice.Message("assistant", content), "stop")),
                new ChatResponse.Usage(promptTokens, completionTokens, promptTokens + completionTokens));
    }

    private static DeepSeekClient mockedClient(ChatResponse first, ChatResponse second) {
        DeepSeekClient client = mock(DeepSeekClient.class);
        when(client.getModel()).thenReturn("deepseek-v4-pro");
        when(client.chatNoSampling(anyString(), anyString(), any())).thenReturn(first, second);
        return client;
    }

    /** 构造单个维度草稿；quote 为 null 表示该维度 evidence 中不写该来源的证据。 */
    private static String dimension(String name, double rawScore, String jdQuote, String resumeQuote,
                                    boolean uncertainty, String conclusion) {
        StringBuilder json = new StringBuilder("{\"name\":\"").append(name)
                .append("\",\"rawScore\":").append(rawScore)
                .append(",\"conclusion\":\"").append(conclusion)
                .append("\",\"evidence\":[");
        boolean needsComma = false;
        if (jdQuote != null) {
            json.append("{\"source\":\"jd\",\"quote\":\"").append(jdQuote).append("\"}");
            needsComma = true;
        }
        if (resumeQuote != null) {
            if (needsComma) {
                json.append(",");
            }
            json.append("{\"source\":\"resume\",\"quote\":\"").append(resumeQuote).append("\"}");
        }
        return json.append("],\"uncertainty\":").append(uncertainty).append("}").toString();
    }

    /** 合规输出：5 个维度齐全，且每个维度都带可在原文中逐字定位的证据。 */
    private static String validDraftJson() {
        return "{\"dimensions\":["
                + dimension("硬性技能匹配", 7.5, "熟悉 Java 与 Spring Boot 后端开发", "掌握 Java、MySQL 与数据结构基础", false, "技能基本覆盖。")
                + "," + dimension("经验年限与职级", 5.0, "大三或研二优先", "本科在读（大三）", false, "无实习经历，年限信息有限。")
                + "," + dimension("项目与成果相关度", 4.0, "了解 RAG 与大模型应用", "掌握 Java、MySQL 与数据结构基础", false, "无项目成果可对应。")
                + "," + dimension("教育与证书", 8.0, "大三或研二优先", "西南财经大学计算机科学与技术专业", false, "学历专业匹配。")
                + "," + dimension("加分项与潜力", 5.0, "大三或研二优先", "本科在读（大三）", false, "加分项命中一般。")
                + "],\"hits\":[],\"gaps\":[],\"risks\":[]}";
    }

    @Test
    @DisplayName("C1：输出维度结构不完整（缺少维度）→ 回灌结构问题重试 1 次并成功")
    void c1RetriesWhenDimensionsIncomplete() {
        String incomplete = "{\"dimensions\":["
                + dimension("硬性技能匹配", 7.5, "熟悉 Java 与 Spring Boot 后端开发", "掌握 Java、MySQL 与数据结构基础", false, "技能基本覆盖。")
                + "," + dimension("教育与证书", 8.0, "大三或研二优先", "西南财经大学计算机科学与技术专业", false, "学历专业匹配。")
                + "],\"hits\":[],\"gaps\":[],\"risks\":[]}";

        DeepSeekClient client = mockedClient(response(incomplete, 2000, 500), response(validDraftJson(), 2200, 400));

        AgentResult<MatchDraft> result = new MatchEvalAgent(client)
                .evaluate(JD_RAW, RESUME_RAW, null, null);

        assertEquals(2, result.attempts());
        assertTrue(result.retried());
        assertNotNull(result.firstError());
        assertTrue(result.firstError().contains("输出结构校验未通过"), result.firstError());
        assertTrue(result.firstError().contains("缺少维度：经验年限与职级"), result.firstError());
        assertEquals(5, result.data().dimensions().size());
        assertEquals(4200, result.promptTokens());
        assertEquals(900, result.completionTokens());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).chatNoSampling(anyString(), promptCaptor.capture(), any());
        String retryPrompt = promptCaptor.getAllValues().get(1);
        assertTrue(retryPrompt.contains("【上一次输出未通过校验，请修正后重新输出完整 JSON】"));
        assertTrue(retryPrompt.contains("缺少维度：经验年限与职级"));
        assertTrue(retryPrompt.contains("【JD 原文】"), "重试 prompt 应保留完整原始输入");
    }

    @Test
    @DisplayName("C2：不确定/无有效证据维度数超过总数 1/5 → 触发阈值重试 1 次并成功")
    void c2RetriesWhenUncertainDimensionsExceedThreshold() {
        // 5 个维度中 2 个缺少可定位证据（2 × 5 = 10 > 5），超过维度总数的 1/5
        String tooManyUncertain = "{\"dimensions\":["
                + dimension("硬性技能匹配", 7.5, "熟悉 Java 与 Spring Boot 后端开发", "掌握 Java、MySQL 与数据结构基础", false, "技能基本覆盖。")
                + "," + dimension("经验年限与职级", 5.0, "大三或研二优先", "本科在读（大三）", false, "无实习经历。")
                + "," + dimension("项目与成果相关度", 4.0, "了解 RAG 与大模型应用", "掌握 Java、MySQL 与数据结构基础", false, "无项目成果。")
                + "," + dimension("教育与证书", 8.0, null, null, false, "学历专业匹配。")
                + "," + dimension("加分项与潜力", 5.0, null, null, false, "加分项命中一般。")
                + "],\"hits\":[],\"gaps\":[],\"risks\":[]}";

        DeepSeekClient client = mockedClient(response(tooManyUncertain, 2000, 600), response(validDraftJson(), 2100, 450));

        AgentResult<MatchDraft> result = new MatchEvalAgent(client)
                .evaluate(JD_RAW, RESUME_RAW, null, null);

        assertEquals(2, result.attempts());
        assertTrue(result.retried());
        assertNotNull(result.firstError());
        assertTrue(result.firstError().contains("超过维度总数的 1/5"), result.firstError());
        assertTrue(result.firstError().contains("教育与证书"));
        assertTrue(result.firstError().contains("加分项与潜力"));
        assertEquals(5, result.data().dimensions().size());

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(client, times(2)).chatNoSampling(anyString(), promptCaptor.capture(), any());
        String retryPrompt = promptCaptor.getAllValues().get(1);
        assertTrue(retryPrompt.contains("标记为不确定或缺少有效原文证据的维度达 2/5"));
        assertTrue(retryPrompt.contains("严禁为凑证据而编造或改写原文"));
    }

    @Test
    @DisplayName("C2 边界：不确定维度数为 1（1 × 5 不大于 5）→ 不触发重试")
    void noRetryWhenUncertainDimensionsWithinThreshold() {
        String oneUncertain = "{\"dimensions\":["
                + dimension("硬性技能匹配", 7.5, "熟悉 Java 与 Spring Boot 后端开发", "掌握 Java、MySQL 与数据结构基础", false, "技能基本覆盖。")
                + "," + dimension("经验年限与职级", 5.0, "大三或研二优先", "本科在读（大三）", false, "无实习经历。")
                + "," + dimension("项目与成果相关度", 4.0, "了解 RAG 与大模型应用", "掌握 Java、MySQL 与数据结构基础", false, "无项目成果。")
                + "," + dimension("教育与证书", 8.0, null, null, true, "原文信息不足。")
                + "," + dimension("加分项与潜力", 5.0, "大三或研二优先", "本科在读（大三）", false, "加分项命中一般。")
                + "],\"hits\":[],\"gaps\":[],\"risks\":[]}";

        DeepSeekClient client = mockedClient(response(oneUncertain, 2000, 600), null);

        AgentResult<MatchDraft> result = new MatchEvalAgent(client)
                .evaluate(JD_RAW, RESUME_RAW, null, null);

        assertEquals(1, result.attempts());
        assertFalse(result.retried());
        assertEquals(5, result.data().dimensions().size());
        verify(client, times(1)).chatNoSampling(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("C1/C2：两次输出均不达标 → 抛出异常，不返回残缺数据")
    void failsWhenBothAttemptsRejected() {
        String incomplete = "{\"dimensions\":[],\"hits\":[],\"gaps\":[],\"risks\":[]}";
        DeepSeekClient client = mockedClient(response(incomplete, 2000, 100), response(incomplete, 2100, 120));

        MatchEvalAgent agent = new MatchEvalAgent(client);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> agent.evaluate(JD_RAW, RESUME_RAW, null, null));
        assertTrue(ex.getMessage().contains("匹配评估失败（已重试 1 次）"), ex.getMessage());
        assertTrue(ex.getMessage().contains("输出结构校验未通过"));
        verify(client, times(2)).chatNoSampling(anyString(), anyString(), any());
    }
}
