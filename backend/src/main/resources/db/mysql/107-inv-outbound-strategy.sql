-- 107 出库策略：批次效期锁定状态位 + 出库行 BIN_CODE + 偏离台账 + 效期参数种子
--     （change add-outbound-strategy，spec outbound-strategy / stock-posting-engine /
--       material-issue / batch-master）
-- 幂等：information_schema + PREPARE 动态 DDL / CREATE IF NOT EXISTS / INSERT IGNORE（105/106 范式）

-- ---------- 1) erp_inv_batch 加 EXPIRY_LOCK_FLAG（效期锁定状态位，design D4） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_batch'
        AND COLUMN_NAME = 'EXPIRY_LOCK_FLAG') = 0,
    'ALTER TABLE erp_inv_batch
       ADD COLUMN EXPIRY_LOCK_FLAG VARCHAR(1) NOT NULL DEFAULT ''0''
         COMMENT ''效期锁定 1/0：剩余天数<有效期×EXPIRY_LOCK_RATIO 置 1（扫描+实时计算维护，用户不可编辑，C-4.4-03）''
         AFTER EXPIRY_DATE',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2) 出库行加 BIN_CODE（4.6.3 拣货推荐回写，design D6） ----------
-- 领料行
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_material_issue_line'
        AND COLUMN_NAME = 'BIN_CODE') = 0,
    'ALTER TABLE erp_inv_material_issue_line
       ADD COLUMN BIN_CODE VARCHAR(64) DEFAULT NULL
         COMMENT ''拣货推荐回写仓位（空=过账时引擎位级 FIFO 分配）'' AFTER BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 调拨行
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_transfer_order_line'
        AND COLUMN_NAME = 'BIN_CODE') = 0,
    'ALTER TABLE erp_inv_transfer_order_line
       ADD COLUMN BIN_CODE VARCHAR(64) DEFAULT NULL
         COMMENT ''拣货推荐回写仓位（空=过账时引擎位级 FIFO 分配）'' AFTER BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 报废行
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_scrap_order_line'
        AND COLUMN_NAME = 'BIN_CODE') = 0,
    'ALTER TABLE erp_inv_scrap_order_line
       ADD COLUMN BIN_CODE VARCHAR(64) DEFAULT NULL
         COMMENT ''拣货推荐回写仓位（空=过账时引擎位级 FIFO 分配）'' AFTER BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 销售发货行（sales-shipment 域，行值优先过账依据）
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_sd_shipment_line'
        AND COLUMN_NAME = 'BIN_CODE') = 0,
    'ALTER TABLE erp_sd_shipment_line
       ADD COLUMN BIN_CODE VARCHAR(64) DEFAULT NULL
         COMMENT ''拣货推荐回写仓位（空=过账时按 BATCH_ALLOC FIFO 选批）'' AFTER WAREHOUSE_CODE',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 3) 改批偏离台账（C-4.4-08 非波次首期留痕，design D5） ----------
CREATE TABLE IF NOT EXISTS erp_inv_batch_deviation (
    ID VARCHAR(64) NOT NULL,
    -- 来源：MATERIAL_ISSUE / PICK_RECOMMEND（4.6.3 确认）
    SRC_DOC_TYPE VARCHAR(32) NOT NULL COMMENT '来源单据类型',
    SRC_DOC_NO VARCHAR(64) NOT NULL COMMENT '来源单据号',
    LINE_NO INT DEFAULT NULL COMMENT '来源行号',
    ITEM_CODE VARCHAR(64) NOT NULL,
    WAREHOUSE_CODE VARCHAR(32) DEFAULT NULL,
    RECOMMENDED_BATCH VARCHAR(64) DEFAULT NULL COMMENT '系统推荐批次（无推荐时 NULL）',
    ACTUAL_BATCH VARCHAR(64) NOT NULL COMMENT '用户实际指定批次',
    REASON VARCHAR(500) NOT NULL COMMENT '偏离原因（必填，空则 422）',
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_BDV_DOC (SRC_DOC_NO, LINE_NO),
    KEY IDX_BDV_ITEM (ITEM_CODE, CREATE_DATE),
    KEY IDX_BDV_CREATE (CREATE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='出库改批偏离台账（4.6.1 偏离监控，非波次首期仅留痕不走审批）';

-- ---------- 4) 效期参数种子（outbound-strategy，FR-4.4-3-3 / BR-4.4-20/21） ----------
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-expiry-lock-ratio', 'EXPIRY_LOCK_RATIO', '0.5', 'RATE', 'INV',
 '效期锁定比例：剩余有效天数 < 有效期总天数×该比例时批次置效期锁定，剔出推荐池并禁止出库过账（BR-4.4-20 / C-4.4-03），下一次出库策略计算生效', 'seed', '0', 0),
('param-min-remaining-shelf-days', 'MIN_REMAINING_SHELF_DAYS', '30', 'DAYS', 'INV',
 '出库效期冗余最低剩余天数：低于该天数标记效期警告但不阻断，出库单加注剩余效期提示（BR-4.4-21），下一次出库策略计算生效', 'seed', '0', 0);
