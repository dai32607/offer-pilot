package com.offerpilot.agent;

/**
 * Agent 单次解析的执行元数据与结果。
 *
 * @param data             解析出的结构化对象
 * @param promptTokens     累计 prompt token（含重试）
 * @param completionTokens 累计 completion token（含重试）
 * @param totalTokens      累计 token 总量（含重试）
 * @param elapsedMs        端到端耗时（毫秒）
 * @param attempts         实际调用大模型次数（1=未重试，2=触发重试）
 * @param retried          是否命中「校验失败回传重试」
 * @param firstError       首次校验失败的错误信息（未重试时为 null）
 */
public record AgentResult<T>(
        T data,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long elapsedMs,
        int attempts,
        boolean retried,
        String firstError
) {
}
