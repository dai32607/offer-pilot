package com.offerpilot.dto;

import java.util.List;
import java.util.Map;

/**
 * 报告接口响应（需求说明书 6.2 A07）：报告本体 + 执行元数据 + S6 板块占位。
 *
 * @param rewriteItems     改写建议条目：S5 阶段固定为空数组，S6 产出
 * @param rewriteNotice    占位说明文案
 * @param evidenceIndex    证据索引（evidenceId → 原文片段），前端用于风险项引用回填
 */
public record ReportView(
        MatchReport report,
        MatchResponse.MatchMeta meta,
        List<Map<String, Object>> rewriteItems,
        String rewriteNotice,
        List<MatchReport.MatchEvidence> evidenceIndex
) {
}
