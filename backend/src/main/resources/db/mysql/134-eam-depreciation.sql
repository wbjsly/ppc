-- 134-eam-depreciation.sql
-- add-asset-depreciation：EAM 折旧管理（design D1/D6：三表 + 卡片加列 + 科目参数种子）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 判存/PREPARE（同 131/132/133）

-- ---------- 1. 科目种子（design D6：折旧费用 + 减值科目） ----------
-- 借方费用科目：制造费用-折旧（PROD）、管理费用-折旧（DEFAULT）
-- 贷方/备抵：累计折旧（1602，133 已种，此兜底）、累计折旧减值、固定资产减值准备
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, CREATE_BY)
VALUES ('acc-6602-depr', '6602', '管理费用-折旧', 'D', 'EXPENSE', 'ACTIVE', 'seed'),
       ('acc-5101-depr', '5101', '制造费用-折旧', 'D', 'EXPENSE', 'ACTIVE', 'seed'),
       ('acc-1602-impair', '160201', '累计折旧减值', 'C', 'ASSET', 'ACTIVE', 'seed'),
       ('acc-1603-reserve', '1603', '固定资产减值准备', 'C', 'ASSET', 'ACTIVE', 'seed');

-- ---------- 2. 参数种子（design D6：科目映射 + 阈值） ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY)
VALUES ('param-eam-depr-prod', 'EAM_DEPR_ACCT_PROD', '5101', 'STRING', 'EAM', '折旧借方科目-生产成本中心(COST_TYPE=PROD)', 'seed'),
       ('param-eam-depr-sales', 'EAM_DEPR_ACCT_SALES', '', 'STRING', 'EAM', '折旧借方科目-销售成本中心(预留)', 'seed'),
       ('param-eam-depr-default', 'EAM_DEPR_ACCT_DEFAULT', '6602', 'STRING', 'EAM', '折旧借方科目-默认(未维护/未匹配)', 'seed'),
       ('param-eam-depr-credit', 'EAM_DEPR_CREDIT', '1602', 'STRING', 'EAM', '折旧凭证贷方科目-累计折旧', 'seed'),
       ('param-depr-residual-alert', 'DEPRECIATION_RESIDUAL_ALERT', '0.10', 'NUMBER', 'EAM', '残值占NBV预警阈值(C-4.6-04)', 'seed'),
       ('param-eam-impair-cr', 'EAM_DEPR_IMPAIR_CR', '160201', 'STRING', 'EAM', '减值凭证借方-累计折旧减值', 'seed'),
       ('param-eam-impair-reserve', 'EAM_IMPAIRMENT_RESERVE', '1603', 'STRING', 'EAM', '减值凭证贷方-固定资产减值准备', 'seed');

-- ---------- 3. 卡片加列（design D1：减值准备/残值额/起提月，NBV 计算不落列） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'IMPAIRMENT');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN IMPAIRMENT DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''减值准备(BR-4.13-15)'' AFTER ACCUM_DEPR',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'RESIDUAL_VALUE');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN RESIDUAL_VALUE DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''残值额(原值×残值率,缓存)'' AFTER RESIDUAL_RATE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'DEPR_START_MONTH');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN DEPR_START_MONTH VARCHAR(6) DEFAULT NULL COMMENT ''起提月 yyyyMM(启用次月,BR-4.13-11)'' AFTER EFFECTIVE_DATE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4. 折旧计提单（design D1：PERIOD_TAG 唯一幂等锚） ----------
CREATE TABLE IF NOT EXISTS erp_eam_depreciation_run (
    ID VARCHAR(64) NOT NULL,
    -- JZ + yyyyMM
    RUN_NO VARCHAR(32) NOT NULL,
    -- 会计月 yyyyMM（同月已提 422 幂等锚，BR-4.6-28 按月计提）
    PERIOD_TAG VARCHAR(6) NOT NULL,
    STATUS VARCHAR(16) NOT NULL DEFAULT 'POSTED',
    VOUCHER_ID VARCHAR(64),
    TOTAL_DEPR DECIMAL(18,2) NOT NULL DEFAULT 0,
    -- BR-4.13-13 勾稽差异（明细汇总 vs 总账折旧科目发生额）
    RECON_DIFF DECIMAL(18,2) NOT NULL DEFAULT 0,
    WARNING_COUNT INT NOT NULL DEFAULT 0,
    -- 1=已冲回（整单全冲）
    REVERSED_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    -- 冲回关联的变动单 ID（erp_eam_asset_change）
    REVERSE_CHANGE_ID VARCHAR(64),
    REMARK VARCHAR(500),
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    -- 仅未冲回行互斥：冲回（REVERSED_FLAG='1'）后可重提，唯一键不再冲突
    UNIQUE KEY UK_EAM_DEPR_PERIOD (PERIOD_TAG, REVERSED_FLAG),
    UNIQUE KEY UK_EAM_DEPR_RUN_NO (RUN_NO),
    KEY IDX_EAM_DEPR_VOUCHER (VOUCHER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 迁移后幂等补列（已建表的旧库走此路径，同 131/132 加列惯例）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_depreciation_run'
               AND COLUMN_NAME = 'REVERSED_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_depreciation_run ADD COLUMN REVERSED_FLAG VARCHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''1=已冲回(整单全冲)'' AFTER WARNING_COUNT',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_depreciation_run'
               AND COLUMN_NAME = 'REVERSE_CHANGE_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_depreciation_run ADD COLUMN REVERSE_CHANGE_ID VARCHAR(64) DEFAULT NULL COMMENT ''冲回关联变动单ID(erp_eam_asset_change)'' AFTER REVERSED_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 唯一键改为"仅未冲回行互斥"：冲回后软删/重提不再撞唯一键（同月仅允许一条未冲回计提单）
SET @old_cols = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_eam_depreciation_run'
                   AND INDEX_NAME = 'UK_EAM_DEPR_PERIOD'
                   AND NON_UNIQUE = 0);
SET @sql := IF(@old_cols = 1,
    'ALTER TABLE erp_eam_depreciation_run DROP INDEX UK_EAM_DEPR_PERIOD',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @new_cols = (SELECT COUNT(*) FROM information_schema.STATISTICS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_eam_depreciation_run'
                   AND INDEX_NAME = 'UK_EAM_DEPR_PERIOD'
                   AND NON_UNIQUE = 0);
SET @sql := IF(@new_cols = 0,
    'ALTER TABLE erp_eam_depreciation_run ADD UNIQUE KEY UK_EAM_DEPR_PERIOD (PERIOD_TAG, REVERSED_FLAG)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 5. 折旧明细（一行一卡；明细↔凭证双向反查） ----------
CREATE TABLE IF NOT EXISTS erp_eam_depreciation_detail (
    ID VARCHAR(64) NOT NULL,
    RUN_ID VARCHAR(64) NOT NULL,
    PERIOD_TAG VARCHAR(6) NOT NULL,
    CARD_ID VARCHAR(64) NOT NULL,
    CARD_NO VARCHAR(32),
    -- 卡片快照（design D1：明细留存计提时卡片口径）
    ASSET_NAME VARCHAR(255),
    ASSET_COST DECIMAL(18,2) NOT NULL DEFAULT 0,
    DEPRECIATION_METHOD VARCHAR(16),
    BEGIN_ACCUM_DEPR DECIMAL(18,2) NOT NULL DEFAULT 0,
    -- 期初/期末（本期计提前后）
    BEGIN_NBV DECIMAL(18,2) NOT NULL DEFAULT 0,
    PERIOD_DEPR DECIMAL(18,2) NOT NULL DEFAULT 0,
    END_ACCUM_DEPR DECIMAL(18,2) NOT NULL DEFAULT 0,
    END_NBV DECIMAL(18,2) NOT NULL DEFAULT 0,
    -- 成本分摊（FR-4.6-5-4）
    COST_CENTER_CODE VARCHAR(32),
    DEBIT_ACCOUNT VARCHAR(32),
    -- C-4.6-04 残值预警标记
    RESIDUAL_ALERT VARCHAR(1) NOT NULL DEFAULT '0',
    -- BR-4.13-12 提足截减标记
    CAPPED VARCHAR(1) NOT NULL DEFAULT '0',
    -- 冲回标记（整单全冲）
    REVERSED VARCHAR(1) NOT NULL DEFAULT '0',
    VOUCHER_ID VARCHAR(64),
    REMARK VARCHAR(500),
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_EAM_DEPR_D_RUN (RUN_ID),
    KEY IDX_EAM_DEPR_D_CARD (CARD_ID),
    KEY IDX_EAM_DEPR_D_PERIOD (PERIOD_TAG),
    KEY IDX_EAM_DEPR_D_VOUCHER (VOUCHER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 明细补列：期初累计折旧 + 卡片快照（幂等；不依赖 AFTER 锚点，旧表/新表均兼容）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_depreciation_detail'
               AND COLUMN_NAME = 'BEGIN_ACCUM_DEPR');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_depreciation_detail ADD COLUMN BEGIN_ACCUM_DEPR DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''期初累计折旧''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_depreciation_detail'
               AND COLUMN_NAME = 'ASSET_NAME');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_depreciation_detail ADD COLUMN ASSET_NAME VARCHAR(255) COMMENT ''卡片名称快照''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_depreciation_detail'
               AND COLUMN_NAME = 'ASSET_COST');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_depreciation_detail ADD COLUMN ASSET_COST DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT ''卡片原值快照''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_depreciation_detail'
               AND COLUMN_NAME = 'DEPRECIATION_METHOD');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_depreciation_detail ADD COLUMN DEPRECIATION_METHOD VARCHAR(16) COMMENT ''折旧方法快照''',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 6. 资产变动单（design D4：POLICY/IMPAIRMENT/REVERSE 三型共用 + 快照） ----------
CREATE TABLE IF NOT EXISTS erp_eam_asset_change (
    ID VARCHAR(64) NOT NULL,
    -- BD + yyyyMMdd + - + 4 位流水
    CHANGE_NO VARCHAR(32) NOT NULL,
    -- POLICY / IMPAIRMENT / REVERSE
    CHANGE_TYPE VARCHAR(16) NOT NULL,
    CARD_ID VARCHAR(64) NOT NULL,
    CARD_NO VARCHAR(32),
    -- 政策变更快照（BR-4.13-14：before/after，不追溯）
    BEFORE_LIFE_YEARS INT,
    AFTER_LIFE_YEARS INT,
    BEFORE_RESIDUAL_RATE DECIMAL(5,4),
    AFTER_RESIDUAL_RATE DECIMAL(5,4),
    BEFORE_METHOD VARCHAR(16),
    AFTER_METHOD VARCHAR(16),
    -- 减值（BR-4.13-15）
    IMPAIR_AMOUNT DECIMAL(18,2),
    IMPAIR_AFTER_NBV DECIMAL(18,2),
    IMPAIR_VOUCHER_ID VARCHAR(64),
    -- 政策变更生效期间（下期 yyyyMM）
    EFFECTIVE_PERIOD VARCHAR(6),
    -- 冲回（REVERSE）：原计提单 + 凭证 + 原因
    RUN_ID VARCHAR(64),
    REVERSE_VOUCHER_ID VARCHAR(64),
    -- 审批底座锚点
    APPR_ID VARCHAR(64),
    STATUS VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    REASON VARCHAR(500),
    REMARK VARCHAR(500),
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_EAM_CHANGE_NO (CHANGE_NO),
    KEY IDX_EAM_CHANGE_CARD (CARD_ID),
    KEY IDX_EAM_CHANGE_RUN (RUN_ID),
    KEY IDX_EAM_CHANGE_TYPE (CHANGE_TYPE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
