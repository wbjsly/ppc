-- ============================================================
-- 190-access-review.sql
-- change: add-access-review-console（16.6 权限复核 SOP-5.6-D，design D1）
--   三张新表：
--     erp_admin_uar_task    季度复核任务主单（截止/状态/升级幂等标记）
--     erp_admin_uar_item    账号×角色逐项（快照 JSON 固化/复核人二态/判定/执行）
--     erp_admin_uar_report  季度报告（统计 + ADMIN 确认 + 内审会签双签）
--   种子：ROLE_UAR_AUDITOR 内审角色 + 授予 admin（冒烟兜底，决策甲）
-- ============================================================
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE，可重跑
-- ============================================================

CREATE TABLE IF NOT EXISTS erp_admin_uar_task (
  ID            VARCHAR(32)  NOT NULL COMMENT '主键',
  CYCLE_TAG     VARCHAR(16)  NOT NULL COMMENT '周期标识（如 2026Q4）',
  DUE_AT        DATETIME     NOT NULL COMMENT '完成截止时间（生成 +10 工作日口径）',
  STATUS        VARCHAR(16)  NOT NULL DEFAULT 'REVIEWING'
                COMMENT 'REVIEWING 复核中 / COMPLETED 已完成待归档 / ARCHIVED 已归档',
  ESCALATED_AT  DATETIME     DEFAULT NULL COMMENT '逾期升级幂等标记（BR-5.6-10）',
  REMIND_AT     DATETIME     DEFAULT NULL COMMENT '逾期重复提醒标记（12h）',
  CREATE_BY     VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE   DATETIME     DEFAULT NULL,
  UPDATE_BY     VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE   DATETIME     DEFAULT NULL,
  DEL_FLAG      VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO        INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_UAR_CYCLE (CYCLE_TAG)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='季度最小权限复核任务（SOP-5.6-D step1）';

CREATE TABLE IF NOT EXISTS erp_admin_uar_item (
  ID                VARCHAR(32)   NOT NULL COMMENT '主键',
  TASK_ID           VARCHAR(32)   NOT NULL COMMENT '所属任务',
  TARGET_USER_ID    VARCHAR(32)   NOT NULL COMMENT '被复核账号',
  ROLE_CODE         VARCHAR(64)   NOT NULL COMMENT '被复核角色',
  RISK_TAG          VARCHAR(16)   NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL/SENSITIVE/PRIVILEGED（静态清单映射）',
  SNAPSHOT_JSON     VARCHAR(2000) DEFAULT NULL COMMENT '生成时刻快照（角色清单/基线偏离/使用统计/三异常标记）',
  REVIEWER_USER_ID  VARCHAR(32)   DEFAULT NULL COMMENT '按人复核人（REPORT_TO 主管）',
  REVIEWER_ROLE     VARCHAR(64)   DEFAULT NULL COMMENT '角色态复核人（DEPT_HEAD/ADMIN 回落）',
  JUDGMENT          VARCHAR(16)   DEFAULT NULL COMMENT 'KEEP 保留 / RECLAIM 收回（空=待复核）',
  JUDGE_REASON      VARCHAR(500)  DEFAULT NULL COMMENT '判定理由（保留未使用/超基线角色必填 ≥10 字）',
  JUDGED_AT         DATETIME      DEFAULT NULL COMMENT '判定时间',
  EXEC_STATUS       VARCHAR(16)   NOT NULL DEFAULT 'PENDING'
                    COMMENT 'PENDING 待执行 / EXECUTED 已回收 / SKIPPED 已保留',
  CREATE_BY         VARCHAR(64)   DEFAULT NULL,
  CREATE_DATE       DATETIME      DEFAULT NULL,
  UPDATE_BY         VARCHAR(64)   DEFAULT NULL,
  UPDATE_DATE       DATETIME      DEFAULT NULL,
  DEL_FLAG          VARCHAR(1)    NOT NULL DEFAULT '0',
  VER_NO            INT           NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_UAR_ITEM (TASK_ID, TARGET_USER_ID, ROLE_CODE),
  KEY IDX_UAR_ITEM_TASK (TASK_ID, JUDGMENT),
  KEY IDX_UAR_ITEM_REVIEWER (REVIEWER_USER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='复核项（账号×角色逐项判定，SOP-5.6-D step4）';

CREATE TABLE IF NOT EXISTS erp_admin_uar_report (
  ID              VARCHAR(32)  NOT NULL COMMENT '主键',
  TASK_ID         VARCHAR(32)  NOT NULL COMMENT '所属任务（唯一锚，幂等）',
  TOTAL_ITEMS     INT          NOT NULL DEFAULT 0 COMMENT '总项数（人工清单角色总数）',
  JUDGED_ITEMS    INT          NOT NULL DEFAULT 0 COMMENT '已判定项数',
  COVERAGE_RATE   DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '复核覆盖率（%）',
  RECLAIMED_ITEMS INT          NOT NULL DEFAULT 0 COMMENT '收回项数',
  RECLAIM_RATE    DECIMAL(5,2) NOT NULL DEFAULT 0 COMMENT '收回率（%）',
  ANOMALY_JSON    VARCHAR(4000) DEFAULT NULL COMMENT '三类异常清单 JSON',
  PENDING_JSON    VARCHAR(4000) DEFAULT NULL COMMENT '未完成项清单 JSON',
  CONFIRM_BY      VARCHAR(32)  DEFAULT NULL COMMENT 'ADMIN 确认人',
  CONFIRM_AT      DATETIME     DEFAULT NULL COMMENT 'ADMIN 确认时间',
  SIGN_BY         VARCHAR(32)  DEFAULT NULL COMMENT '内审会签人（ROLE_UAR_AUDITOR）',
  SIGN_AT         DATETIME     DEFAULT NULL COMMENT '内审会签时间',
  ARCHIVED        CHAR(1)      NOT NULL DEFAULT '0' COMMENT '1 已归档（双签齐备）',
  CREATE_BY       VARCHAR(64)  DEFAULT NULL,
  CREATE_DATE     DATETIME     DEFAULT NULL,
  UPDATE_BY       VARCHAR(64)  DEFAULT NULL,
  UPDATE_DATE     DATETIME     DEFAULT NULL,
  DEL_FLAG        VARCHAR(1)   NOT NULL DEFAULT '0',
  VER_NO          INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (ID),
  UNIQUE KEY UK_UAR_REPORT_TASK (TASK_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='季度 UAR 报告（SOP-5.6-D step6，C-5.6-05 双签归档）';

-- ============================================================
-- 2. 内审角色（报告会签，决策甲）+ 授予 admin（冒烟/测试兜底）
-- ============================================================
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('role-uar-auditor', 'ROLE_UAR_AUDITOR', '复核内审', 37, '1', 'migrate-190', NOW(), '0', 0);

INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID)
VALUES ('ur-admin-uar-auditor', 'user-admin', 'role-uar-auditor');
