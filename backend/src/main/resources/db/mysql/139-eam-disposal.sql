-- 139-eam-disposal.sql
-- add-asset-disposal：资产报废处置（design D1：三表 + 分类双标记 + 卡片处置日 + 科目参数种子 + 菜单权限回填）
-- 幂等：CREATE TABLE IF NOT EXISTS + INSERT IGNORE + information_schema 判存/PREPARE（同 133~138）

-- ---------- 1. 科目种子（6115 资产处置损益；1606 固定资产清理已由 137 种植，此处 IGNORE 幂等核对） ----------
INSERT IGNORE INTO erp_fin_account (ID, ACCOUNT_CODE, ACCOUNT_NAME, DIRECTION, CATEGORY, STATUS, CREATE_BY)
VALUES ('acc-eam-disposal-pl', '6115', '资产处置损益', 'D', 'EXPENSE', 'ACTIVE', 'seed'),
       ('acc-1606', '1606', '固定资产清理', 'D', 'ASSET', 'ACTIVE', 'seed');

-- 回收入库载体：虚拟回收服务商（BR-35 一键入库走 GoodsReceiptService.createFree 需活跃供应商）
INSERT IGNORE INTO erp_mdm_supplier (ID, SUPPLIER_CODE, SUPPLIER_NAME, STATUS, CREATE_BY)
VALUES ('sup-eam-recycle', 'SUPEAMRECYCLE', '资产回收服务商（系统虚拟）', 'QUALIFIED', 'seed');

-- ---------- 2. 参数种子（design D3：处置审批分级阈值 A/B） ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY)
VALUES ('param-eam-disposal-th-a', 'ASSET_DISPOSAL_THRESHOLD_A', '500000', 'NUMBER', 'EAM', '处置审批阈值A：原值≥A 追加财务经理（默认50万）', 'seed'),
       ('param-eam-disposal-th-b', 'ASSET_DISPOSAL_THRESHOLD_B', '5000000', 'NUMBER', 'EAM', '处置审批阈值B：原值≥B 追加总经理（默认500万）', 'seed');

-- ---------- 3. 菜单权限回填（7.6.1/7.6.2/7.6.3 行已由 099 种子预插，此处仅补权限；099 EXTRA_PERM 同步重生成） ----------
UPDATE erp_admin_menu SET PERM = 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_GM,ROLE_ADMIN'
WHERE ID IN ('M7-6', 'M7-6-1', 'M7-6-2', 'M7-6-3') AND (PERM IS NULL OR PERM = '');

-- ---------- 4. 处置单 ----------
CREATE TABLE IF NOT EXISTS erp_eam_disposal_order (
    ID VARCHAR(64) NOT NULL,
    DSP_NO VARCHAR(32) NOT NULL COMMENT '处置单号 DSP+yyyyMMdd+4位流水',
    DISPOSAL_TYPE VARCHAR(16) NOT NULL COMMENT 'SCRAP 报废 / SALE 出售',
    REASON_CODE VARCHAR(32) COMMENT 'TECH淘汰/ DAMAGE损坏/ IDLE闲置/ POLICY政策性',
    REASON_NOTE VARCHAR(512),
    APP_STATUS VARCHAR(24) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PENDING_APPROVE/APPROVED/EXECUTED/RECEIVABLE/CLOSED/REJECTED',
    APPR_ID VARCHAR(64) COMMENT '审批实例锚',
    EST_RESIDUAL DECIMAL(18,2) COMMENT '评估残值（出售=市场价/报废=残料回收价）',
    EVAL_REPORT_NO VARCHAR(128) COMMENT '评估报告编号/附件锚',
    REVIEW_NOTE VARCHAR(512) COMMENT '残值偏高复核说明（C-4.13-05）',
    TOTAL_COST DECIMAL(18,2) DEFAULT 0,
    TOTAL_ACCUM DECIMAL(18,2) DEFAULT 0,
    TOTAL_NBV DECIMAL(18,2) DEFAULT 0,
    TOTAL_PROCEEDS DECIMAL(18,2) DEFAULT 0 COMMENT '处置价合计（行评估价）',
    TOTAL_RECYCLE DECIMAL(18,2) DEFAULT 0 COMMENT '残料回收合计',
    GAIN_LOSS DECIMAL(18,2) DEFAULT 0 COMMENT '处置损益 = 处置价+回收-NBV',
    RECEIVABLE_BAL DECIMAL(18,2) DEFAULT 0 COMMENT '应收处置款余额（BR-35 未结不得关闭）',
    EXECUTED_AT DATETIME COMMENT '执行确认时间（卡片 DISPOSED_AT 锚）',
    REPORT_APPR_ID VARCHAR(64) COMMENT '结案报告审批实例',
    REPORT_SUMMARY VARCHAR(1024) COMMENT '结案报告摘要',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    UNIQUE KEY UK_EAM_DSP_NO (DSP_NO),
    KEY IDX_EAM_DSP_STATUS (APP_STATUS),
    KEY IDX_EAM_DSP_TYPE (DISPOSAL_TYPE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 5. 处置明细（一卡一行，批量多行；快照冻结比对基准） ----------
CREATE TABLE IF NOT EXISTS erp_eam_disposal_line (
    ID VARCHAR(64) NOT NULL,
    ORDER_ID VARCHAR(64) NOT NULL,
    CARD_ID VARCHAR(64),
    CARD_NO VARCHAR(32),
    ASSET_NAME VARCHAR(256),
    CATEGORY_CODE VARCHAR(32),
    DEPT_NAME VARCHAR(128),
    COST_CENTER_CODE VARCHAR(32),
    ORIG_COST DECIMAL(18,2) COMMENT '快照原值',
    ACCUM_DEPR DECIMAL(18,2) COMMENT '快照累计折旧',
    NBV DECIMAL(18,2) COMMENT '快照 NBV（=原值-累计-减值，执行时重取现值校验）',
    FULLY_DEPR_FLAG CHAR(1) DEFAULT '0' COMMENT '已提足标记（BR-32 校验锚）',
    LINE_PROCEEDS DECIMAL(18,2) COMMENT '行处置价/评估价',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    KEY IDX_EAM_DSPL_ORDER (ORDER_ID),
    KEY IDX_EAM_DSPL_CARD (CARD_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 6. 残料回收明细（→ 一键入库 GR_ID 回写） ----------
CREATE TABLE IF NOT EXISTS erp_eam_disposal_recycle (
    ID VARCHAR(64) NOT NULL,
    ORDER_ID VARCHAR(64) NOT NULL,
    ITEM_NAME VARCHAR(256) NOT NULL COMMENT '回收物名称',
    QTY DECIMAL(18,4) DEFAULT 1,
    EST_PRICE DECIMAL(18,2) DEFAULT 0 COMMENT '估价（入库成本价，冲减处置损失）',
    GR_ID VARCHAR(64) COMMENT '库存入库单号（执行确认后回写）',
    GR_STATUS VARCHAR(16) DEFAULT 'PENDING' COMMENT 'PENDING/POSTED',
    CREATE_BY VARCHAR(64),
    CREATE_DATE DATETIME,
    UPDATE_BY VARCHAR(64),
    UPDATE_DATE DATETIME,
    VER_NO INT DEFAULT 0,
    DEL_FLAG CHAR(1) DEFAULT '0',
    REMARK VARCHAR(512),
    PRIMARY KEY (ID),
    KEY IDX_EAM_DSPR_ORDER (ORDER_ID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ---------- 7. 分类双标记（BR-36 环保安全/数据销毁，判存加列同 RISK_LEVEL 模式） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_category'
               AND COLUMN_NAME = 'ENV_REVIEW_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_category ADD COLUMN ENV_REVIEW_FLAG CHAR(1) DEFAULT ''0'' COMMENT ''1=执行处置须环保安全评估（BR-36）'' AFTER RISK_LEVEL',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_category'
               AND COLUMN_NAME = 'DATA_DESTROY_FLAG');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_category ADD COLUMN DATA_DESTROY_FLAG CHAR(1) DEFAULT ''0'' COMMENT ''1=执行处置须数据销毁证明（BR-36）'' AFTER ENV_REVIEW_FLAG',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 8. 卡片处置日（BR-34 折旧当月豁免锚） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_asset_card'
               AND COLUMN_NAME = 'DISPOSED_AT');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_asset_card ADD COLUMN DISPOSED_AT DATETIME COMMENT ''处置执行确认日（BR-34 当月豁免/次月停提锚）'' AFTER LAST_USED_AT',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 9. 回收明细加物料编码列（BR-35 一键入库 createFree 行必填 itemCode） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_eam_disposal_recycle'
               AND COLUMN_NAME = 'ITEM_CODE');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_eam_disposal_recycle ADD COLUMN ITEM_CODE VARCHAR(32) COMMENT ''回收物物料编码（入库载体，须有物料主数据）'' AFTER ITEM_NAME',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
