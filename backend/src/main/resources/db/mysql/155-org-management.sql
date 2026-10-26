-- ============================================================
-- 155-org-management.sql
-- M10-2 组织管理（add-org-management，spec org-structure-management）
-- 原地升级 hr_dept/hr_position/hr_employee + hr_grade/hr_org_change 两新表
-- 幂等：可重复执行
-- ============================================================

-- ---------- 1. hr_dept 加列：部门主管 + 成本中心锚 ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_dept' AND COLUMN_NAME = 'MANAGER_EMP_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_dept ADD COLUMN MANAGER_EMP_ID VARCHAR(32) NULL COMMENT ''部门主管（指向 hr_employee.ID）'' AFTER PARENT_ID',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_dept' AND COLUMN_NAME = 'MANAGER_APPROVAL_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_dept ADD COLUMN MANAGER_APPROVAL_ID VARCHAR(32) NULL COMMENT ''主管变更调整单锚（hr_org_change.ID）'' AFTER MANAGER_EMP_ID',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_dept' AND COLUMN_NAME = 'COST_CENTER');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_dept ADD COLUMN COST_CENTER VARCHAR(32) NULL COMMENT ''成本中心锚（双人复核「成本中心变更」要素落点）'' AFTER MANAGER_APPROVAL_ID',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. hr_position 加列：职级关联 + 岗位说明书要点 ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_position' AND COLUMN_NAME = 'GRADE_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_position ADD COLUMN GRADE_ID VARCHAR(32) NULL COMMENT ''关联职级（带宽经职级间接获得）'' AFTER DEPT_ID',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_position' AND COLUMN_NAME = 'JD_JSON');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_position ADD COLUMN JD_JSON TEXT NULL COMMENT ''岗位说明书要点 JSON（职责/考核标准）'' AFTER ROLES_JSON',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3. hr_employee 加列：直接汇报人 ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_employee' AND COLUMN_NAME = 'REPORT_TO_EMP_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_employee ADD COLUMN REPORT_TO_EMP_ID VARCHAR(32) NULL COMMENT ''直接汇报人（指向 hr_employee.ID）'' AFTER POS_ID',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3.5 hr_transfer 加列：调岗带宽超限标记（FR-4.7-4-5 L2） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_transfer' AND COLUMN_NAME = 'BAND_EXCEEDED');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_transfer ADD COLUMN BAND_EXCEEDED VARCHAR(1) NULL COMMENT ''带宽超限 1=超上限/0=低于下限/NULL 未触发'' AFTER SALARY_ADVICE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_transfer' AND COLUMN_NAME = 'BAND_NOTE');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_transfer ADD COLUMN BAND_NOTE VARCHAR(255) NULL COMMENT ''带宽校验说明（超限详情/未关联职级标注）'' AFTER BAND_EXCEEDED',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4. hr_grade 职级表（带宽挂职级） ----------
CREATE TABLE IF NOT EXISTS hr_grade (
    ID          VARCHAR(32)  NOT NULL,
    GRADE_NO    VARCHAR(16)  NOT NULL COMMENT '职级编码 G+3 位流水',
    NAME        VARCHAR(64)  NOT NULL COMMENT '职级名称',
    SERIES      VARCHAR(8)   NOT NULL COMMENT '序列 P 管理 / M 技术',
    SAL_MIN     DECIMAL(12,2) NULL COMMENT '带宽下限（月薪）',
    SAL_MAX     DECIMAL(12,2) NULL COMMENT '带宽上限（月薪）',
    STATUS      VARCHAR(1)   NOT NULL DEFAULT '1' COMMENT '1 启用 / 0 停用',
    CREATE_BY   VARCHAR(64)  NULL,
    CREATE_DATE DATETIME     NULL,
    UPDATE_BY   VARCHAR(64)  NULL,
    UPDATE_DATE DATETIME     NULL,
    DEL_FLAG    CHAR(1)      NOT NULL DEFAULT '0',
    VER_NO      INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_HR_GRADE_NO (GRADE_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HR 职级（M10-2-4，带宽挂职级）';

-- ---------- 5. hr_org_change 组织调整单 ----------
CREATE TABLE IF NOT EXISTS hr_org_change (
    ID             VARCHAR(32)  NOT NULL,
    CHG_NO         VARCHAR(24)  NOT NULL COMMENT '调整单号 OC+yyyyMMdd+4 位流水',
    CHG_TYPE       VARCHAR(16)  NOT NULL COMMENT 'MERGE 合并 / DEPT_MANAGER 主管变更 / GRADE_BAND 带宽变更',
    SOURCE_ID      VARCHAR(32)  NULL COMMENT '源（被合并部门/被变更对象）',
    TARGET_ID      VARCHAR(32)  NULL COMMENT '目标（承接部门）',
    PAYLOAD_JSON   TEXT         NULL COMMENT '前后快照 JSON（before/after + 执行结果计数）',
    EFFECTIVE_DATE DATE         NULL COMMENT '拟生效日',
    STATUS         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT→APPROVING→EFFECTED/REJECTED',
    APPR_ID        VARCHAR(32)  NULL COMMENT '审批实例锚',
    EXECUTED_AT    DATETIME     NULL,
    CREATE_BY      VARCHAR(64)  NULL,
    CREATE_DATE    DATETIME     NULL,
    UPDATE_BY      VARCHAR(64)  NULL,
    UPDATE_DATE    DATETIME     NULL,
    DEL_FLAG       CHAR(1)      NOT NULL DEFAULT '0',
    VER_NO         INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_HR_ORG_CHG_NO (CHG_NO),
    KEY IDX_HR_ORG_CHG_SRC (SOURCE_ID, CHG_TYPE),
    KEY IDX_HR_ORG_CHG_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='组织调整单（M10-2，合并/主管变更/带宽变更审批流）';

-- ---------- 6. SysParam：汇报树深度上限 ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'PARAM-HR-REPORT-DEPTH', 'HR_REPORT_DEPTH_MAX', '10', 'NUMBER', 'HR',
       '员工汇报树深度上限（防病态长链，超限拒绝设置）', 'system', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'HR_REPORT_DEPTH_MAX');

-- ---------- 7. M10-2 组织管理五行 PERM（对齐 HR 域三角色） ----------
UPDATE erp_admin_menu SET PERM='ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN'
 WHERE ID IN ('M10-2','M10-2-1','M10-2-2','M10-2-3','M10-2-4') AND (PERM IS NULL OR PERM='');
