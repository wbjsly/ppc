-- ============================================================
-- 171-cost-collection-freeze.sql
-- 预算管控（M11.6，设计 4.8 流程五；change add-cost-collection-freeze）
--   ①项目领料单+行 ②外协合同台账+付款单 ③差旅标准+报销单
--   ④间接分摊规则+费用池+分摊明细 ⑤五类成本统一归集 ⑥成本偏差预警
--   ⑦超预算审批申请 ⑧erp_pm_project 幂等 ALTER 加冻结三列
--   ⑨SysParam COST_TOLERANCE ⑩ROLE_EXEC 角色 ⑪M11-6 三叶 PERM
--   ⑫差旅标准三档种子
-- 幂等：CREATE TABLE IF NOT EXISTS + information_schema 守卫 ALTER + INSERT IGNORE/UPDATE 只补空
-- ============================================================

-- ---------- 1. 项目领料单（FR-4.8-5-1；材料成本） ----------

CREATE TABLE IF NOT EXISTS erp_pm_mat_issue (
    ID            VARCHAR(32) NOT NULL,
    ISSUE_NO      VARCHAR(32) NOT NULL COMMENT 'PMI+yyyyMMdd-序号',
    PROJECT_ID    VARCHAR(32) NOT NULL,
    ISSUE_DATE    DATE NOT NULL COMMENT '领用日期',
    REMARK        VARCHAR(500) NULL,
    STATUS        VARCHAR(20) NOT NULL COMMENT 'DRAFT|SUBMITTED|APPROVED|REJECTED',
    REVIEW_BY     VARCHAR(64) NULL,
    REVIEW_DATE   DATETIME NULL,
    REJECT_REASON VARCHAR(500) NULL,
    VOUCHER_ID    VARCHAR(32) NULL COMMENT '会计凭证回写',
    CREATE_BY     VARCHAR(64) NULL,
    CREATE_DATE   DATETIME NULL,
    UPDATE_BY     VARCHAR(64) NULL,
    UPDATE_DATE   DATETIME NULL,
    DEL_FLAG      CHAR(1) NOT NULL DEFAULT '0',
    VER_NO        INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_MI_NO (ISSUE_NO),
    KEY IDX_PM_MI_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目领料单（add-cost-collection-freeze）';

CREATE TABLE IF NOT EXISTS erp_pm_mat_issue_line (
    ID         VARCHAR(32) NOT NULL,
    ISSUE_ID   VARCHAR(32) NOT NULL,
    WBS_ID     VARCHAR(32) NOT NULL COMMENT '任务级（BR-4.8-20 必填）',
    ITEM_ID    VARCHAR(64) NOT NULL COMMENT '物料编码',
    ITEM_NAME  VARCHAR(200) NULL,
    BATCH_NO   VARCHAR(64) NULL,
    WAREHOUSE_CODE VARCHAR(64) NOT NULL COMMENT '扣库存仓库',
    QTY        DECIMAL(14,3) NOT NULL,
    UNIT_PRICE DECIMAL(14,4) NOT NULL COMMENT '单价快照（库存无成本列，由录入携带）',
    AMOUNT     DECIMAL(14,2) NOT NULL COMMENT '=QTY×UNIT_PRICE',
    CREATE_BY  VARCHAR(64) NULL,
    CREATE_DATE DATETIME NULL,
    UPDATE_BY  VARCHAR(64) NULL,
    UPDATE_DATE DATETIME NULL,
    DEL_FLAG   CHAR(1) NOT NULL DEFAULT '0',
    VER_NO     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_MI_LINE_ISSUE (ISSUE_ID),
    KEY IDX_PM_MI_LINE_WBS (WBS_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目领料明细行';

-- ---------- 2. 外协合同台账 + 付款单（FR-4.8-5-2） ----------

CREATE TABLE IF NOT EXISTS erp_pm_subcontract (
    ID             VARCHAR(32) NOT NULL,
    CONTRACT_NO    VARCHAR(32) NOT NULL COMMENT 'PSC+yyyyMMdd-序号',
    PROJECT_ID     VARCHAR(32) NOT NULL,
    WBS_ID         VARCHAR(32) NULL COMMENT '可空（L4 标记 NO_WBS_FLAG）',
    NO_WBS_FLAG    CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=未挂 WBS（L4 提示）',
    SUPPLIER_ID    VARCHAR(64) NULL,
    SUPPLIER_NAME  VARCHAR(200) NULL,
    TOTAL_AMOUNT   DECIMAL(14,2) NOT NULL COMMENT '含税总额',
    PAID_AMOUNT    DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '已付累计',
    STATUS         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE|CLOSED',
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_SC_NO (CONTRACT_NO),
    KEY IDX_PM_SC_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外协合同台账';

CREATE TABLE IF NOT EXISTS erp_pm_sub_payment (
    ID            VARCHAR(32) NOT NULL,
    PAY_NO        VARCHAR(32) NOT NULL COMMENT 'SPP+yyyyMMdd-序号',
    CONTRACT_ID   VARCHAR(32) NOT NULL,
    PROJECT_ID    VARCHAR(32) NOT NULL,
    WBS_ID        VARCHAR(32) NULL COMMENT '合同冗余（空则归项目级）',
    AMOUNT        DECIMAL(14,2) NOT NULL,
    PAY_DATE      DATE NOT NULL,
    STATUS        VARCHAR(20) NOT NULL COMMENT 'DRAFT|SUBMITTED|APPROVED|REJECTED',
    REVIEW_BY     VARCHAR(64) NULL,
    REVIEW_DATE   DATETIME NULL,
    REJECT_REASON VARCHAR(500) NULL,
    VOUCHER_ID    VARCHAR(32) NULL,
    CREATE_BY     VARCHAR(64) NULL,
    CREATE_DATE   DATETIME NULL,
    UPDATE_BY     VARCHAR(64) NULL,
    UPDATE_DATE   DATETIME NULL,
    DEL_FLAG      CHAR(1) NOT NULL DEFAULT '0',
    VER_NO        INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_SP_NO (PAY_NO),
    KEY IDX_PM_SP_CONTRACT (CONTRACT_ID),
    KEY IDX_PM_SP_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外协付款单';

-- ---------- 3. 职级差旅标准 + 差旅报销单（FR-4.8-5-3） ----------

CREATE TABLE IF NOT EXISTS erp_pm_travel_standard (
    ID            VARCHAR(32) NOT NULL,
    GRADE         VARCHAR(32) NOT NULL COMMENT '职级 CODE',
    CITY_CATEGORY VARCHAR(32) NOT NULL COMMENT '一线|二线|其他',
    DAILY_LIMIT   DECIMAL(10,2) NOT NULL COMMENT '日限额',
    STATUS        VARCHAR(1) NOT NULL DEFAULT '1',
    CREATE_BY     VARCHAR(64) NULL,
    CREATE_DATE   DATETIME NULL,
    UPDATE_BY     VARCHAR(64) NULL,
    UPDATE_DATE   DATETIME NULL,
    DEL_FLAG      CHAR(1) NOT NULL DEFAULT '0',
    VER_NO        INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_TS_GRADE (GRADE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='职级差旅标准';

CREATE TABLE IF NOT EXISTS erp_pm_travel_claim (
    ID               VARCHAR(32) NOT NULL,
    CLAIM_NO         VARCHAR(32) NOT NULL COMMENT 'DTC+yyyyMMdd-序号',
    EMP_ID           VARCHAR(64) NOT NULL,
    EMP_NAME         VARCHAR(64) NULL,
    GRADE            VARCHAR(32) NULL COMMENT '职级快照',
    PROJECT_ID       VARCHAR(32) NULL COMMENT '可空 → DEPT_ONLY',
    WBS_ID           VARCHAR(32) NULL,
    DEPT_ID          VARCHAR(64) NULL COMMENT '部门（DEPT_ONLY 归集主体）',
    DEPT_ONLY        CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=未挂项目，归部门费用',
    START_DATE       DATE NOT NULL,
    END_DATE         DATE NOT NULL,
    DAYS             DECIMAL(5,1) NOT NULL,
    DAILY_LIMIT      DECIMAL(10,2) NULL COMMENT '标准快照',
    AMOUNT           DECIMAL(14,2) NOT NULL,
    OVER_STANDARD_FLAG CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=超标',
    OVER_REASON      VARCHAR(500) NULL COMMENT '超标必填',
    OVER_APPR_ID     VARCHAR(32) NULL COMMENT 'L2 审批实例（超标）',
    STATUS           VARCHAR(20) NOT NULL COMMENT 'DRAFT|SUBMITTED|APPROVED|REJECTED',
    REVIEW_BY        VARCHAR(64) NULL,
    REVIEW_DATE      DATETIME NULL,
    REJECT_REASON    VARCHAR(500) NULL,
    VOUCHER_ID       VARCHAR(32) NULL,
    CREATE_BY        VARCHAR(64) NULL,
    CREATE_DATE      DATETIME NULL,
    UPDATE_BY        VARCHAR(64) NULL,
    UPDATE_DATE      DATETIME NULL,
    DEL_FLAG         CHAR(1) NOT NULL DEFAULT '0',
    VER_NO           INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_TC_NO (CLAIM_NO),
    KEY IDX_PM_TC_PROJECT (PROJECT_ID),
    KEY IDX_PM_TC_EMP (EMP_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='差旅报销单';

-- ---------- 4. 间接分摊：规则 / 费用池 / 分摊明细（FR-4.8-5-5） ----------

CREATE TABLE IF NOT EXISTS erp_pm_alloc_rule (
    ID         VARCHAR(32) NOT NULL,
    PROJECT_ID VARCHAR(32) NOT NULL,
    RULE_TYPE  VARCHAR(20) NOT NULL COMMENT 'HOURS 工时占比 | BUDGET 预算占比',
    STATUS     VARCHAR(1) NOT NULL DEFAULT '1',
    CREATE_BY  VARCHAR(64) NULL,
    CREATE_DATE DATETIME NULL,
    UPDATE_BY  VARCHAR(64) NULL,
    UPDATE_DATE DATETIME NULL,
    DEL_FLAG   CHAR(1) NOT NULL DEFAULT '0',
    VER_NO     INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_AR_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='间接分摊规则（每项目至多一条生效）';

CREATE TABLE IF NOT EXISTS erp_pm_cost_pool (
    ID               VARCHAR(32) NOT NULL,
    PERIOD           VARCHAR(7) NOT NULL COMMENT 'yyyy-MM',
    EXPENSE_CATEGORY VARCHAR(64) NOT NULL COMMENT '管理费|办公费|IT费用|其他',
    AMOUNT           DECIMAL(14,2) NOT NULL,
    REMARK           VARCHAR(500) NULL,
    CREATE_BY        VARCHAR(64) NULL,
    CREATE_DATE      DATETIME NULL,
    UPDATE_BY        VARCHAR(64) NULL,
    UPDATE_DATE      DATETIME NULL,
    DEL_FLAG         CHAR(1) NOT NULL DEFAULT '0',
    VER_NO           INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_CP_PERIOD (PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='公共费用池（间接费用期间录入）';

CREATE TABLE IF NOT EXISTS erp_pm_alloc_detail (
    ID           VARCHAR(32) NOT NULL,
    PERIOD       VARCHAR(7) NOT NULL,
    POOL_ID      VARCHAR(32) NOT NULL,
    PROJECT_ID   VARCHAR(32) NOT NULL,
    WBS_ID       VARCHAR(32) NULL COMMENT '间接分摊落项目级（WBS 空）',
    ALLOC_BASE   DECIMAL(14,2) NOT NULL COMMENT '占比基数快照',
    ALLOC_RATE   DECIMAL(8,4) NOT NULL COMMENT '占比快照',
    AMOUNT       DECIMAL(14,2) NOT NULL,
    VOUCHER_ID   VARCHAR(32) NULL,
    COLLECT_ID   VARCHAR(32) NULL COMMENT '→erp_pm_cost_collect',
    CREATE_BY    VARCHAR(64) NULL,
    CREATE_DATE  DATETIME NULL,
    UPDATE_BY    VARCHAR(64) NULL,
    UPDATE_DATE  DATETIME NULL,
    DEL_FLAG     CHAR(1) NOT NULL DEFAULT '0',
    VER_NO       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_AD_PERIOD_POOL (PERIOD, POOL_ID),
    KEY IDX_PM_AD_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='间接成本分摊明细（月度任务写入，同 PERIOD+POOL 幂等）';

-- ---------- 5. 五类成本统一归集（design D2） ----------

CREATE TABLE IF NOT EXISTS erp_pm_cost_collect (
    ID           VARCHAR(32) NOT NULL,
    PROJECT_ID   VARCHAR(32) NOT NULL,
    WBS_ID       VARCHAR(32) NULL COMMENT '任务级；间接/部分外协为空（项目级）',
    COST_TYPE    VARCHAR(20) NOT NULL COMMENT 'LABOR|MATERIAL|SUBCONTRACT|TRAVEL|INDIRECT',
    AMOUNT       DECIMAL(14,2) NOT NULL,
    SOURCE_TYPE  VARCHAR(20) NOT NULL COMMENT 'MAT_ISSUE|SUB_PAYMENT|TRAVEL_CLAIM|HOURS_COLLECT|ALLOC',
    SOURCE_ID    VARCHAR(100) NOT NULL COMMENT '来源单据/归集行 ID（分摊为 poolId:projectId 组合）',
    COLLECT_DATE DATE NOT NULL,
    VOUCHER_ID   VARCHAR(32) NULL COMMENT '会计凭证回写',
    CREATE_BY    VARCHAR(64) NULL,
    CREATE_DATE  DATETIME NULL,
    UPDATE_BY    VARCHAR(64) NULL,
    UPDATE_DATE  DATETIME NULL,
    DEL_FLAG     CHAR(1) NOT NULL DEFAULT '0',
    VER_NO       INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_CC_PROJECT_TYPE (PROJECT_ID, COST_TYPE),
    KEY IDX_PM_CC_SOURCE (SOURCE_TYPE, SOURCE_ID),
    KEY IDX_PM_CC_DATE (COLLECT_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='五类成本统一归集（只增不改，冲减走负数行）';

-- ---------- 6. 成本偏差预警（C-4.8-02 / BR-4.8-21 双阈值） ----------

CREATE TABLE IF NOT EXISTS erp_pm_cost_alert (
    ID             VARCHAR(32) NOT NULL,
    PROJECT_ID     VARCHAR(32) NOT NULL,
    LEVEL          VARCHAR(8) NOT NULL COMMENT 'YELLOW|RED',
    DEVIATION_RATE DECIMAL(8,2) NOT NULL COMMENT '触发时偏差率快照（%）',
    PV             DECIMAL(14,2) NOT NULL COMMENT '触发时 PV 快照',
    ACTUAL         DECIMAL(14,2) NOT NULL COMMENT '触发时实际累计快照',
    ALERT_DATE     DATE NOT NULL,
    HANDLED_FLAG   CHAR(1) NOT NULL DEFAULT '0',
    HANDLED_BY     VARCHAR(64) NULL,
    HANDLED_NOTE   VARCHAR(500) NULL,
    HANDLED_AT     DATETIME NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_CA_PROJ_LVL_DATE (PROJECT_ID, LEVEL, ALERT_DATE),
    KEY IDX_PM_CA_DATE (ALERT_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='成本偏差预警（同日同级 upsert 幂等）';

-- ---------- 7. 超预算审批申请（FR-4.8-5-7 / BR-4.8-22） ----------

CREATE TABLE IF NOT EXISTS erp_pm_over_budget (
    ID             VARCHAR(32) NOT NULL,
    APPLY_NO       VARCHAR(32) NOT NULL COMMENT 'OBB+yyyyMMdd-序号',
    PROJECT_ID     VARCHAR(32) NOT NULL,
    OVER_AMOUNT    DECIMAL(14,2) NOT NULL COMMENT '超支金额 = 实际累计 − 基线',
    OVER_RATE      DECIMAL(8,2) NOT NULL COMMENT '超支比例（%）',
    REASON         VARCHAR(1000) NOT NULL,
    SAVING_MEASURE VARCHAR(1000) NULL COMMENT '已采取节约措施',
    EXTRA_BUDGET   DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '追加预算需求（0=纯解冻）',
    APPR_ID        VARCHAR(32) NULL COMMENT '审批实例',
    APPROVAL_ROLE  VARCHAR(32) NOT NULL COMMENT '按超支比例路由：ROLE_PMO|ROLE_FINANCE|ROLE_EXEC',
    STATUS         VARCHAR(20) NOT NULL COMMENT 'PENDING|APPROVED|REJECTED',
    PM_OPINION     VARCHAR(500) NULL,
    CREATE_BY      VARCHAR(64) NULL,
    CREATE_DATE    DATETIME NULL,
    UPDATE_BY      VARCHAR(64) NULL,
    UPDATE_DATE    DATETIME NULL,
    DEL_FLAG       CHAR(1) NOT NULL DEFAULT '0',
    VER_NO         INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_OB_NO (APPLY_NO),
    KEY IDX_PM_OB_PROJECT (PROJECT_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='超预算审批申请（BR-4.8-22 三级分级）';

-- ---------- 8. erp_pm_project 幂等 ALTER：冻结三列（BR-4.8-21） ----------

SET @c1 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_pm_project'
               AND COLUMN_NAME = 'FREEZE_FLAG');
SET @d1 := IF(@c1 = 0,
    'ALTER TABLE erp_pm_project ADD COLUMN FREEZE_FLAG CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''1=冻结（超基线）'' AFTER BASELINE_AMOUNT',
    'SELECT 1');
PREPARE s1 FROM @d1; EXECUTE s1; DEALLOCATE PREPARE s1;

SET @c2 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_pm_project'
               AND COLUMN_NAME = 'FREEZE_REASON');
SET @d2 := IF(@c2 = 0,
    'ALTER TABLE erp_pm_project ADD COLUMN FREEZE_REASON VARCHAR(500) NULL COMMENT ''冻结原因'' AFTER FREEZE_FLAG',
    'SELECT 1');
PREPARE s2 FROM @d2; EXECUTE s2; DEALLOCATE PREPARE s2;

SET @c3 := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_pm_project'
               AND COLUMN_NAME = 'FROZEN_AT');
SET @d3 := IF(@c3 = 0,
    'ALTER TABLE erp_pm_project ADD COLUMN FROZEN_AT DATETIME NULL COMMENT ''冻结时间'' AFTER FREEZE_REASON',
    'SELECT 1');
PREPARE s3 FROM @d3; EXECUTE s3; DEALLOCATE PREPARE s3;

-- ---------- 9. SysParam 成本偏差预警阈值（BR-4.8-21 双阈值口径） ----------

INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('PARAM-COST-TOL', 'COST_TOLERANCE', '0.5', 'NUMBER', 'PM', '成本偏差预警阈值（%）：偏差率>此值仅预警；实际累计>基线才冻结（BR-4.8-21）', 'system', NOW(), '0', 0);

-- ---------- 10. ROLE_EXEC 分管领导角色（BR-4.8-22 第三级） ----------

INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('role-exec', 'ROLE_EXEC', '分管领导', 26, '1', 'system', '0', 0);

-- ---------- 11. M11-6 三叶菜单 PERM（幂等 UPDATE 只补空值） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_PMO,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M11-6', 'M11-6-1', 'M11-6-2')
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_FINANCE,ROLE_EXEC,ROLE_ADMIN'
 WHERE ID = 'M11-6-3'
   AND (PERM IS NULL OR PERM = '');

-- ---------- 12. 差旅标准三档种子（职级 × 城市类别，日限额元） ----------

INSERT IGNORE INTO erp_pm_travel_standard (ID, GRADE, CITY_CATEGORY, DAILY_LIMIT, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('pmtravel-junior-1', 'JUNIOR', '一线', 350, '1', 'system', '0', 0),
       ('pmtravel-junior-2', 'JUNIOR', '二线', 250, '1', 'system', '0', 0),
       ('pmtravel-junior-3', 'JUNIOR', '其他', 200, '1', 'system', '0', 0),
       ('pmtravel-mid-1',    'MID',    '一线', 500, '1', 'system', '0', 0),
       ('pmtravel-mid-2',    'MID',    '二线', 350, '1', 'system', '0', 0),
       ('pmtravel-mid-3',    'MID',    '其他', 280, '1', 'system', '0', 0),
       ('pmtravel-senior-1', 'SENIOR', '一线', 700, '1', 'system', '0', 0),
       ('pmtravel-senior-2', 'SENIOR', '二线', 500, '1', 'system', '0', 0),
       ('pmtravel-senior-3', 'SENIOR', '其他', 400, '1', 'system', '0', 0);

-- ---------- 13. 项目成本科目 + 五类凭证科目映射（design D2；沿 162/165 映射表先例） ----------

INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS) VALUES
 ('acc-pm-cost-material',    '660501', '项目成本-材料',   'Dr', '费用', 'ACTIVE'),
 ('acc-pm-cost-subcontract', '660502', '项目成本-外协',   'Dr', '费用', 'ACTIVE'),
 ('acc-pm-cost-travel',      '660503', '项目成本-差旅',   'Dr', '费用', 'ACTIVE'),
 ('acc-pm-cost-labor',       '660504', '项目成本-人工',   'Dr', '费用', 'ACTIVE'),
 ('acc-pm-cost-indirect',    '660505', '项目成本-间接',   'Dr', '费用', 'ACTIVE');

-- 借方（五类项目成本科目）+ 贷方（原材料/应付账款/员工往来/应付工资/待摊费用）
INSERT IGNORE INTO hr_pay_account_mapping (ID, MAPPING_TYPE, MAPPING_KEY, ACCOUNT_CODE, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('PAM-PM-MAT-DR',   'FIXED', 'PM_COST_MATERIAL_DR',    '660501', '项目成本-材料（借）',     'migration', NOW(), '0', 0),
       ('PAM-PM-MAT-CR',   'FIXED', 'PM_COST_MATERIAL_CR',    '1403',   '原材料（贷）',           'migration', NOW(), '0', 0),
       ('PAM-PM-SUB-DR',   'FIXED', 'PM_COST_SUBCONTRACT_DR', '660502', '项目成本-外协（借）',     'migration', NOW(), '0', 0),
       ('PAM-PM-SUB-CR',   'FIXED', 'PM_COST_SUBCONTRACT_CR', '2202',   '应付账款（贷）',         'migration', NOW(), '0', 0),
       ('PAM-PM-TRV-DR',   'FIXED', 'PM_COST_TRAVEL_DR',      '660503', '项目成本-差旅（借）',     'migration', NOW(), '0', 0),
       ('PAM-PM-TRV-CR',   'FIXED', 'PM_COST_TRAVEL_CR',      '2241',   '其他应付款-员工（贷）',   'migration', NOW(), '0', 0),
       ('PAM-PM-LAB-DR',   'FIXED', 'PM_COST_LABOR_DR',       '660504', '项目成本-人工（借）',     'migration', NOW(), '0', 0),
       ('PAM-PM-LAB-CR',   'FIXED', 'PM_COST_LABOR_CR',       '221101', '应付职工薪酬-工资（贷）', 'migration', NOW(), '0', 0),
       ('PAM-PM-IND-DR',   'FIXED', 'PM_COST_INDIRECT_DR',    '660505', '项目成本-间接（借）',     'migration', NOW(), '0', 0),
       ('PAM-PM-IND-CR',   'FIXED', 'PM_COST_INDIRECT_CR',    '1801',   '待摊费用（贷）',         'migration', NOW(), '0', 0);
