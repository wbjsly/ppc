-- 142-gl-management.sql
-- add-gl-management：总账管理（design D1：三表 + 科目 AUX_SET + 凭证行 7 列 + 凭证审核痕迹
--                      + 期间种子 + 存量 POSTED 凭证余额回填 + M8-1 菜单权限回填）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 判存/PREPARE

-- ---------- 1. 科目余额表（design D3：PERIOD+ACCOUNT_CODE+AUX_KEY 唯一；幂等辅助键防重放双计） ----------
CREATE TABLE IF NOT EXISTS erp_fin_balance (
    ID VARCHAR(64) NOT NULL,
    PERIOD VARCHAR(6) NOT NULL COMMENT '会计期间 YYYYMM',
    ACCOUNT_CODE VARCHAR(32) NOT NULL,
    AUX_KEY VARCHAR(255) NOT NULL DEFAULT '' COMMENT '辅助维度拼接键（|分隔，空=无辅助）',
    DR_TOTAL DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '本期借方累计',
    CR_TOTAL DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '本期贷方累计',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    UNIQUE KEY UK_FIN_BAL (PERIOD, ACCOUNT_CODE, AUX_KEY),
    KEY IDX_FIN_BAL_PERIOD (PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 余额幂等辅助表：每张凭证行仅回写一次（create/冲销/重放防双计，design D3）
CREATE TABLE IF NOT EXISTS erp_fin_balance_journal (
    ID VARCHAR(64) NOT NULL,
    VOUCHER_ID VARCHAR(64) NOT NULL,
    LINE_NO INT NOT NULL,
    PERIOD VARCHAR(6) NOT NULL,
    ACCOUNT_CODE VARCHAR(32) NOT NULL,
    AUX_KEY VARCHAR(255) NOT NULL DEFAULT '',
    DIRECTION VARCHAR(2) NOT NULL,
    AMOUNT DECIMAL(18,2) NOT NULL,
    CREATED_AT DATETIME DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_FIN_BALJ (VOUCHER_ID, LINE_NO, DIRECTION)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 2. 会计期间表（design D4：全局单轨） ----------
CREATE TABLE IF NOT EXISTS erp_fin_period (
    ID VARCHAR(64) NOT NULL,
    PERIOD VARCHAR(6) NOT NULL COMMENT 'YYYYMM',
    STATUS VARCHAR(16) NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/CLOSED',
    CLOSE_BY VARCHAR(64),
    CLOSE_AT DATETIME,
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    UNIQUE KEY UK_FIN_PERIOD (PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 3. 凭证模板表（design D5：行 JSON 快照） ----------
CREATE TABLE IF NOT EXISTS erp_fin_voucher_template (
    ID VARCHAR(64) NOT NULL,
    NAME VARCHAR(128) NOT NULL COMMENT '模板名称',
    VOUCHER_TYPE VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    ROWS_JSON TEXT COMMENT '行快照 JSON（科目/方向/摘要/辅助维度，金额留空）',
    USER_SCOPE VARCHAR(64) COMMENT '制单人隔离（NULL=公共模板）',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    KEY IDX_FIN_TMPL_USER (USER_SCOPE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 4. 科目加辅助核算标记（design D1：逗号分隔维度码，NULL=无辅助要求） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_account'
               AND COLUMN_NAME = 'AUX_SET');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_account ADD COLUMN AUX_SET VARCHAR(64) COMMENT ''辅助核算要求（CUST,SUPP,PROJ,EMP,DEPT,PROFIT）'' AFTER STATUS',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 5. 凭证行加 7 列（辅助+成本/利润中心，存量零影响） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_voucher_line'
               AND COLUMN_NAME = 'COST_CENTER');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_voucher_line ADD COLUMN COST_CENTER VARCHAR(32) COMMENT ''成本中心'' AFTER SUMMARY, '
    'ADD COLUMN PROFIT_CENTER VARCHAR(32) COMMENT ''利润中心'' AFTER COST_CENTER, '
    'ADD COLUMN AUX_CUSTOMER VARCHAR(64) COMMENT ''辅助-客户'' AFTER PROFIT_CENTER, '
    'ADD COLUMN AUX_SUPPLIER VARCHAR(64) COMMENT ''辅助-供应商'' AFTER AUX_CUSTOMER, '
    'ADD COLUMN AUX_PROJECT VARCHAR(64) COMMENT ''辅助-项目'' AFTER AUX_SUPPLIER, '
    'ADD COLUMN AUX_EMPLOYEE VARCHAR(64) COMMENT ''辅助-员工'' AFTER AUX_PROJECT, '
    'ADD COLUMN AUX_DEPT VARCHAR(64) COMMENT ''辅助-部门'' AFTER AUX_EMPLOYEE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6. 凭证头加审核痕迹（手工凭证 C-4.6-07 留痕） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_voucher'
               AND COLUMN_NAME = 'AUDIT_BY');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_voucher ADD COLUMN AUDIT_BY VARCHAR(64) COMMENT ''审核人'' AFTER STATUS, '
    'ADD COLUMN AUDIT_DATE DATETIME COMMENT ''审核时间'' AFTER AUDIT_BY, '
    'ADD COLUMN AUDIT_OPINION VARCHAR(512) COMMENT ''驳回/审核意见'' AFTER AUDIT_DATE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 7. 期间种子（当月 OPEN，幂等） ----------
INSERT IGNORE INTO erp_fin_period (ID, PERIOD, STATUS, CREATE_BY)
VALUES (CONCAT('fp-', DATE_FORMAT(CURDATE(), '%Y%m')), DATE_FORMAT(CURDATE(), '%Y%m'), 'OPEN', 'seed');

-- ---------- 8. 存量 POSTED 凭证余额回填（design D3：借贷分栏按期间+科目聚合，余额表全空时一次性回填，幂等） ----------
SET @balEmpty := (SELECT COUNT(*) FROM erp_fin_balance);
SET @sql := IF(@balEmpty = 0,
    'INSERT IGNORE INTO erp_fin_balance (ID, PERIOD, ACCOUNT_CODE, AUX_KEY, DR_TOTAL, CR_TOTAL, CREATE_BY)
     SELECT CONCAT(''bal-'', v.PERIOD, ''-'', l.ACCOUNT_CODE), v.PERIOD, l.ACCOUNT_CODE, '''',
            SUM(CASE WHEN l.DIRECTION = ''DR'' THEN l.AMOUNT ELSE 0 END),
            SUM(CASE WHEN l.DIRECTION = ''CR'' THEN l.AMOUNT ELSE 0 END), ''migration''
      FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID
      WHERE v.STATUS = ''POSTED'' AND v.DEL_FLAG = ''0'' AND l.DEL_FLAG = ''0''
      GROUP BY v.PERIOD, l.ACCOUNT_CODE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 聚合金额回写（同 PREPARE 模式：借/贷两列 UPDATE）
SET @sql := IF(@balEmpty = 0,
    'UPDATE erp_fin_balance b JOIN (
       SELECT v.PERIOD p, l.ACCOUNT_CODE a,
              SUM(CASE WHEN l.DIRECTION = ''DR'' THEN l.AMOUNT ELSE 0 END) dr,
              SUM(CASE WHEN l.DIRECTION = ''CR'' THEN l.AMOUNT ELSE 0 END) cr
       FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID
       WHERE v.STATUS = ''POSTED'' AND v.DEL_FLAG = ''0'' AND l.DEL_FLAG = ''0''
       GROUP BY v.PERIOD, l.ACCOUNT_CODE
     ) agg ON b.PERIOD = agg.p AND b.ACCOUNT_CODE = agg.a AND b.AUX_KEY = ''''
     SET b.DR_TOTAL = agg.dr, b.CR_TOTAL = agg.cr',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 存量凭证行补幂等 journal（防后续 reverse 反向冲减时双计）
SET @sql := IF(@balEmpty = 0,
    'INSERT IGNORE INTO erp_fin_balance_journal (ID, VOUCHER_ID, LINE_NO, PERIOD, ACCOUNT_CODE, AUX_KEY, DIRECTION, AMOUNT)
     SELECT CONCAT(''bj-'', l.ID), l.VOUCHER_ID, l.LINE_NO, v.PERIOD, l.ACCOUNT_CODE, '''', l.DIRECTION, l.AMOUNT
      FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID = l.VOUCHER_ID
      WHERE v.STATUS = ''POSTED'' AND v.DEL_FLAG = ''0'' AND l.DEL_FLAG = ''0''',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 10. 菜单权限回填（8.1.1~8.1.5：财务资产会计/财务经理/管理员） ----------
UPDATE erp_admin_menu SET PERM = 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'
WHERE ID IN ('M8-1', 'M8-1-1', 'M8-1-2', 'M8-1-3', 'M8-1-4', 'M8-1-5') AND (PERM IS NULL OR PERM = '');
