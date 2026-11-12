package com.offerpilot.dto;

/**
 * 任务状态视图（需求说明书 6.2 A05）：status / progress / current_agent / failed_agent / token 汇总。
 */
public record TaskStatusView(
        String taskNo,
        String status,
        int progress,
        String currentAgent,
        String failedAgent,
        int tokenTotal,
        long durationMs,
        String createdAt,
        int totalAgents,
        int finishedAgents,
        boolean reportReady,
        long lastSeq
) {
}
