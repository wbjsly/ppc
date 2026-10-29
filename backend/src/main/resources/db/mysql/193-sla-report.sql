-- ============================================================
-- 193-sla-report.sql
-- change: add-sla-report-console（16.9 服务报告 / 系统 SLA 报告，
--         BR-5.6-16、C-5.6-05、全局卡控 C-0-01，design D1）
--   erp_sla_report 单表承载报告与状态机：
--     PERIOD YYYY-MM 唯一键（同月报告唯一）
--     METRICS_JSON 五类指标快照（归档即锁定，D2）
--     DATA_AS_OF 数据截止时间戳
--     STATUS 三态 DRAFT → ARCHIVED → PUBLISHED（单向）
--     归档/发布留痕 + PUBLISH_COUNT
--   菜单 path 核验：099 已是 /m/16.9.1~2，预期无 UPDATE
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS，可重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS erp_sla_report (
  ID              VARCHAR(32)  NOT NULL COMMENT '主键',
  PERIOD          VARCHAR(7)   NOT NULL COMMENT '报告月份 YYYY-MM（唯一锚）',
  METRICS_JSON    TEXT         DEFAULT NULL COMMENT '指标行快照 JSON（D2 五类：接口可用率/P95/错误率/审计写入/安全事件量）',
  DATA_AS_OF      DATETIME     DEFAULT NULL COMMENT '数据截止时间戳（生成时刻快照语义）',
  STATUS          VARCHAR(16)  NOT NULL DEFAULT 'DRAFT'
                  COMMENT 'DRAFT 草稿 / ARCHIVED 已归档（版本锁定）/ PUBLISHED 已发布',
  ARCHIVE_BY      VARCHAR(32)  DEFAULT NULL COMMENT '归档人（ADMIN）',
  ARCHIVE_AT      DATETIME     DEFAULT NULL COMMENT '归档时间',
  PUBLISH_BY      VARCHAR(32)  DEFAULT NULL COMMENT '发布人',
  PUBLISH_AT      DATETIME     DEFAULT NULL COMMENT '发布时间',
  PUBLISH_COUNT   INT          NOT NULL DEFAULT 0 COMMENT '发布次数（违规尝试也计，C-5.6-05 证据面）',
  CREATE_BY       VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE     DATETIME     DEFAULT NULL,
  UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE     DATETIME     DEFAULT NULL,
  DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO          INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_SLA_REPORT_PERIOD (PERIOD),
  KEY IDX_SLA_REPORT_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统月度 SLA 报告（16.9，BR-5.6-16 发布门禁，C-0-01 归档前置）';
