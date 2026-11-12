package com.offerpilot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 匹配评估请求：输入 JD 原文与简历原文，由服务端内部编排「解析 → 评估」。
 * seed 为可选固定随机种子（评测重跑用），null 表示不传。
 */
public record MatchRequest(
        @NotBlank(message = "不能为空") String jdText,
        @NotBlank(message = "不能为空") String resumeText,
        Integer seed
) {
    /** 兼容旧调用：不传 seed。 */
    public MatchRequest(String jdText, String resumeText) {
        this(jdText, resumeText, null);
    }
}
