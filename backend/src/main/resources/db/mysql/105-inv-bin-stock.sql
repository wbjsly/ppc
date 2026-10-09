-- 105 仓位维度与分配台账（change add-bin-assignment，spec bin-assignment / stock-posting-engine / stock-snapshot / warehouse-master / item-master-creation）
-- 库存行升为批次×仓位粒度 + 流水/日结带位 + 物料存储属性 + 分配台账 + 冻结行明细列 + 菜单 PERM
-- 幂等：CREATE IF NOT EXISTS / information_schema + PREPARE 动态 DDL（040/055/101/102/103/104 范式）

-- ---------- 1) erp_inv_stock 加仓位列（NOT NULL DEFAULT '' 即完成存量回填，spec warehouse-master D16） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_stock'
        AND COLUMN_NAME = 'BIN_CODE') = 0,
    'ALTER TABLE erp_inv_stock
       ADD COLUMN BIN_CODE VARCHAR(64) NOT NULL DEFAULT ''''
         COMMENT ''仓位编号（空串=未分配虚拟位，bin-assignment 4.4.5 补上架）'' AFTER BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 存量兜底清理（加列时已按默认值填充；仅防 NULL，幂等）
UPDATE erp_inv_stock SET BIN_CODE = ''
 WHERE BIN_CODE IS NULL;

-- ---------- 2) 库存唯一键升级：(WH,ITEM,BATCH) → (WH,ITEM,BATCH,BIN) ----------
-- 先删旧唯一键（存在才删），再建新唯一键（不存在才建）；两步均幂等
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_stock'
        AND INDEX_NAME = 'UK_STOCK_WH_ITEM_BATCH') > 0,
    'ALTER TABLE erp_inv_stock DROP INDEX UK_STOCK_WH_ITEM_BATCH',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_stock'
        AND INDEX_NAME = 'UK_STOCK_WH_ITEM_BIN') = 0,
    'ALTER TABLE erp_inv_stock
       ADD UNIQUE KEY UK_STOCK_WH_ITEM_BIN (WAREHOUSE_CODE, ITEM_CODE, BATCH_NO, BIN_CODE)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 仓位维度查询索引（Tab B 未分配队列 / 按位查库存）
SET @ddl = IF(
    (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_stock'
        AND INDEX_NAME = 'IDX_STOCK_BIN') = 0,
    'ALTER TABLE erp_inv_stock
       ADD KEY IDX_STOCK_BIN (WAREHOUSE_CODE, BIN_CODE)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 3) 出入库流水加仓位列（位级 before/after，spec stock-posting-engine 流水写入） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_transaction'
        AND COLUMN_NAME = 'BIN_CODE') = 0,
    'ALTER TABLE erp_inv_transaction
       ADD COLUMN BIN_CODE VARCHAR(64) NOT NULL DEFAULT ''''
         COMMENT ''仓位编号（位级一维一条；空串=未分配位）'' AFTER BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 流水按位筛选索引
SET @ddl = IF(
    (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_transaction'
        AND INDEX_NAME = 'IDX_INV_TXN_BIN') = 0,
    'ALTER TABLE erp_inv_transaction
       ADD KEY IDX_INV_TXN_BIN (WAREHOUSE_CODE, BIN_CODE, CREATE_DATE)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4) 日结余额快照加仓位列并升级唯一键（spec stock-snapshot 日结粒度 +仓位） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_daily_balance'
        AND COLUMN_NAME = 'BIN_CODE') = 0,
    'ALTER TABLE erp_inv_daily_balance
       ADD COLUMN BIN_CODE VARCHAR(64) NOT NULL DEFAULT ''''
         COMMENT ''仓位编号（日结粒度=日期+仓库+物料+批次+仓位）'' AFTER BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_daily_balance'
        AND INDEX_NAME = 'UK_INV_DAILY') > 0,
    'ALTER TABLE erp_inv_daily_balance DROP INDEX UK_INV_DAILY',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_daily_balance'
        AND INDEX_NAME = 'UK_INV_DAILY_BIN') = 0,
    'ALTER TABLE erp_inv_daily_balance
       ADD UNIQUE KEY UK_INV_DAILY_BIN (BAL_DATE, WAREHOUSE_CODE, ITEM_CODE, BATCH_NO, BIN_CODE)',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 5) 物料存储属性三列（spec item-master-creation / bin-assignment 合规数据源，偏差 D2 NULL 放行） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mdm_item'
        AND COLUMN_NAME = 'TEMP_LEVEL') = 0,
    'ALTER TABLE erp_mdm_item
       ADD COLUMN TEMP_LEVEL VARCHAR(16) DEFAULT NULL
         COMMENT ''温湿度等级（TEMP_LEVEL 字典 code；NULL=无要求，分配合规跳过）'',
       ADD COLUMN HAZARD_LEVEL VARCHAR(16) DEFAULT NULL
         COMMENT ''危化品等级（HAZARD_LEVEL 字典 code；NULL=无要求）'',
       ADD COLUMN CLEAN_LEVEL VARCHAR(16) DEFAULT NULL
         COMMENT ''洁净等级（CLEAN_LEVEL 字典 code；NULL=无要求）'' AFTER HAZARD_LEVEL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 6) 分配台账（spec bin-assignment 分配台账与状态机，design D4） ----------
CREATE TABLE IF NOT EXISTS erp_inv_putaway (
    ID VARCHAR(64) NOT NULL,
    -- GR / 后续退货等来源单据类型
    SOURCE_TYPE VARCHAR(32) NOT NULL COMMENT '来源单据类型（GR...）',
    SOURCE_DOC_NO VARCHAR(64) NOT NULL COMMENT '来源单号（GR 单号）',
    SOURCE_LINE_NO INT NOT NULL DEFAULT 0 COMMENT '来源行号（0=无行粒度，如未分配位上架）',
    WAREHOUSE_CODE VARCHAR(32) NOT NULL COMMENT '仓库编码',
    ITEM_CODE VARCHAR(64) NOT NULL,
    BATCH_NO VARCHAR(64) NOT NULL DEFAULT '' COMMENT '批次号',
    QTY DECIMAL(18,4) NOT NULL COMMENT '分配/上架数量（分配不改库存数量）',
    BIN_CODE VARCHAR(64) NOT NULL COMMENT '目标仓位编号',
    -- RECOMMENDED 推荐 / CONFIRMED 确认（过账取位依据）
    STATUS VARCHAR(16) NOT NULL DEFAULT 'RECOMMENDED' COMMENT '状态机 RECOMMENDED→CONFIRMED',
    -- 改派=新记录替代旧记录，旧记录保留并由新记录反向关联（design D4 禁止原地改 BIN_CODE）
    SUPERSEDED_BY VARCHAR(64) DEFAULT NULL COMMENT '被哪条新记录替代（改派留痕）',
    REMARK VARCHAR(500) DEFAULT NULL,
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    KEY IDX_INV_PUTAWAY_DOC (SOURCE_DOC_NO, SOURCE_LINE_NO, STATUS),
    KEY IDX_INV_PUTAWAY_BIN (WAREHOUSE_CODE, BIN_CODE, STATUS),
    KEY IDX_INV_PUTAWAY_ITEM (ITEM_CODE, BATCH_NO)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓位分配台账（4.4.5，强前置校验依据）';

-- ---------- 7) 冻结单加行级明细列（design D7：按位行拆分平移、解冻原路回补） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_freeze'
        AND COLUMN_NAME = 'DETAIL_JSON') = 0,
    'ALTER TABLE erp_inv_freeze
       ADD COLUMN DETAIL_JSON TEXT DEFAULT NULL
         COMMENT ''行级平移明细 JSON [{binCode,qty}]（解冻原路回补）'' AFTER REMARK',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 8) 存量库菜单 PERM 补丁（099 被 marker 跳过不重跑；产物行由生成器同步更新） ----------
UPDATE erp_admin_menu
   SET PERM = 'ROLE_WAREHOUSE,ROLE_ADMIN'
 WHERE ID = 'M4-4-5';
