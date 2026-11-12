-- ============================================================================
-- OfferPilot · S6 阶段建表脚本（需求说明书 5.6 interview_session）
-- 目标库：MySQL 8.0.x（utf8mb4）
-- 说明：运行时若未接入 MySQL（当前后端以内存态 InterviewSessionStore 承载会话，
--      字段与列语义与下表一一对应），本脚本作为目标库建表与字段对照依据保留。
-- ============================================================================

CREATE TABLE IF NOT EXISTS interview_session (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '会话主键',
    task_id          BIGINT UNSIGNED NOT NULL               COMMENT '所属任务',
    report_id        BIGINT UNSIGNED NOT NULL               COMMENT '关联匹配报告',
    status           VARCHAR(16)     NOT NULL DEFAULT 'ONGOING' COMMENT 'ONGOING/FINISHED/ABORTED',
    round_no         TINYINT UNSIGNED NOT NULL DEFAULT 0    COMMENT '已进行轮次',
    max_round        TINYINT UNSIGNED NOT NULL DEFAULT 5    COMMENT '最大轮次',
    transcript_json  JSON            NOT NULL               COMMENT '问答记录 {round, question, answer, evaluation}',
    score_json       JSON            NULL                   COMMENT '汇总评分（3.6 各轮 evaluation 聚合）',
    summary_md       TEXT            NULL                   COMMENT '面试总结与改进建议',
    started_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    finished_at      DATETIME(3)     NULL,
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_session_task (task_id),
    KEY idx_session_report (report_id),
    CONSTRAINT fk_session_task   FOREIGN KEY (task_id)   REFERENCES analysis_task (id),
    CONSTRAINT fk_session_report FOREIGN KEY (report_id) REFERENCES match_report (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '模拟面试会话';

-- ----------------------------------------------------------------------------
-- 字段 ↔ 运行时模型对照（InterviewSession / InterviewTurn）
--   id              ↔ InterviewSession.id
--   task_id         ↔ InterviewSession.taskNo（analysis_task.task_no）
--   report_id       ↔ InterviewSession.taskNo 关联的 match_report（当前内存态由任务结果持有）
--   status          ↔ InterviewSession.status（ONGOING/FINISHED/ABORTED）
--   round_no        ↔ InterviewSession.roundNo（每轮写入后累加）
--   max_round       ↔ InterviewSession.maxRound（A10 入参 max_round，上限见 offerpilot.interview.max-round-limit）
--   transcript_json ↔ InterviewSession.transcript（InterviewTurn 列表）
--   score_json      ↔ InterviewSession.score（InterviewService.aggregate 聚合结果）
--   summary_md      ↔ InterviewSession.summaryMd（A13 finish 生成）
--   started_at      ↔ InterviewSession.createdAt
--   finished_at     ↔ InterviewSession.finishedAt
-- ----------------------------------------------------------------------------
