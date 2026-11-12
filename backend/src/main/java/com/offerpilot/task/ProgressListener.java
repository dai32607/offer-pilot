package com.offerpilot.task;

/**
 * 流水线进度监听器（需求说明书 6.4 SSE 事件来源）。
 * 由 {@code MatchService} 在编排各阶段时回调，由 TaskService 转换为 SSE 事件。
 */
public interface ProgressListener {

    /** 空实现：同步接口 /api/match 使用，不产生任何进度副作用。 */
    ProgressListener NOOP = new ProgressListener() {
        @Override
        public void onAgentStarted(String agentName, String model) {
        }

        @Override
        public void onAgentFinished(String agentName, long latencyMs, boolean retried,
                                    int attempts, int totalTokens) {
        }

        @Override
        public void onAgentFailed(String agentName, String errorCode, int retryCount) {
        }
    };

    /** 某 Agent 开始调用。 */
    void onAgentStarted(String agentName, String model);

    /**
     * 某 Agent 执行中的细粒度进度（流式消费时按增量回调；默认忽略，不影响既有实现）。
     *
     * @param agentName      Agent 名
     * @param elapsedMs      自该 Agent 开始以来的毫秒数
     * @param generatedChars 截至当前的已生成字符数
     */
    default void onAgentProgress(String agentName, long elapsedMs, int generatedChars) {
    }

    /** 某 Agent 完成且通过校验。 */
    void onAgentFinished(String agentName, long latencyMs, boolean retried,
                         int attempts, int totalTokens);

    /** 某 Agent 重试耗尽失败。 */
    void onAgentFailed(String agentName, String errorCode, int retryCount);
}
