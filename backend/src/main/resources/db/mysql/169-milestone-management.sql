-- ============================================================
-- 169-milestone-management.sql
-- change: add-milestone-management（M11.4 进度管理，设计 4.8 流程三）
--   1. erp_pm_milestone   里程碑（挂 WBS 阶段节点 level=1，六态状态机）
--   2. erp_pm_ms_review   评审记录（永久保留，只增不改）
--   3. M11-4 三叶菜单 PERM 幂等 UPDATE（沿 167 教训：不依赖 099 重生成落库）
-- ============================================================

-- ---------- 1. 里程碑 ----------
CREATE TABLE IF NOT EXISTS erp_pm_milestone (
    ID                  VARCHAR(32)     NOT NULL COMMENT '主键',
    PROJECT_ID          VARCHAR(32)     NOT NULL COMMENT '项目 ID',
    WBS_ID              VARCHAR(32)     NOT NULL COMMENT '挂载 WBS 阶段节点（level=1）',
    NAME                VARCHAR(128)    NOT NULL COMMENT '里程碑名称',
    PLAN_END_DATE       DATE            NOT NULL COMMENT '计划完成日期',
    REVIEW_CRITERIA     VARCHAR(1000)   NULL COMMENT '量化评审标准（文本，BR-4.8-15）',
    APPROVER_USER_ID    VARCHAR(32)     NULL COMMENT '审批人（展示用，审批节点角色=ROLE_PMO）',
    APPROVER_NAME       VARCHAR(64)     NULL COMMENT '审批人姓名（冗余展示）',
    PREV_MILESTONE_ID   VARCHAR(32)     NULL COMMENT '前序依赖里程碑（同项目，可空；BR-4.8-16）',
    STATUS              VARCHAR(16)     NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED|SUBMITTING|REVIEWING|APPROVED|DONE|REWORK',
    REWORK_COUNT        INT             NOT NULL DEFAULT 0 COMMENT '返工计数（FAIL +1；==2 升级 GM）',
    REVIEW_PASS_AT      DATETIME        NULL COMMENT '评审通过时间（审批超时基准）',
    ACTUAL_END_DATE     DATE            NULL COMMENT '实际完成日（DONE 时写）',
    DELAY_NOTIFIED      CHAR(1)         NOT NULL DEFAULT '0' COMMENT 'L4 滞后已通知标记',
    ESCALATED_NOTIFIED  CHAR(1)         NOT NULL DEFAULT '0' COMMENT '升级滞后已通知标记',
    OVERDUE_REMINDED    CHAR(1)         NOT NULL DEFAULT '0' COMMENT '审批催办已标记',
    APPR_ID             VARCHAR(32)     NULL COMMENT '审批实例 ID',
    REJECT_OPINION      VARCHAR(500)    NULL COMMENT '审批驳回意见',
    CREATE_BY           VARCHAR(64)     NULL,
    CREATE_DATE         DATETIME        NULL,
    UPDATE_BY           VARCHAR(64)     NULL,
    UPDATE_DATE         DATETIME        NULL,
    DEL_FLAG            CHAR(1)         NOT NULL DEFAULT '0',
    VER_NO              INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_MS_PROJECT (PROJECT_ID),
    KEY IDX_PM_MS_WBS (WBS_ID),
    KEY IDX_PM_MS_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目里程碑（add-milestone-management，FR-4.8-3-1）';

-- ---------- 2. 评审记录（只增不改，永久保留） ----------
CREATE TABLE IF NOT EXISTS erp_pm_ms_review (
    ID              VARCHAR(32)     NOT NULL COMMENT '主键',
    MILESTONE_ID    VARCHAR(32)     NOT NULL COMMENT '里程碑 ID',
    RESULT          VARCHAR(16)     NOT NULL COMMENT 'PASS|CONDITIONAL|FAIL',
    OPINION         VARCHAR(1000)   NULL COMMENT '评审意见',
    RECTIFY_ITEM    VARCHAR(500)    NULL COMMENT '整改项（CONDITIONAL 必填）',
    RECTIFY_DUE     DATE            NULL COMMENT '整改期限（CONDITIONAL 必填）',
    REWORK_PLAN     VARCHAR(1000)   NULL COMMENT '返工计划（FAIL 必填）',
    REVIEW_BY       VARCHAR(64)     NOT NULL COMMENT '评审人',
    REVIEW_DATE     DATETIME        NOT NULL COMMENT '评审时间',
    CREATE_BY       VARCHAR(64)     NULL,
    CREATE_DATE     DATETIME        NULL,
    UPDATE_BY       VARCHAR(64)     NULL,
    UPDATE_DATE     DATETIME        NULL,
    DEL_FLAG        CHAR(1)         NOT NULL DEFAULT '0',
    VER_NO          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_MS_REVIEW_MS (MILESTONE_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='里程碑评审记录（add-milestone-management，FR-4.8-3-3）';

-- ---------- 3. WBS 阶段完成标记（审批通过回写：阶段名下全部里程碑 DONE 才置位） ----------
-- 幂等 ALTER（information_schema 守卫，沿 047 惯例）
SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'erp_pm_wbs'
                       AND COLUMN_NAME = 'PHASE_DONE');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE erp_pm_wbs ADD COLUMN PHASE_DONE CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''阶段完成标记（里程碑全 DONE 由回调置 1）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col2_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                      WHERE TABLE_SCHEMA = DATABASE()
                        AND TABLE_NAME = 'erp_pm_wbs'
                        AND COLUMN_NAME = 'PHASE_DONE_DATE');
SET @ddl2 := IF(@col2_exists = 0,
    'ALTER TABLE erp_pm_wbs ADD COLUMN PHASE_DONE_DATE DATE NULL COMMENT ''阶段完成日期''',
    'SELECT 1');
PREPARE stmt2 FROM @ddl2;
EXECUTE stmt2;
DEALLOCATE PREPARE stmt2;

-- ---------- 4. M11-4 三叶菜单 PERM 幂等 UPDATE ----------
-- 只补空值（不覆盖人工调整）；167 教训：099 重生成的 INSERT IGNORE 对已执行库不生效
UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_PMO,ROLE_ADMIN'
 WHERE ID IN ('M11-4', 'M11-4-1', 'M11-4-2')
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-4-3'
   AND (PERM IS NULL OR PERM = '');
