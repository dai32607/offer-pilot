package com.offerpilot.common;

import lombok.Getter;

/**
 * 业务错误码枚举。
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "成功"),
    INVALID_PARAM(400, "参数不合法"),
    SYSTEM_ERROR(500, "系统错误"),
    /** 兼容历史同步接口 /api/match 的错误码。 */
    LLM_CALL_FAILED(1001, "大模型调用失败"),

    /** 需求说明书 6.1 错误码表：参数校验失败。 */
    PARAM_INVALID(40001, "参数校验失败"),
    /** 任务 / 报告不存在。 */
    NOT_FOUND(40004, "资源不存在"),
    /** 任务正在执行，禁止重复触发。 */
    TASK_RUNNING(40009, "任务正在执行，禁止重复触发"),
    /** Agent 输出未通过 Schema 校验。 */
    SCHEMA_INVALID(40022, "Agent 输出未通过 Schema 校验"),
    /** 上游模型调用失败。 */
    UPSTREAM_FAILED(40501, "上游模型调用失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
