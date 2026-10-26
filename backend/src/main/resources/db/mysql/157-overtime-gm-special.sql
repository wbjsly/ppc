-- ============================================================
-- 157 加班调休补缺口：36h 总经理特批通道（BR-4.7-04）+ 审批超时催办（FR-4.7-6-2）
--   add-overtime-followup 变更
-- ============================================================

-- ---------- 1. hr_overtime_apply 加列：特批标记/超限原因/审批意见 ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_overtime_apply' AND COLUMN_NAME = 'IS_SPECIAL');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_overtime_apply ADD COLUMN IS_SPECIAL CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''特批标记 1=总经理特批'' AFTER IS_EMERGENCY',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_overtime_apply' AND COLUMN_NAME = 'SPECIAL_REASON');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_overtime_apply ADD COLUMN SPECIAL_REASON VARCHAR(200) NULL COMMENT ''特批超限原因（勾选特批必填）'' AFTER IS_SPECIAL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'hr_overtime_apply' AND COLUMN_NAME = 'APPR_OPINION');
SET @sql := IF(@col = 0,
    'ALTER TABLE hr_overtime_apply ADD COLUMN APPR_OPINION VARCHAR(200) NULL COMMENT ''审批意见'' AFTER APPR_ID',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. SysParam：加班审批超时催办时限（工作日） ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'PARAM-OT-APPROVE-REMIND', 'OT_APPROVE_REMIND_DAYS', '2', 'NUMBER', 'HR', '加班审批超时催办时限（工作日），FR-4.7-6-2', 'system', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'OT_APPROVE_REMIND_DAYS');
