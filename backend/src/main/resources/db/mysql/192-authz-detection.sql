-- ============================================================
-- 192-authz-detection.sql
-- change: add-authz-detection-console（16.8 越权检测，BR-5.6-11、S-5.6-06）
--   1. erp_authz_incident 越权事件表（告警与降权 1:1 合表，design D1）
--      触发计数/阈值/到期（默认 +7d）/状态 ACTIVE·RESTORED·DISABLED
--      /处置三字段（MISUSE 误用 / RESTORE 提前恢复 / MALICIOUS 恶意）
--      /结构化结论 ≥10 字/培训标记
--   2. erp_sys_param 登记 AUTHZ_DENY_BURST_THRESHOLD（'10'，5 分钟窗口，
--      安全负责人归口，SECURITY 组）——参数改动对后续判定生效（D8）
--   3. 菜单 path 核验：099 已是 /m/16.8.1~2，预期无 UPDATE
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE，可重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS erp_authz_incident (
  ID              VARCHAR(32)  NOT NULL COMMENT '主键',
  TARGET_USER_ID  VARCHAR(32)  NOT NULL COMMENT '触发账号',
  HIT_COUNT       INT          NOT NULL DEFAULT 0 COMMENT '触发时 5 分钟窗口内 403 计数',
  THRESHOLD       INT          NOT NULL DEFAULT 10 COMMENT '触发阈值（AUTHZ_DENY_BURST_THRESHOLD 快照）',
  TRIGGERED_AT    DATETIME     NOT NULL COMMENT '触发时刻',
  EXPIRE_AT       DATETIME     NOT NULL COMMENT '降权到期（默认触发 +7 天）',
  STATUS          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
                  COMMENT 'ACTIVE 降权中 / RESTORED 已恢复 / DISABLED 已禁用（恶意处置，标记保留至到期）',
  DISPOSITION     VARCHAR(16)  DEFAULT NULL
                  COMMENT 'MISUSE 误用（待到期恢复）/ RESTORE 提前恢复 / MALICIOUS 恶意',
  CONCLUSION      VARCHAR(500) DEFAULT NULL COMMENT '结构化处置结论（≥10 字）',
  TRAINING_FLAG   VARCHAR(1)   NOT NULL DEFAULT '0' COMMENT '1=要求参加数据安全培训（误用处置）',
  DISPOSITION_BY  VARCHAR(32)  DEFAULT NULL COMMENT '处置人',
  DISPOSITION_AT  DATETIME     DEFAULT NULL COMMENT '处置时间',
  RESTORE_BY      VARCHAR(32)  DEFAULT NULL COMMENT '恢复人（提前恢复=操作人；到期恢复=系统）',
  RESTORE_AT      DATETIME     DEFAULT NULL COMMENT '恢复时间',
  RESTORE_REASON  VARCHAR(200) DEFAULT NULL COMMENT '恢复方式/说明（到期自动 / 手动提前+说明）',
  CREATE_BY       VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE     DATETIME     DEFAULT NULL,
  UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE     DATETIME     DEFAULT NULL,
  DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO          INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  KEY IDX_AUTHZ_INCIDENT_USER (TARGET_USER_ID, STATUS),
  KEY IDX_AUTHZ_INCIDENT_EXPIRE (STATUS, EXPIRE_AT)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='越权探测事件（BR-5.6-11：告警与临时降权 1:1 合表，S-5.6-06 处置闭环）';

-- ============================================================
-- 2. 阈值参数（4.0.3 未登记，本次新增；安全负责人归口）
-- ============================================================
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK,
                                  HISTORY_JSON, CREATE_BY, CREATE_DATE, UPDATE_BY, UPDATE_DATE, DEL_FLAG, VER_NO)
VALUES ('param-authz-deny-burst', 'AUTHZ_DENY_BURST_THRESHOLD', '10', 'NUMBER', 'SECURITY',
        '越权探测告警阈值（同账号 5 分钟滚动窗口内 403 拒绝次数；BR-5.6-11）',
        NULL, 'migrate-192', NOW(), 'migrate-192', NOW(), '0', 0);
