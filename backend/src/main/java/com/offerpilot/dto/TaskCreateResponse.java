package com.offerpilot.dto;

/**
 * 创建任务响应（需求说明书 6.3 A03）：返回 taskId 与三条后续访问入口。
 */
public record TaskCreateResponse(
        String taskNo,
        String status,
        int progress,
        String streamUrl,
        String reportUrl,
        String eventsUrl
) {
}
