package com.offerpilot.task;

/**
 * 一条已发布的 SSE 事件（持久化于任务内，用于断线后按 seq 补齐）。
 *
 * @param seq      事件序号，任务内自增且严格单调（作为 SSE 的 id 字段）
 * @param name     事件名，如 agent.started / report.ready
 * @param dataJson data 载荷（JSON 字符串，已含 task_no、seq、ts）
 * @param ts       事件时间（ISO-8601）
 */
public record TaskEvent(long seq, String name, String dataJson, String ts) {
}
