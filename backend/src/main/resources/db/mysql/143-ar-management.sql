-- 143-ar-management.sql
-- add-ar-management：应收管理（design D1：ar_invoice 确认三列 + 坏账计提台账
--                      + 科目 1232/6702 + 账龄段/比例参数 + M8-2 菜单权限回填）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 判存/PREPARE

-- ---------- 1. 应收确认三列（确认标记=账龄起算锚点，未确认不计账龄） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_fin_ar_invoice'
               AND COLUMN_NAME = 'CONFIRM_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_fin_ar_invoice ADD COLUMN CONFIRM_FLAG CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''1=已确认（账龄起算锚点，未确认不计账龄）'' AFTER STATUS,
     ADD COLUMN CONFIRM_BY VARCHAR(64) COMMENT ''确认人'' AFTER CONFIRM_FLAG,
     ADD COLUMN CONFIRM_DATE DATETIME COMMENT ''确认时间'' AFTER CONFIRM_BY',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2. 坏账计提台账（design D3：PERIOD+CUSTOMER_ID 唯一，六段基数 JSON 快照） ----------
CREATE TABLE IF NOT EXISTS erp_fin_ar_provision (
    ID VARCHAR(64) NOT NULL,
    PROVISION_NO VARCHAR(32) NOT NULL COMMENT '计提单号 PVD+period+cust',
    PERIOD VARCHAR(6) NOT NULL COMMENT '会计期间 YYYYMM',
    CUSTOMER_ID VARCHAR(64) NOT NULL,
    CUSTOMER_CODE VARCHAR(32),
    CUSTOMER_NAME VARCHAR(128),
    BUCKET_BASE JSON COMMENT '六段基数快照 [{bucket,base,rate,accrual}...]',
    TARGET_BALANCE DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '目标余额=Σ段计提额',
    BOOKED_BALANCE DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已提余额（冲回前）',
    PERIOD_ACCRUAL DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '本期计提额（可负=冲减）',
    REVERSED_FLAG CHAR(1) NOT NULL DEFAULT '0' COMMENT '1=上期已冲回',
    REVERSED_VOUCHER_ID VARCHAR(64) COMMENT '冲回凭证锚',
    VOUCHER_ID VARCHAR(64) COMMENT '本期计提凭证锚',
    STATUS VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CONFIRMED',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    UNIQUE KEY UK_FIN_AR_PVD (PERIOD, CUSTOMER_ID),
    UNIQUE KEY UK_FIN_AR_PVD_NO (PROVISION_NO),
    KEY IDX_FIN_AR_PVD_PERIOD (PERIOD),
    KEY IDX_FIN_AR_PVD_CUST (CUSTOMER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 3. 科目种子（1232 坏账准备/6702 资产减值损失，spec ar-management） ----------
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, CREATE_BY, DEL_FLAG, VER_NO)
VALUES ('acct-1232', '1232', '坏账准备', 'CR', 'ASSET', 'ACTIVE', 'seed', '0', 0),
       ('acct-6702', '6702', '资产减值损失', 'DR', 'PROFIT', 'ACTIVE', 'seed', '0', 0);

-- ---------- 4. 参数种子（design D1：六段账龄 + 组合比例，4~8 段弹性） ----------
-- 注意：erp_sys_param.ID 无默认值，必须显式给 ID（否则隐式空串致第二行 PK 冲突被 IGNORE 丢弃）
DELETE FROM erp_sys_param WHERE PARAM_KEY IN ('AR_AGING_BUCKETS', 'AR_BAD_DEBT_RATES') AND ID = '';
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY)
VALUES ('param-ar-aging-buckets', 'AR_AGING_BUCKETS', '30,60,90,180,360', 'STRING', 'FIN',
        '应收账龄分段（逗号分隔天数，5 个分界=6 段：0-30/31-60/61-90/91-180/181-360/360+）', 'seed'),
       ('param-ar-bad-debt-rates', 'AR_BAD_DEBT_RATES', '0.005,0.01,0.02,0.05,0.10,0.30', 'STRING', 'FIN',
        '坏账计提各段比例（与 AR_AGING_BUCKETS 段一一对应；段基数×比例=计提额）', 'seed');

-- ---------- 5. 菜单权限回填（M8-2 三叶：与 M8-1 对齐用 ROLE_FIN_MGR） ----------
UPDATE erp_admin_menu SET PERM = 'ROLE_FIN_MGR,ROLE_FIN_ASSET,ROLE_ADMIN'
WHERE ID IN ('M8-2', 'M8-2-1', 'M8-2-2', 'M8-2-3') AND (PERM IS NULL OR PERM = '');
