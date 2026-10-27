-- ============================================================
-- 174-rnd-material.sql
-- 研发物料领用与试制成本归集（M11.9，设计 4.8 流程十；change add-rnd-material）
--   ①erp_pm_wbs 加 TASK_TYPE（C-4.8-12 判断依据 + BR-4.8-37 分账基础）
--   ②erp_pm_mat_issue 加 TRIAL_FLAG/TRIAL_PURPOSE（同一张领料单复用扣库存/归集/凭证）
--   ③erp_pm_trial_consumption(+line) 消耗确认单（FR-4.8-10-3）
--   ④erp_pm_sample_ledger 样品台账（BR-4.8-39 批次关联）
--   ⑤erp_pm_trial_return(+line) 退料单（FR-4.8-10-4 质检结论+成本冲减）
--   ⑥M11-9 两叶 PERM 幂等 UPDATE
-- 幂等：ALTER 走 information_schema 守卫（沿 169 惯例）+ CREATE IF NOT EXISTS + UPDATE 只补空
-- ============================================================

-- ---------- 1. WBS 任务类型（C-4.8-12 / BR-4.8-37） ----------

SET @col_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'erp_pm_wbs'
                       AND COLUMN_NAME = 'TASK_TYPE');
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE erp_pm_wbs ADD COLUMN TASK_TYPE VARCHAR(10) NOT NULL DEFAULT ''DEV'' COMMENT ''任务类型：DEV 开发|TEST 测试|TRIAL 试制|OTHER（C-4.8-12 判断依据）''',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @idx_exists := (SELECT COUNT(*) FROM information_schema.STATISTICS
                     WHERE TABLE_SCHEMA = DATABASE()
                       AND TABLE_NAME = 'erp_pm_wbs'
                       AND INDEX_NAME = 'IDX_PM_WBS_TASKTYPE');
SET @ddl_idx := IF(@idx_exists = 0,
    'ALTER TABLE erp_pm_wbs ADD INDEX IDX_PM_WBS_TASKTYPE (TASK_TYPE)',
    'SELECT 1');
PREPARE stmt_idx FROM @ddl_idx;
EXECUTE stmt_idx;
DEALLOCATE PREPARE stmt_idx;

-- ---------- 2. 领料单试制标识（同一张单，BR-4.8-37 分账） ----------

SET @tf_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'erp_pm_mat_issue'
                      AND COLUMN_NAME = 'TRIAL_FLAG');
SET @ddl_tf := IF(@tf_exists = 0,
    'ALTER TABLE erp_pm_mat_issue ADD COLUMN TRIAL_FLAG CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''1=研发试制（BR-4.8-37 与量产分账，须关联 TRIAL 任务）''',
    'SELECT 1');
PREPARE stmt_tf FROM @ddl_tf;
EXECUTE stmt_tf;
DEALLOCATE PREPARE stmt_tf;

SET @tp_exists := (SELECT COUNT(*) FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA = DATABASE()
                      AND TABLE_NAME = 'erp_pm_mat_issue'
                      AND COLUMN_NAME = 'TRIAL_PURPOSE');
SET @ddl_tp := IF(@tp_exists = 0,
    'ALTER TABLE erp_pm_mat_issue ADD COLUMN TRIAL_PURPOSE VARCHAR(500) NULL COMMENT ''试制用途说明''',
    'SELECT 1');
PREPARE stmt_tp FROM @ddl_tp;
EXECUTE stmt_tp;
DEALLOCATE PREPARE stmt_tp;

-- ---------- 3. 试制消耗确认单（FR-4.8-10-3，一领料单一份服务层防重） ----------

CREATE TABLE IF NOT EXISTS erp_pm_trial_consumption (
    ID                VARCHAR(32) NOT NULL,
    ISSUE_ID          VARCHAR(32) NOT NULL COMMENT '关联试制领料单',
    PROJECT_ID        VARCHAR(32) NOT NULL,
    WBS_ID            VARCHAR(32) NULL,
    REPORT_ATTACH_ID  VARCHAR(32) NULL COMMENT '试制报告附件（BIZ_TYPE=TRIAL_REPORT）',
    TOTAL_ISSUED      DECIMAL(14,3) NOT NULL DEFAULT 0 COMMENT '领用数汇总快照',
    TOTAL_ACTUAL      DECIMAL(14,3) NOT NULL DEFAULT 0 COMMENT '实耗数汇总快照',
    TOTAL_SURPLUS     DECIMAL(14,3) NOT NULL DEFAULT 0 COMMENT '结余数汇总快照',
    DEVIATION_FLAG    CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=有差异>10% 未说明',
    REMARK            VARCHAR(500) NULL,
    STATUS            VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED' COMMENT 'CONFIRMED（确认即终态，BR-4.8-26 惯例禁改）',
    CONFIRM_BY        VARCHAR(64) NULL,
    CONFIRM_DATE      DATETIME NULL,
    UPDATE_BY         VARCHAR(64) NULL,
    UPDATE_DATE       DATETIME NULL,
    CREATE_BY         VARCHAR(64) NULL,
    CREATE_DATE       DATETIME NULL,
    DEL_FLAG          CHAR(1) NOT NULL DEFAULT '0',
    VER_NO            INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_TC_ISSUE (ISSUE_ID),
    KEY IDX_PM_TC_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试制消耗确认单（add-rnd-material，FR-4.8-10-3）';

-- ---------- 4. 实际消耗行 ----------

CREATE TABLE IF NOT EXISTS erp_pm_trial_consumption_line (
    ID              VARCHAR(32) NOT NULL,
    CONSUMPTION_ID  VARCHAR(32) NOT NULL,
    ISSUE_LINE_ID   VARCHAR(32) NULL COMMENT '关联领料行',
    ITEM_ID         VARCHAR(64) NOT NULL,
    ITEM_NAME       VARCHAR(200) NULL,
    ISSUED_QTY      DECIMAL(14,3) NOT NULL DEFAULT 0 COMMENT '领用数',
    ACTUAL_QTY      DECIMAL(14,3) NOT NULL DEFAULT 0 COMMENT '实耗数',
    SURPLUS_QTY     DECIMAL(14,3) NOT NULL DEFAULT 0 COMMENT '结余=领用-实耗（服务端算）',
    UNIT_PRICE      DECIMAL(14,4) NULL COMMENT '原领用单价快照（退库冲减依据）',
    DEVIATION_NOTE  VARCHAR(500) NULL COMMENT '差异说明（>10% 时建议填写，空则 L4）',
    UPDATE_BY       VARCHAR(64) NULL,
    UPDATE_DATE     DATETIME NULL,
    CREATE_BY       VARCHAR(64) NULL,
    CREATE_DATE     DATETIME NULL,
    DEL_FLAG        CHAR(1) NOT NULL DEFAULT '0',
    VER_NO          INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_TCL_CONSUMPTION (CONSUMPTION_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试制实际消耗行（add-rnd-material）';

-- ---------- 5. 样品台账（BR-4.8-39 批次关联可追溯） ----------

CREATE TABLE IF NOT EXISTS erp_pm_sample_ledger (
    ID             VARCHAR(32) NOT NULL,
    CONSUMPTION_ID VARCHAR(32) NULL,
    ISSUE_ID       VARCHAR(32) NOT NULL COMMENT '关联试制领料单',
    SAMPLE_NO      VARCHAR(32) NULL COMMENT '样品编号',
    NAME           VARCHAR(200) NOT NULL COMMENT '样品名称',
    BATCH_NO       VARCHAR(64) NULL COMMENT '关联领料批次（BR-4.8-39 用料来源追溯）',
    ITEM_ID        VARCHAR(64) NULL,
    QTY            DECIMAL(14,3) NOT NULL DEFAULT 1,
    DESTINATION    VARCHAR(20) NOT NULL COMMENT 'TEST 测试|CUSTOMER 送客户|RETAIN 留样|SCRAP 报废',
    STATUS         VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' COMMENT 'AVAILABLE|USED|SCRAPPED',
    REGISTRER      VARCHAR(64) NULL,
    REMARK         VARCHAR(500) NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_SL_ISSUE (ISSUE_ID),
    KEY IDX_PM_SL_BATCH (BATCH_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='样品台账（add-rnd-material，BR-4.8-39）';

-- ---------- 6. 试制退料单（FR-4.8-10-4） ----------

CREATE TABLE IF NOT EXISTS erp_pm_trial_return (
    ID            VARCHAR(32) NOT NULL,
    RETURN_NO     VARCHAR(32) NOT NULL COMMENT 'RTR+yyyyMMdd-序号',
    ISSUE_ID      VARCHAR(32) NOT NULL COMMENT '关联试制领料单',
    PROJECT_ID    VARCHAR(32) NOT NULL,
    STATUS        VARCHAR(20) NOT NULL COMMENT 'DRAFT|SUBMITTED|APPROVED|REJECTED',
    REVIEW_BY     VARCHAR(64) NULL,
    REVIEW_DATE   DATETIME NULL,
    REJECT_REASON VARCHAR(500) NULL,
    VOUCHER_ID    VARCHAR(32) NULL COMMENT '成本冲减凭证回写',
    UPDATE_BY     VARCHAR(64) NULL,
    UPDATE_DATE   DATETIME NULL,
    CREATE_BY     VARCHAR(64) NULL,
    CREATE_DATE   DATETIME NULL,
    DEL_FLAG      CHAR(1) NOT NULL DEFAULT '0',
    VER_NO        INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_TR_ISSUE (ISSUE_ID),
    KEY IDX_PM_TR_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试制退料单（add-rnd-material，FR-4.8-10-4）';

-- ---------- 7. 退库行（质检结论 + 原单价快照） ----------

CREATE TABLE IF NOT EXISTS erp_pm_trial_return_line (
    ID            VARCHAR(32) NOT NULL,
    RETURN_ID     VARCHAR(32) NOT NULL,
    ITEM_ID       VARCHAR(64) NOT NULL,
    ITEM_NAME     VARCHAR(200) NULL,
    BATCH_NO      VARCHAR(64) NULL COMMENT '退库批次（跨项目挪用校验依据 BR-4.8-04）',
    RETURN_QTY    DECIMAL(14,3) NOT NULL DEFAULT 0 COMMENT '退库数（不得超过结余）',
    UNIT_PRICE    DECIMAL(14,4) NULL COMMENT '原领用单价快照',
    AMOUNT        DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '冲减额=退库数×原单价',
    QC_RESULT     VARCHAR(20) NOT NULL COMMENT 'REUSABLE 可再用|SCRAP 报废',
    RETURNED_FLAG CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=已入库冲减（防重复）',
    UPDATE_BY     VARCHAR(64) NULL,
    UPDATE_DATE   DATETIME NULL,
    CREATE_BY     VARCHAR(64) NULL,
    CREATE_DATE   DATETIME NULL,
    DEL_FLAG      CHAR(1) NOT NULL DEFAULT '0',
    VER_NO        INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_TRL_RETURN (RETURN_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='试制退库行（add-rnd-material）';

-- ---------- 8. M11-9 两叶菜单 PERM（幂等 UPDATE 只补空值） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_ADMIN'
 WHERE ID = 'M11-9-1'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_RD_LEAD,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-9-2'
   AND (PERM IS NULL OR PERM = '');

-- ---------- 9. 试制退库入库单据类型（REUSABLE 结余入库，IN 方向） ----------

INSERT IGNORE INTO erp_inv_doc_type (ID, TYPE_CODE, TYPE_NAME, DIRECTION, FLOW_PREFIX, NEED_BATCH, ENABLED, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('doctype-trial-return-in', 'TRIAL_RETURN_IN', '试制退料入库', 'IN', 'TRT', 0, 1,
        '试制结余可再用物料退库入库（add-rnd-material，FR-4.8-10-4）', 'migration', NOW(), '0', 0);
