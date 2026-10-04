-- 032 招标定标审批列（design D6：招标独立单节点审批，不复用 2.1.4 的 PR 审批引擎）
-- MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，而 DbBootstrap 对执行异常仅 log.warn 后仍标记脚本已执行，
-- 故必须用 information_schema 判断 + PREPARE 动态执行，保证可重复执行且首次必定生效。
-- TABLE_SCHEMA 用 DATABASE() 取当前库，不硬编码。

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_proc_tender'
        AND COLUMN_NAME = 'AWARD_APPROVAL_STATUS') = 0,
    'ALTER TABLE erp_proc_tender
       ADD COLUMN AWARD_APPROVAL_STATUS VARCHAR(16) NOT NULL DEFAULT ''NONE'',
       ADD COLUMN AWARD_APPROVED_BY VARCHAR(64),
       ADD COLUMN AWARD_APPROVED_DATE DATETIME,
       ADD COLUMN AWARD_APPROVAL_NOTE VARCHAR(255)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
