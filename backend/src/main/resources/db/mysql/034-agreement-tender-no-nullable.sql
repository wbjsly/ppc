-- 034 手工建协议适配：erp_proc_framework_agreement.TENDER_NO 放开 NOT NULL
-- 背景：change add-framework-agreement-order design D5 手工创建协议无关联招标（source=MANUAL，
-- TENDER_NO 为 NULL）；原 031 中该列 NOT NULL 且无默认值导致手工插入 500。
-- 唯一索引 UK_PA_TENDER_NO 在 MySQL 中允许多个 NULL，招标生成的幂等语义不受影响。
-- 幂等：information_schema 判断 IS_NULLABLE + PREPARE（033 范式）。

SET @ddl = IF(
    (SELECT IS_NULLABLE FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_proc_framework_agreement'
        AND COLUMN_NAME = 'TENDER_NO') = 'NO',
    'ALTER TABLE erp_proc_framework_agreement MODIFY COLUMN TENDER_NO VARCHAR(32) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- TENDER_ID 同理：手工协议无关联招标，放开 NOT NULL
SET @ddl = IF(
    (SELECT IS_NULLABLE FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_proc_framework_agreement'
        AND COLUMN_NAME = 'TENDER_ID') = 'NO',
    'ALTER TABLE erp_proc_framework_agreement MODIFY COLUMN TENDER_ID VARCHAR(64) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 其余硬性列（AGREEMENT_NO / EFFECTIVE_DATE / TITLE）服务端均必填赋值，无需变更。
