package com.offerpilot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 解析接口通用请求体。
 */
public record ParseRequest(
        @NotBlank(message = "text 不能为空") String text
) {
}
