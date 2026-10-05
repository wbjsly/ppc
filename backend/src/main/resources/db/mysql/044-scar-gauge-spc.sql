-- 044-scar-gauge-spc.sql
-- SCAR 回复超期提醒计数（tasks 10.3：每 3 天提醒 + 响应及时性扣分标记）
-- 幂等：information_schema + PREPARE（037 范式）

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_qms_scar'
        AND COLUMN_NAME = 'REPLY_REMIND_COUNT') = 0,
    'ALTER TABLE erp_qms_scar
       ADD COLUMN REPLY_REMIND_COUNT INT NOT NULL DEFAULT 0
         COMMENT ''回复超期提醒计数（每 3 天一次）'' AFTER REJECT_COUNT,
       ADD COLUMN LAST_REMIND_TIME DATETIME
         COMMENT ''上次提醒时间（3 天周期基准）'' AFTER REPLY_REMIND_COUNT',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
