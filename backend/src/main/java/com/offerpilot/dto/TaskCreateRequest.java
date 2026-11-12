package com.offerpilot.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotBlank;

/**
 * 创建分析任务请求（需求说明书 6.2 A03）。
 * <p>
 * S5 阶段直接接收 JD 原文与简历原文（与同步接口 /api/match 同源），
 * 文件上传与 resume_id/jd_id 关联留待 S6 接入。
 */
public record TaskCreateRequest(
        @JsonAlias("task_no") String taskNo,
        @NotBlank(message = "不能为空") String jdText,
        @NotBlank(message = "不能为空") String resumeText,
        Options options
) {

    /** 任务可选项：needRewrite / needInterview 在 S5 仅记录，不参与执行（对应 S6）。 */
    public record Options(
            @JsonAlias("need_rewrite") Boolean needRewrite,
            @JsonAlias("need_interview") Boolean needInterview
    ) {
    }
}
