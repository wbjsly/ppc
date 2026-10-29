-- ============================================================
-- 185-admin-user-lifecycle.sql
-- change: add-account-lifecycle-console（16.1 账号生命周期，design D1）
--   erp_admin_user 幂等加 4 列：
--     PHONE           手机号（双因子绑定预留，本期仅落库——偏差表）
--     LAST_LOGIN_AT   最后登录时间（登录成功写入）
--     MUST_CHANGE_PWD 首次登录强制改密标记（'1' 需改密，新账号/重置密码置位）
--     ARCHIVED_AT     归档时间（STATUS='2' 归档态，停用 90 天自动归档）
--   STATUS 三态约定（varchar(1)，无 DDL）：'1' 启用 / '0' 停用 / '2' 归档
-- ============================================================
-- 幂等：information_schema 判断列存在再 PREPARE（沿 178/179/182 惯例）
-- ============================================================

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_admin_user'
                   AND COLUMN_NAME = 'PHONE');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_admin_user ADD COLUMN PHONE VARCHAR(32) DEFAULT NULL COMMENT ''手机号（双因子绑定预留）'' AFTER SUPPLIER_ID',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_admin_user'
                   AND COLUMN_NAME = 'LAST_LOGIN_AT');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_admin_user ADD COLUMN LAST_LOGIN_AT DATETIME DEFAULT NULL COMMENT ''最后登录时间'' AFTER PHONE',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_admin_user'
                   AND COLUMN_NAME = 'MUST_CHANGE_PWD');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_admin_user ADD COLUMN MUST_CHANGE_PWD VARCHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''强制改密标记（1=首次登录/重置后须改密）'' AFTER LAST_LOGIN_AT',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_admin_user'
                   AND COLUMN_NAME = 'ARCHIVED_AT');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_admin_user ADD COLUMN ARCHIVED_AT DATETIME DEFAULT NULL COMMENT ''归档时间（STATUS=2，归档不可恢复 C-0-05）'' AFTER MUST_CHANGE_PWD',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
