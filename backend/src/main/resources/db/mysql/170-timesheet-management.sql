-- ============================================================
-- 170-timesheet-management.sql
-- change: add-timesheet-management（M11.5 工时管理，设计 4.8 流程四）
--   1. erp_pm_timesheet        工时单（单头，UK 同人同天一单）
--   2. erp_pm_timesheet_line   工时明细行（项目/WBS 任务/工时/摘要）
--   3. erp_pm_hours_collect    工时归集明细（审批通过写入，费率快照）
--   4. erp_pm_hours_alert      偏差预警标记（黄/红 + 已处理 + 升级）
--   5. erp_pm_rate 幂等 ALTER：加 EFF_FROM/EFF_TO 生效区间 + UK 降级
--      （原 UK_PM_RATE_GRADE 同职级唯一，与期间型多行费率矛盾 → 降普通索引）
--   6. SysParam HOURS_TOLERANCE_YELLOW/RED（BR-4.8-19 两级）
--   7. M11-5 四叶菜单 PERM 幂等 UPDATE（沿 167 教训）
-- ============================================================

-- ---------- 1. 工时单 ----------
CREATE TABLE IF NOT EXISTS erp_pm_timesheet (
    ID              VARCHAR(32)     NOT NULL COMMENT '主键',
    WORK_DATE       DATE            NOT NULL COMMENT '填报日期',
    EMP_ID          VARCHAR(32)     NOT NULL COMMENT '员工（登录账号映射）',
    EMP_NAME        VARCHAR(64)     NULL COMMENT '员工姓名（冗余）',
    DEPT_ID         VARCHAR(32)     NULL COMMENT '部门（展示）',
    STATUS          VARCHAR(16)     NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT|BACKFILL|SUBMITTED|APPROVED|REJECTED',
    TOTAL_HOURS     DECIMAL(6,1)    NOT NULL DEFAULT 0 COMMENT '单头合计（保存时重算）',
    IS_BACKFILL     CHAR(1)         NOT NULL DEFAULT '0' COMMENT '补录标记（BR-4.8-17 上周补录）',
    BACKFILL_APPR_ID VARCHAR(32)    NULL COMMENT '补录审批实例（超 2 周 L2）',
    SUBMIT_DATE     DATETIME        NULL COMMENT '提交时间',
    REVIEW_BY       VARCHAR(64)     NULL COMMENT '审核人',
    REVIEW_DATE     DATETIME        NULL COMMENT '审核时间',
    REJECT_REASON   VARCHAR(500)    NULL COMMENT '驳回原因（必填）',
    CREATE_BY       VARCHAR(64)     NULL,
    CREATE_DATE     DATETIME        NULL,
    UPDATE_BY       VARCHAR(64)     NULL,
    UPDATE_DATE     DATETIME        NULL,
    DEL_FLAG        CHAR(1)         NOT NULL DEFAULT '0',
    VER_NO          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_TS_EMP_DATE (EMP_ID, WORK_DATE),
    KEY IDX_PM_TS_STATUS (STATUS),
    KEY IDX_PM_TS_DATE (WORK_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工时单（add-timesheet-management，FR-4.8-4-1）';

-- ---------- 2. 工时明细行 ----------
CREATE TABLE IF NOT EXISTS erp_pm_timesheet_line (
    ID              VARCHAR(32)     NOT NULL COMMENT '主键',
    TIMESHEET_ID    VARCHAR(32)     NOT NULL COMMENT '工时单 ID',
    PROJECT_ID      VARCHAR(32)     NOT NULL COMMENT '项目',
    WBS_ID          VARCHAR(32)     NOT NULL COMMENT 'WBS 任务（level=3 校验，BR-4.8-18）',
    HOURS           DECIMAL(5,1)    NOT NULL COMMENT '工时（0.5 步长）',
    WORK_CONTENT    VARCHAR(500)    NOT NULL COMMENT '工作内容摘要',
    CREATE_BY       VARCHAR(64)     NULL,
    CREATE_DATE     DATETIME        NULL,
    UPDATE_BY       VARCHAR(64)     NULL,
    UPDATE_DATE     DATETIME        NULL,
    DEL_FLAG        CHAR(1)         NOT NULL DEFAULT '0',
    VER_NO          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_TSL_TS (TIMESHEET_ID),
    KEY IDX_PM_TSL_TASK (WBS_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工时明细行（add-timesheet-management）';

-- ---------- 3. 工时归集明细（只增不改，费率快照） ----------
CREATE TABLE IF NOT EXISTS erp_pm_hours_collect (
    ID              VARCHAR(32)     NOT NULL COMMENT '主键',
    TIMESHEET_ID    VARCHAR(32)     NOT NULL COMMENT '来源工时单',
    LINE_ID         VARCHAR(32)     NOT NULL COMMENT '来源明细行',
    EMP_ID          VARCHAR(32)     NOT NULL COMMENT '员工',
    EMP_NAME        VARCHAR(64)     NULL,
    GRADE           VARCHAR(32)     NULL COMMENT '职级快照（任务职级/项目覆盖/当前回落标注在 GRADE_SOURCE）',
    GRADE_SOURCE    VARCHAR(16)     NULL COMMENT 'TASK|PROJECT|CURRENT 费率来源（审计）',
    PROJECT_ID      VARCHAR(32)     NOT NULL,
    WBS_ID          VARCHAR(32)     NOT NULL,
    WORK_DATE       DATE            NOT NULL COMMENT '工时日期快照（费率匹配基准）',
    HOURS           DECIMAL(5,1)    NOT NULL,
    RATE            DECIMAL(10,2)   NOT NULL COMMENT '费率快照',
    LABOR_COST      DECIMAL(14,2)   NOT NULL COMMENT '人工成本 = HOURS × RATE',
    COLLECT_DATE    DATE            NOT NULL COMMENT '归集日',
    CREATE_BY       VARCHAR(64)     NULL,
    CREATE_DATE     DATETIME        NULL,
    UPDATE_BY       VARCHAR(64)     NULL,
    UPDATE_DATE     DATETIME        NULL,
    DEL_FLAG        CHAR(1)         NOT NULL DEFAULT '0',
    VER_NO          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_HC_PROJECT (PROJECT_ID),
    KEY IDX_PM_HC_WBS (WBS_ID),
    KEY IDX_PM_HC_TS (TIMESHEET_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工时归集明细（add-timesheet-management，FR-4.8-4-3）';

-- ---------- 4. 偏差预警标记 ----------
CREATE TABLE IF NOT EXISTS erp_pm_hours_alert (
    ID              VARCHAR(32)     NOT NULL COMMENT '主键',
    PROJECT_ID      VARCHAR(32)     NOT NULL COMMENT '项目',
    LEVEL           VARCHAR(8)      NOT NULL COMMENT 'YELLOW|RED（BR-4.8-19）',
    DEVIATION_RATE  DECIMAL(8,2)    NOT NULL COMMENT '触发时偏差率快照（%）',
    ALERT_DATE      DATE            NOT NULL COMMENT '预警生成日',
    HANDLED_FLAG    CHAR(1)         NOT NULL DEFAULT '0' COMMENT '已处理标记（PM 点按）',
    HANDLED_BY      VARCHAR(64)     NULL,
    HANDLED_NOTE    VARCHAR(500)    NULL COMMENT '处理说明（可附）',
    HANDLED_AT      DATETIME        NULL,
    ESCALATED_FLAG  CHAR(1)         NOT NULL DEFAULT '0' COMMENT '5 工作日未处理已升级标记',
    ESCALATED_AT    DATETIME        NULL,
    CREATE_BY       VARCHAR(64)     NULL,
    CREATE_DATE     DATETIME        NULL,
    UPDATE_BY       VARCHAR(64)     NULL,
    UPDATE_DATE     DATETIME        NULL,
    DEL_FLAG        CHAR(1)         NOT NULL DEFAULT '0',
    VER_NO          INT             NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_HA_PROJ_LVL (PROJECT_ID, LEVEL),
    KEY IDX_PM_HA_DATE (ALERT_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工时偏差预警（add-timesheet-management，C-4.8-03）';

-- ---------- 5. erp_pm_rate 幂等 ALTER：生效区间 + UK 降级 ----------
-- 5.1 加 EFF_FROM/EFF_TO（information_schema 守卫，沿 047/169 惯例）
SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_pm_rate'
               AND COLUMN_NAME = 'EFF_FROM');
SET @d1 := IF(@c1 = 0,
    'ALTER TABLE erp_pm_rate ADD COLUMN EFF_FROM DATE NOT NULL DEFAULT ''2026-01-01'' COMMENT ''生效自（含）'' AFTER RATE_PER_HOUR',
    'SELECT 1');
PREPARE s1 FROM @d1; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_pm_rate'
               AND COLUMN_NAME = 'EFF_TO');
SET @d2 := IF(@c2 = 0,
    'ALTER TABLE erp_pm_rate ADD COLUMN EFF_TO DATE NULL COMMENT ''生效至（含，NULL=至今）'' AFTER EFF_FROM',
    'SELECT 1');
PREPARE s2 FROM @d2; EXECUTE s2; DEALLOCATE PREPARE s2;

-- 5.2 UK_PM_RATE_GRADE 降级为普通索引：同职级多行期间型费率的前提
--     （守卫：索引存在才降级；沿 168 UK 冲突教训）
SET @uk := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_pm_rate'
               AND INDEX_NAME = 'UK_PM_RATE_GRADE');
SET @d3 := IF(@uk > 0,
    'ALTER TABLE erp_pm_rate DROP INDEX UK_PM_RATE_GRADE, ADD KEY IDX_PM_RATE_GRADE (GRADE)',
    'SELECT 1');
PREPARE s3 FROM @d3; EXECUTE s3; DEALLOCATE PREPARE s3;

-- ---------- 6. SysParam 偏差阈值（BR-4.8-19 两级） ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('PARAM-HOURS-TOL-YELLOW', 'HOURS_TOLERANCE_YELLOW', '10', 'NUMBER', 'PM', '工时偏差黄色预警阈值（%）', 'system', NOW(), '0', 0),
       ('PARAM-HOURS-TOL-RED', 'HOURS_TOLERANCE_RED', '20', 'NUMBER', 'PM', '工时偏差红色预警阈值（%）', 'system', NOW(), '0', 0);

-- ---------- 7. M11-5 四叶菜单 PERM 幂等 UPDATE ----------
-- 只补空值（不覆盖人工调整）；167 教训：099 重生成的 INSERT IGNORE 对已执行库不生效
UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_PMO,ROLE_ADMIN'
 WHERE ID IN ('M11-5', 'M11-5-1')
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_PMO,ROLE_DEPT_HEAD,ROLE_ADMIN'
 WHERE ID = 'M11-5-2'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M11-5-3', 'M11-5-4')
   AND (PERM IS NULL OR PERM = '');
