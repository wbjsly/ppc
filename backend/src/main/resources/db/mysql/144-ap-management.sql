-- 144-ap-management.sql
-- add-ap-management：应付管理（design D1：ap_invoice 加 DUE_DATE + 付款计划单 + M8-3 菜单权限回填）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 判存/PREPARE

-- ---------- 1. 应付到期日列（账龄落段基准，design D3/D6） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_ap_invoice'
               AND COLUMN_NAME = 'DUE_DATE');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_ap_invoice ADD COLUMN DUE_DATE date COMMENT ''到期日=INVOICE_DATE+供应商账期天数（存量留 NULL 人工补录）'' AFTER INVOICE_DATE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 付款计划单（design D4：PERIOD+LEGAL_ENTITY 唯一；分供应商计划行 JSON） ----------
CREATE TABLE IF NOT EXISTS erp_fin_payment_plan (
    ID VARCHAR(64) NOT NULL,
    PLAN_NO VARCHAR(32) NOT NULL COMMENT '计划单号 PPL+period+seq',
    PERIOD VARCHAR(6) NOT NULL COMMENT '会计期间 YYYYMM',
    LEGAL_ENTITY_ID VARCHAR(64) COMMENT '法人（可空=全法人；payment_request 无该维度故按期间匹配）',
    PLAN_TOTAL DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '计划总额',
    EXECUTED_TOTAL DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已执行付款累计',
    PLAN_LINES JSON COMMENT '分供应商计划行 [{supplierId,supplierName,planAmount,executedAmount}...]',
    STATUS VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING/APPROVED/CLOSED',
    APPR_ID VARCHAR(64) COMMENT '审批实例锚',
    REJECT_REASON VARCHAR(255),
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    UNIQUE KEY UK_FIN_AP_PLAN_NO (PLAN_NO),
    UNIQUE KEY UK_FIN_AP_PLAN_PERIOD (PERIOD, LEGAL_ENTITY_ID),
    KEY IDX_FIN_AP_PLAN_PERIOD (PERIOD)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 3. 菜单权限回填（M8-3 三叶：与 M8-1/M8-2 对齐） ----------
UPDATE erp_admin_menu SET PERM = 'ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_ADMIN'
WHERE ID IN ('M8-3', 'M8-3-1', 'M8-3-2', 'M8-3-3') AND (PERM IS NULL OR PERM = '');
