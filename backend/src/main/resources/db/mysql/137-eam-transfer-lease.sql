-- 137-eam-transfer-lease.sql
-- add-asset-transfer-lease：资产调拨与租赁（design D1：三表 + 卡片五列 + 科目参数角色种子 + 菜单权限回填）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 判存/PREPARE（同 133~135）

-- ---------- 1. 科目种子（design D1：清理/内部往来/使用权资产/租赁负债/租赁利息） ----------
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, CREATE_BY)
VALUES ('acc-1606', '1606', '固定资产清理', 'D', 'ASSET', 'ACTIVE', 'seed'),
       ('acc-eam-interco', '1221', '内部往来', 'D', 'ASSET', 'ACTIVE', 'seed'),
       ('acc-eam-rou', '1621', '使用权资产', 'D', 'ASSET', 'ACTIVE', 'seed'),
       ('acc-eam-lease-liab', '2701', '租赁负债', 'C', 'LIABILITY', 'ACTIVE', 'seed'),
       ('acc-eam-lease-int', '6603', '租赁利息费用', 'D', 'EXPENSE', 'ACTIVE', 'seed');

-- ---------- 2. 参数种子（design D1/D5：内部往来科目 + 默认折现率） ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY)
VALUES ('param-eam-interco-acct', 'EAM_INTERCO_ACCOUNT', '1221', 'STRING', 'EAM', '跨法人调拨内部往来科目', 'seed'),
       ('param-lease-discount-rate', 'LEASE_DEFAULT_DISCOUNT_RATE', '4.5', 'NUMBER', 'EAM', '租赁默认折现率%（取不到内含利率时用）', 'seed');

-- ---------- 3. 角色种子（design D1：部门主管 + 财务经理） ----------
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY)
VALUES ('role-dept-head', 'ROLE_DEPT_HEAD', '部门主管', 63, '1', 'seed'),
       ('role-eam-fin-mgr', 'ROLE_FIN_MGR', '财务经理', 64, '1', 'seed');

-- ---------- 4. 菜单权限回填（7.4.1/7.4.2/7.4.3 行已由 099 种子预插，此处仅补权限；INSERT IGNORE 不更新既有行） ----------
UPDATE erp_admin_menu SET PERM = 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_ADMIN'
WHERE ID IN ('M7-4', 'M7-4-1', 'M7-4-2', 'M7-4-3') AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_EQUIP_ENG,ROLE_EQUIP_MGR,ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_FIN_MGR,ROLE_DEPT_HEAD,ROLE_ADMIN'
WHERE ID = 'M7';

-- ---------- 5. 卡片加列（design D1：法人/质押/租赁锚/最后使用日） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'LEGAL_ENTITY');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN LEGAL_ENTITY VARCHAR(32) DEFAULT NULL COMMENT ''所属法人 LE_CODE(跨法人调拨即改)'' AFTER CATEGORY_CODE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'PLEDGED_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN PLEDGED_FLAG VARCHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''质押抵押 1=是(BR-21 调拨阻断)'' AFTER LOCK_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'LEASE_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN LEASE_FLAG VARCHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''租赁资产(ROU) 1=是'' AFTER PLEDGED_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'LEASE_CONTRACT_ID');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN LEASE_CONTRACT_ID VARCHAR(64) DEFAULT NULL COMMENT ''租赁合同锚(ROU 卡)'' AFTER LEASE_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'LAST_USED_AT');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN LAST_USED_AT DATE DEFAULT NULL COMMENT ''最后使用日(闲置判定锚 BR-4.13-05)'' AFTER EFFECTIVE_DATE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量回填：法人 = 主数据首条 LE_CODE；最后使用日 = 启用日期（幂等：仅回填 NULL 行）
UPDATE erp_eam_asset_card c
SET c.LEGAL_ENTITY = COALESCE(c.LEGAL_ENTITY,
        (SELECT LE_CODE FROM erp_mdm_legal_entity WHERE DEL_FLAG = '0' ORDER BY LE_CODE LIMIT 1))
WHERE c.LEGAL_ENTITY IS NULL;
UPDATE erp_eam_asset_card SET LAST_USED_AT = EFFECTIVE_DATE WHERE LAST_USED_AT IS NULL;

-- ---------- 6. 调拨单（design D1：FROM/TO 三组快照 + 交接双确认 + 内部交易锚） ----------
CREATE TABLE IF NOT EXISTS erp_eam_transfer_order (
    ID VARCHAR(64) NOT NULL,
    TRF_NO VARCHAR(32) NOT NULL COMMENT '调拨单号 TRF+yyyyMMdd+4位流水',
    TRANSFER_TYPE VARCHAR(12) NOT NULL COMMENT 'DEPT 部门间 / CROSS_LE 跨法人',
    CARD_ID VARCHAR(64) NOT NULL,
    CARD_NO VARCHAR(32) NOT NULL,
    -- FROM/TO 三组快照（申请时点落单，完成前即可反查；折旧回溯取 FROM_CC）
    FROM_DEPT VARCHAR(128), FROM_CC VARCHAR(32), FROM_LE VARCHAR(32),
    TO_DEPT VARCHAR(128), TO_CC VARCHAR(32), TO_LE VARCHAR(32),
    FROM_LOCATION VARCHAR(128), TO_LOCATION VARCHAR(128) COMMENT '存放地点快照（完成即改卡片地点）',
    REASON VARCHAR(500) NOT NULL COMMENT '调拨原因',
    PLAN_DATE DATE COMMENT '拟调拨日期',
    STATUS VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_APPROVE/APPROVED/HANDOVERED/COMPLETED/REJECTED/CANCELLED',
    APPR_ID VARCHAR(64) COMMENT '审批底座实例',
    REJECT_REASON VARCHAR(500),
    -- 交接双确认（FR-4.13-4-3：双齐才可完成）
    OUT_CONFIRM_BY VARCHAR(64), OUT_CONFIRM_AT DATETIME,
    IN_CONFIRM_BY VARCHAR(64), IN_CONFIRM_AT DATETIME,
    COMPLETED_AT DATETIME COMMENT '完成时间（BR-22 折旧回溯锚）',
    -- 跨法人内部交易（BR-23/C-10）
    INTERCO_NO VARCHAR(32) COMMENT '内部交易单号 ICR+yyyyMMdd+4位流水',
    OUT_VOUCHER_ID VARCHAR(64) COMMENT '调出处置凭证',
    IN_VOUCHER_ID VARCHAR(64) COMMENT '调入建卡凭证',
    NEW_CARD_ID VARCHAR(64) COMMENT '调入方新卡（原值=NBV）',
    NBV_AMOUNT DECIMAL(18,2) COMMENT '内部交易金额=调出时点 NBV',
    REMARK VARCHAR(500),
    CREATE_BY VARCHAR(64), CREATE_DATE DATETIME, UPDATE_BY VARCHAR(64), UPDATE_DATE DATETIME,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0', VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_EAM_TRF_NO (TRF_NO),
    KEY IDX_EAM_TRF_CARD (CARD_ID),
    KEY IDX_EAM_TRF_STATUS (STATUS),
    KEY IDX_EAM_TRF_INTERCO (INTERCO_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 兼容已建表环境判存补列（NBV 与存放地点快照）
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_transfer_order'
               AND COLUMN_NAME = 'NBV_AMOUNT');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_transfer_order ADD COLUMN NBV_AMOUNT DECIMAL(18,2) COMMENT ''内部交易金额=调出时点 NBV'' AFTER NEW_CARD_ID',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_transfer_order'
               AND COLUMN_NAME = 'FROM_LOCATION');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_transfer_order ADD COLUMN FROM_LOCATION VARCHAR(128) COMMENT ''调出存放地点快照'' AFTER FROM_LE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_transfer_order'
               AND COLUMN_NAME = 'TO_LOCATION');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_transfer_order ADD COLUMN TO_LOCATION VARCHAR(128) COMMENT ''调入存放地点快照'' AFTER TO_LE',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 7. 租赁合同（design D1：利率留痕/选择权/豁免/分类/状态/变更快照） ----------
CREATE TABLE IF NOT EXISTS erp_eam_lease_contract (
    ID VARCHAR(64) NOT NULL,
    LSE_NO VARCHAR(32) NOT NULL COMMENT '合同号 LSE+yyyyMMdd+4位流水',
    LESSOR VARCHAR(255) NOT NULL COMMENT '出租方',
    ASSET_DESC VARCHAR(500) NOT NULL COMMENT '租赁资产描述',
    START_DATE DATE NOT NULL COMMENT '租赁期起',
    END_DATE DATE NOT NULL COMMENT '租赁期止',
    TERMS_MONTHS INT NOT NULL COMMENT '租期月数（引擎取用）',
    PAYMENT_AMOUNT DECIMAL(18,2) NOT NULL COMMENT '每期租金付款额',
    PAY_CYCLE_MONTHS INT NOT NULL DEFAULT 1 COMMENT '付款周期月数（1 月付/3 季付/6 半年付/12 年付）',
    DISCOUNT_RATE DECIMAL(9,6) COMMENT '年折现率（小数，0.045）',
    RATE_BASIS VARCHAR(16) COMMENT 'INCLUSIVE 租赁内含利率 / INCREMENTAL 增量借款利率（留痕 FR-4.13-4-8）',
    RENEW_OPTION VARCHAR(255) COMMENT '续租选择权说明',
    BUY_OPTION VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '购买选择权 1=有',
    BUY_PRICE DECIMAL(18,2) COMMENT '约定购买价',
    INDEX_FLOAT_FLAG VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '租金指数浮动 1=有（重估付款行不改利率）',
    CLASSIFY VARCHAR(16) COMMENT 'ROU/FINANCE/OPERATING/EXEMPT（人工确认）',
    CLASSIFY_BASIS VARCHAR(500) COMMENT '辅助判断依据留痕',
    CONFIRM_BY VARCHAR(64), CONFIRM_DATE DATE COMMENT '分类确认人/日',
    EXEMPT_REASON VARCHAR(500) COMMENT '豁免理由（BR-25 L5 留痕）',
    INIT_DIRECT_COST DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '初始直接费用',
    PREPAID_RENT DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '预付租金',
    LEASE_INCENTIVE DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '已收租赁激励',
    PV DECIMAL(18,2) COMMENT '租赁负债现值（确认时）',
    ROU_AMOUNT DECIMAL(18,2) COMMENT '使用权资产金额 = PV+初始费用+预付−激励',
    ROU_CARD_ID VARCHAR(64) COMMENT 'ROU 卡片',
    ESTIMATED_FLAG VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '默示续租暂估 1=按原条款暂估计提',
    APPR_ID VARCHAR(64) COMMENT '重新计量审批实例',
    STATUS VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/CLASSIFIED/CONFIRMED/MATURED/TERMINATED',
    CHANGE_JSON VARCHAR(2000) COMMENT '条款变更快照',
    REMARK VARCHAR(500),
    CREATE_BY VARCHAR(64), CREATE_DATE DATETIME, UPDATE_BY VARCHAR(64), UPDATE_DATE DATETIME,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0', VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_EAM_LSE_NO (LSE_NO),
    KEY IDX_EAM_LSE_STATUS (STATUS),
    KEY IDX_EAM_LSE_CARD (ROU_CARD_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 8. 摊余成本计划（design D1/D5：逐月行 + 实际利率法 + 快照归档） ----------
CREATE TABLE IF NOT EXISTS erp_eam_lease_schedule (
    ID VARCHAR(64) NOT NULL,
    CONTRACT_ID VARCHAR(64) NOT NULL,
    LSE_NO VARCHAR(32) NOT NULL,
    SEQ INT NOT NULL COMMENT '月序（1 起；T0 确认月行无利息无付款以 SEQ=0 表示）',
    PERIOD_TAG VARCHAR(6) NOT NULL COMMENT '会计月 yyyyMM',
    BEGIN_BAL DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '期初租赁负债',
    INTEREST DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '利息 = 期初 × 月利率',
    PAYMENT DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '付款（非付款月=0）',
    END_BAL DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '期末 = 期初+利息−付款',
    PROCESSED_FLAG VARCHAR(1) NOT NULL DEFAULT '0' COMMENT '月度处理 1=已出凭证',
    PROCESSED_AT DATETIME,
    VOUCHER_INT_ID VARCHAR(64) COMMENT '利息凭证（冲回锚）',
    VOUCHER_PAY_ID VARCHAR(64) COMMENT '付款凭证（冲回锚）',
    SNAPSHOT_SEQ INT NOT NULL DEFAULT 0 COMMENT '快照序（重计量旧行 +1 归档，当前行=0）',
    CREATE_BY VARCHAR(64), CREATE_DATE DATETIME, UPDATE_BY VARCHAR(64), UPDATE_DATE DATETIME,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0', VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_EAM_LS_CQS (CONTRACT_ID, SEQ, SNAPSHOT_SEQ),
    KEY IDX_EAM_LS_CONTRACT (CONTRACT_ID, SNAPSHOT_SEQ),
    KEY IDX_EAM_LS_PERIOD (PERIOD_TAG)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
