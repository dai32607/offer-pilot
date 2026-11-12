package com.offerpilot.dto;

import java.util.List;

/**
 * 匹配评估接口响应：报告本体 + 各阶段执行元数据（供成本统计与复盘）。
 */
public record MatchResponse(
        MatchReport report,
        MatchMeta meta
) {

    public record MatchMeta(
            StageMeta jdParse,
            StageMeta resumeParse,
            StageMeta matchEval,
            String matchModel,
            int dimensionCount,
            int uncertaintyCount,
            List<String> invalidDimensions,
            boolean renormalized,
            String retryReason
    ) {
    }

    /** 单个阶段的执行元数据：token 累计值含重试，attempts=1 表示未重试。 */
    public record StageMeta(
            String agent,
            String model,
            long elapsedMs,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int attempts,
            boolean retried
    ) {
    }
}
