-- ============================================================
-- 172-project-change.sql
-- 项目变更管理（M11.7，设计 4.8 流程八；change add-project-change）
--   ①erp_pm_project_change 变更单（全集容器：五类变更）
--   ②erp_pm_change_wbs 受影响 WBS 集（倒签/先干后批拦截依据）
--   ③erp_pm_baseline_version 基线版本（每版全量快照，BR-4.8-31 永久只读）
--   ④M11-7 两叶 PERM 幂等 UPDATE
-- 幂等：CREATE TABLE IF NOT EXISTS + UPDATE 只补空（沿 171 教训不依赖 099 marker）
-- ============================================================

-- ---------- 1. 项目变更单（全集容器，FR-4.8-8-1） ----------

CREATE TABLE IF NOT EXISTS erp_pm_project_change (
    ID                     VARCHAR(32) NOT NULL,
    CHANGE_NO              VARCHAR(32) NOT NULL COMMENT 'PC+yyyyMMdd-序号',
    PROJECT_ID             VARCHAR(32) NOT NULL,
    CHANGE_TYPE            VARCHAR(20) NOT NULL COMMENT 'SCOPE|SCHEDULE|BUDGET|RESOURCE|COMBO',
    REASON                 VARCHAR(200) NOT NULL COMMENT '客户需求|技术风险|外部环境|内部优化',
    DESCRIPTION            VARCHAR(2000) NULL COMMENT '变更描述（<20 字 L4 提示，不阻断）',
    EFFECTIVE_DATE         DATE NOT NULL COMMENT '期望生效日（早于今日=倒签 L1 拒绝，BR-4.8-32）',
    SCOPE_IMPACT           VARCHAR(2000) NULL COMMENT '范围影响：WBS 增删改清单（人工）',
    SCHEDULE_IMPACT_DAYS   INT NULL COMMENT '进度影响：工作日（人工）',
    RESOURCE_IMPACT        VARCHAR(2000) NULL COMMENT '资源影响：人力增减（人工）',
    BUDGET_ADD             DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '变更追加金额',
    BUDGET_RATE            DECIMAL(8,4) NOT NULL DEFAULT 0 COMMENT '预算影响占比（服务端算=追加/基线）',
    IS_CONTRACT_FLAG       CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=涉合同（手动勾 OR 占比>10% 强制）',
    CONTRACT_ID            VARCHAR(32) NULL,
    CONTRACT_NO            VARCHAR(64) NULL,
    CONTRACT_CHANGE_VER_ID VARCHAR(32) NULL COMMENT '回填：销售域合同变更版本 ID',
    STATUS                 VARCHAR(20) NOT NULL COMMENT 'DRAFT|APPROVING|EXECUTING|DONE|VERIFIED|REJECTED',
    APPR_ID                VARCHAR(32) NULL COMMENT '审批实例',
    VER_NO_LABEL           VARCHAR(20) NULL COMMENT '通过后写入基线版本号（V1.1）',
    CUSTOMER_CONFIRM_FLAG  CHAR(1) NOT NULL DEFAULT '0' COMMENT '第三档：客户书面确认附件已传（L1）',
    PM_OPINION             VARCHAR(500) NULL,
    VERIFY_OPINION         VARCHAR(500) NULL COMMENT 'PMO 核验说明',
    VERIFY_DEVIATION       DECIMAL(8,4) NULL COMMENT '实际偏差%（>50 L4 升级 PMO）',
    CREATE_BY              VARCHAR(64) NULL,
    CREATE_DATE            DATETIME NULL,
    UPDATE_BY              VARCHAR(64) NULL,
    UPDATE_DATE            DATETIME NULL,
    DEL_FLAG               CHAR(1) NOT NULL DEFAULT '0',
    VER_NO                 INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_PC_NO (CHANGE_NO),
    KEY IDX_PM_PC_PROJECT (PROJECT_ID),
    KEY IDX_PM_PC_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目变更单（add-project-change）';

-- ---------- 2. 受影响 WBS 集（C-4.8-09 拦截依据） ----------

CREATE TABLE IF NOT EXISTS erp_pm_change_wbs (
    ID          VARCHAR(32) NOT NULL,
    CHANGE_ID   VARCHAR(32) NOT NULL,
    WBS_ID      VARCHAR(32) NOT NULL,
    CREATE_BY   VARCHAR(64) NULL,
    CREATE_DATE DATETIME NULL,
    UPDATE_BY   VARCHAR(64) NULL,
    UPDATE_DATE DATETIME NULL,
    DEL_FLAG    CHAR(1) NOT NULL DEFAULT '0',
    VER_NO      INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_PM_CW (CHANGE_ID, WBS_ID),
    KEY IDX_PM_CW_WBS (WBS_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目变更受影响 WBS 集（add-project-change）';

-- ---------- 3. 基线版本（每版全量快照，BR-4.8-31 永久只读） ----------
-- 无业务 UK（CURRENT_FLAG 由服务层事务保证唯一，避免与历史归档冲突，design 风险节）

CREATE TABLE IF NOT EXISTS erp_pm_baseline_version (
    ID              VARCHAR(32) NOT NULL,
    PROJECT_ID      VARCHAR(32) NOT NULL,
    VER_NO_LABEL    VARCHAR(20) NOT NULL COMMENT '如 V1.0 / V1.1（可读标签）',
    MAJOR_VER       INT NOT NULL DEFAULT 1 COMMENT '主版本（涉合同变更 +1）',
    MINOR_VER       INT NOT NULL DEFAULT 0 COMMENT '次版本（范围/预算/资源类 +1）',
    SNAPSHOT_JSON   LONGTEXT NOT NULL COMMENT 'erp_pm_budget_line 六列全量快照',
    BASELINE_AMOUNT DECIMAL(14,2) NOT NULL COMMENT '该版本基线总额',
    CURRENT_FLAG    CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=当前生效版',
    CHANGE_ID       VARCHAR(32) NULL COMMENT '来源变更单（NULL=立项初版）',
    CREATE_BY       VARCHAR(64) NULL,
    CREATE_DATE     DATETIME NULL,
    UPDATE_BY       VARCHAR(64) NULL,
    UPDATE_DATE     DATETIME NULL,
    DEL_FLAG        CHAR(1) NOT NULL DEFAULT '0',
    VER_NO          INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_PM_BV_PROJECT (PROJECT_ID),
    KEY IDX_PM_BV_LABEL (PROJECT_ID, VER_NO_LABEL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目基线版本（add-project-change，历史版只读）';

-- ---------- 4. M11-7 两叶菜单 PERM 幂等 UPDATE（只补空，沿 167/170/171 教训） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_PM,ROLE_PMO,ROLE_ADMIN'
 WHERE ID = 'M11-7-1'
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_PMO,ROLE_FINANCE,ROLE_EXEC,ROLE_ADMIN'
 WHERE ID = 'M11-7-2'
   AND (PERM IS NULL OR PERM = '');
