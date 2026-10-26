-- ============================================================================
-- 151-red-invoice-void.sql — 红冲作废（M9-4，change add-red-invoice-void）
--   spec: red-invoice-void（4.14 流程 4：红字冲销全流程 + 发票作废）
--         + invoice-management ADDED 进项税额转出（FR-4.14-3-6 / BR-4.14-22）
--   幂等：CREATE TABLE IF NOT EXISTS + information_schema 判存 + INSERT IGNORE
-- ============================================================================

-- ---------- 1. 红冲申请单（design D1：独立实体，不污染 RedInvoice 台账语义） ----------
CREATE TABLE IF NOT EXISTS erp_fin_red_flush_apply (
    ID VARCHAR(64) NOT NULL,
    APPLY_NO VARCHAR(32) NOT NULL COMMENT '申请单号 FSAP+yyyyMMdd+流水',
    ORIGIN_INVOICE_ID VARCHAR(64) NOT NULL COMMENT '原蓝票 ID',
    ORIGIN_INVOICE_NO VARCHAR(64) DEFAULT NULL COMMENT '原蓝票号',
    CUSTOMER_ID VARCHAR(64) NOT NULL,
    CUSTOMER_CODE VARCHAR(32) DEFAULT NULL,
    CUSTOMER_NAME VARCHAR(128) DEFAULT NULL,
    AMOUNT DECIMAL(18,2) NOT NULL COMMENT '红冲金额（价税合计，含税）',
    TAX_RATE DECIMAL(10,4) DEFAULT NULL COMMENT '原票税率',
    TAX_AMOUNT DECIMAL(18,2) DEFAULT NULL COMMENT '红冲税额（按原票税率反算负数口径在红票侧）',
    REASON_TYPE VARCHAR(16) NOT NULL COMMENT 'RETURN/DISCOUNT/ERROR',
    REASON VARCHAR(500) NOT NULL COMMENT '红冲原因',
    -- DRAFT→APPROVED→INFO_ISSUED→ISSUED | REJECTED
    STATUS VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '红冲申请状态机',
    APPR_ID VARCHAR(64) COMMENT '审批实例 ID（ApprovalEngine）',
    INFO_SHEET_NO VARCHAR(64) COMMENT '红字信息表编号（开红票门禁，BR-4.14-25）',
    INFO_SHEET_FILLER VARCHAR(16) COMMENT '信息表填开方 BUYER/SELLER（按购方抵扣状态判定）',
    CROSS_PERIOD_FLAG CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=跨期红冲（BR-4.14-27 留痕）',
    CROSS_PERIOD_NOTE VARCHAR(500) COMMENT '更正申报评估结论（跨期必填）',
    FREQ_FLAG CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=频繁红冲升级（同客户窗口超阈值）',
    RED_INVOICE_ID VARCHAR(64) COMMENT '开具后红票 ID',
    RED_NO VARCHAR(64) COMMENT '开具后红票号',
    VOUCHER_ID VARCHAR(64) COMMENT '红冲凭证 ID（DRAFT）',
    REOPEN_APPLY_NO VARCHAR(32) COMMENT '重开关联：由本单发起的新开票申请号',
    REMARK VARCHAR(500),
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    PRIMARY KEY (ID),
    UNIQUE KEY UK_FIN_FSAP_NO (APPLY_NO),
    KEY IDX_FIN_FSAP_ORIGIN (ORIGIN_INVOICE_ID),
    KEY IDX_FIN_FSAP_CUST (CUSTOMER_ID, CREATE_DATE),
    KEY IDX_FIN_FSAP_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='红冲申请单（M9-4-1，spec red-invoice-void）';

-- ---------- 2. 发票作废申请单（FR-4.14-4-7~10：作废亦须申请+审批留痕 C-4.14-03） ----------
CREATE TABLE IF NOT EXISTS erp_fin_invoice_void_apply (
    ID VARCHAR(64) NOT NULL,
    APPLY_NO VARCHAR(32) NOT NULL COMMENT '作废申请单号 VSAP+yyyyMMdd+流水',
    INVOICE_ID VARCHAR(64) NOT NULL COMMENT '待作废销项发票 ID',
    INVOICE_NO VARCHAR(64) DEFAULT NULL COMMENT '发票号',
    CUSTOMER_ID VARCHAR(64) NOT NULL,
    CUSTOMER_CODE VARCHAR(32) DEFAULT NULL,
    CUSTOMER_NAME VARCHAR(128) DEFAULT NULL,
    AMOUNT DECIMAL(18,2) DEFAULT NULL COMMENT '发票金额（价税合计，留痕）',
    REASON VARCHAR(500) NOT NULL COMMENT '作废原因',
    -- DRAFT→APPROVED→VOIDED | REJECTED
    STATUS VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT '作废申请状态机',
    APPR_ID VARCHAR(64) COMMENT '审批实例 ID',
    VOUCHER_ID VARCHAR(64) COMMENT '作废凭证 ID（原开票凭证存在时红字冲销，DRAFT）',
    REMARK VARCHAR(500),
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    PRIMARY KEY (ID),
    UNIQUE KEY UK_FIN_VSAP_NO (APPLY_NO),
    KEY IDX_FIN_VSAP_INVOICE (INVOICE_ID),
    KEY IDX_FIN_VSAP_STATUS (STATUS)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='发票作废申请单（M9-4-2，spec red-invoice-void）';

-- ---------- 3. RedInvoice 扩展（信息单留痕 + 申请单锚） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_red_invoice'
               AND COLUMN_NAME = 'INFO_SHEET_NO');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_red_invoice ADD COLUMN INFO_SHEET_NO VARCHAR(64) DEFAULT NULL COMMENT ''红字信息表编号'' AFTER REMARK',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_red_invoice'
               AND COLUMN_NAME = 'INFO_SHEET_FILLER');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_red_invoice ADD COLUMN INFO_SHEET_FILLER VARCHAR(16) DEFAULT NULL COMMENT ''信息表填开方 BUYER/SELLER'' AFTER INFO_SHEET_NO',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_red_invoice'
               AND COLUMN_NAME = 'FLUSH_APPLY_NO');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_red_invoice ADD COLUMN FLUSH_APPLY_NO VARCHAR(32) DEFAULT NULL COMMENT ''来源红冲申请单号'' AFTER INFO_SHEET_FILLER',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4. InputInvoice 累计转出金额（进项税额转出，BR-4.14-22） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_input_invoice'
               AND COLUMN_NAME = 'TRANSFER_OUT_AMOUNT');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_input_invoice ADD COLUMN TRANSFER_OUT_AMOUNT DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''累计转出税额（≤已抵扣，FR-4.14-3-6）'' AFTER VOUCHER_ID',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 5. InvoiceApply 重开关联字段（BR-4.14-29） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_sd_invoice_apply'
               AND COLUMN_NAME = 'RED_FLUSH_APPLY_NO');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_sd_invoice_apply ADD COLUMN RED_FLUSH_APPLY_NO VARCHAR(32) DEFAULT NULL COMMENT ''重开关联红冲申请单号（BR-4.14-29 强制关联）'' AFTER DISPUTE_NOTE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_sd_invoice_apply'
               AND COLUMN_NAME = 'REOPEN_NOTE');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_sd_invoice_apply ADD COLUMN REOPEN_NOTE VARCHAR(500) DEFAULT NULL COMMENT ''重开书面说明（金额/购方不一致时必填，BR-4.14-29 L2）'' AFTER RED_FLUSH_APPLY_NO',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 6. 系统参数（频繁红冲窗口/次数 + 审批金额阈值） ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'PARAM-RED-FLUSH-FREQ-DAYS', 'RED_FLUSH_FREQ_DAYS', '30', 'NUMBER', 'TAX',
       '频繁红冲判定窗口天数（同客户窗口内超次数阈值 → 升级财务经理审批）', 'system', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'RED_FLUSH_FREQ_DAYS');

INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'PARAM-RED-FLUSH-FREQ-COUNT', 'RED_FLUSH_FREQ_COUNT', '3', 'NUMBER', 'TAX',
       '频繁红冲次数阈值（窗口内 ≥ 该次数触发升级审批，FR-4.14-4-2）', 'system', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'RED_FLUSH_FREQ_COUNT');

INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'PARAM-RED-FLUSH-THRESHOLD', 'RED_FLUSH_APPROVAL_THRESHOLD', '100000', 'NUMBER', 'TAX',
       '红冲/作废审批金额阈值（< 阈值税务专员主管单级；≥ 阈值加财务经理，FR-4.14-4-2）', 'system', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_sys_param WHERE PARAM_KEY = 'RED_FLUSH_APPROVAL_THRESHOLD');

-- ---------- 7. M9-4 红冲作废菜单 PERM（对齐 M9-1~3 三角色） ----------
UPDATE erp_admin_menu SET PERM='ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M9-4','M9-4-1','M9-4-2','M9-4-3') AND (PERM IS NULL OR PERM='');

-- ---------- 8. 进项税额转出科目（222103；费用侧复用 660299） ----------
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
SELECT 'acct-222103', '222103', '应交税费-进项税转出', 'CR', 'TAX', 'ACTIVE', 'system', NOW(), '0', 0
WHERE NOT EXISTS (SELECT 1 FROM erp_fin_account WHERE ACCOUNT_CODE = '222103');
