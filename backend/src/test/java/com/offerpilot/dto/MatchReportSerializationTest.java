package com.offerpilot.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MatchReport 新增 rewriteSuggestions 字段后的 JSON 契约测试：
 * 新字段序列化、旧报告 JSON（无该字段）反序列化兼容、null 语义。
 */
class MatchReportSerializationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static MatchReport reportWithSuggestions() {
        return new MatchReport(27.4, "一般", List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(new MatchReport.RewriteSuggestion(
                        "系统学习过 C 语言与 Java，通过课程实践完成过基础算法与小型程序的编写",
                        "熟悉 Java，具备课程实践与小型程序编写经验，掌握面向对象编程基础",
                        "突出 Java 技能以贴合 JD 语言要求",
                        "硬性技能匹配")));
    }

    @Test
    @DisplayName("序列化：新报告 JSON 包含 rewriteSuggestions 数组及四字段")
    void serializesRewriteSuggestions() throws Exception {
        String json = mapper.writeValueAsString(reportWithSuggestions());
        assertTrue(json.contains("\"rewriteSuggestions\""), "序列化应包含 rewriteSuggestions 字段");
        assertTrue(json.contains("\"original\"") && json.contains("\"rewritten\"")
                && json.contains("\"reason\"") && json.contains("\"reference\""),
                "建议条目应包含 original/rewritten/reason/reference 四字段");
    }

    @Test
    @DisplayName("反序列化兼容：旧报告 JSON 缺少 rewriteSuggestions 不报错，字段为 null")
    void deserializesLegacyReportWithoutField() throws Exception {
        String legacyJson = "{\"overallScore\":27.4,\"level\":\"一般\",\"dimensions\":[],\"hits\":[],"
                + "\"gaps\":[],\"risks\":[],\"evidenceIndex\":[],\"uncertaintyFlags\":[],\"notes\":[]}";
        MatchReport report = mapper.readValue(legacyJson, MatchReport.class);
        assertNotNull(report);
        assertEquals(27.4, report.overallScore());
        assertNull(report.rewriteSuggestions(), "旧报告缺失该字段时应为 null（由调用方降级为空列表）");
    }

    @Test
    @DisplayName("反序列化：新 JSON 中 rewriteSuggestions 完整还原")
    void deserializesRewriteSuggestions() throws Exception {
        String json = "{\"overallScore\":27.4,\"level\":\"一般\",\"rewriteSuggestions\":["
                + "{\"original\":\"系统学习过 C 语言与 Java\",\"rewritten\":\"熟悉 Java 与面向对象编程\","
                + "\"reason\":\"贴合 JD 语言要求\",\"reference\":\"硬性技能匹配\"}]}";
        MatchReport report = mapper.readValue(json, MatchReport.class);
        assertNotNull(report.rewriteSuggestions());
        assertEquals(1, report.rewriteSuggestions().size());
        MatchReport.RewriteSuggestion s = report.rewriteSuggestions().get(0);
        assertEquals("系统学习过 C 语言与 Java", s.original());
        assertEquals("熟悉 Java 与面向对象编程", s.rewritten());
        assertEquals("贴合 JD 语言要求", s.reason());
        assertEquals("硬性技能匹配", s.reference());
    }

    @Test
    @DisplayName("反序列化兼容：未知字段被忽略（@JsonIgnoreProperties）")
    void ignoresUnknownProperties() throws Exception {
        String json = "{\"overallScore\":27.4,\"level\":\"一般\",\"futureField\":123,"
                + "\"rewriteSuggestions\":[]}";
        MatchReport report = mapper.readValue(json, MatchReport.class);
        assertEquals(27.4, report.overallScore());
        assertNotNull(report.rewriteSuggestions());
        assertTrue(report.rewriteSuggestions().isEmpty());
    }
}
