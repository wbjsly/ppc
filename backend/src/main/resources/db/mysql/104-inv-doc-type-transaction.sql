-- 104 出入库业务类型与流水（change add-stock-posting-engine，spec stock-doc-type / stock-posting-engine / inbound-workbench）
-- 类型配置 + 出入库流水 + 收货单入库确认列 + 菜单 PERM（4.4.1~4.4.4）
-- 幂等：CREATE IF NOT EXISTS / INSERT IGNORE / information_schema + PREPARE 动态 DDL（040/055/101/102/103 范式）

-- ---------- 1) 出入库业务类型（spec stock-doc-type，design D1） ----------
CREATE TABLE IF NOT EXISTS erp_inv_doc_type (
    ID VARCHAR(64) NOT NULL,
    -- 类型码全局唯一、创建后不可改（流水引用稳定性）
    TYPE_CODE VARCHAR(32) NOT NULL COMMENT '类型码 PURCHASE_IN/SALES_OUT/...',
    DIRECTION VARCHAR(4) NOT NULL COMMENT 'IN/OUT',
    TYPE_NAME VARCHAR(64) NOT NULL,
    -- 流水编号前缀（本期统一 TX，保留按类型扩展位）
    FLOW_PREFIX VARCHAR(8) NOT NULL DEFAULT 'TX',
    -- 缺省分配方式：AUTO_FIFO（引擎分配）/ MANUAL（请求指定批次）
    DEFAULT_ALLOC VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    NEED_BATCH TINYINT NOT NULL DEFAULT 1 COMMENT '1=批次必填+联动建档',
    NEED_SERIAL TINYINT NOT NULL DEFAULT 0 COMMENT '1=序列判重强制',
    ENABLED TINYINT NOT NULL DEFAULT 1,
    REMARK VARCHAR(500) DEFAULT NULL,
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_INV_DOC_TYPE (TYPE_CODE),
    KEY IDX_INV_DOC_TYPE_DIR (DIRECTION, ENABLED)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出入库业务类型配置（4.4.1 配套）';

-- ---------- 2) 出入库流水（spec stock-posting-engine，纯追加无 update 路径） ----------
CREATE TABLE IF NOT EXISTS erp_inv_transaction (
    ID VARCHAR(64) NOT NULL,
    -- TX+yyMMdd+6位日流水，全局唯一
    TXN_NO VARCHAR(32) NOT NULL COMMENT '流水号',
    DIRECTION VARCHAR(4) NOT NULL COMMENT 'IN/OUT（数量恒正，方向区分加减）',
    TYPE_CODE VARCHAR(32) NOT NULL COMMENT '出入库类型码',
    BIZ_DOC_TYPE VARCHAR(32) NOT NULL COMMENT '来源单据类型 GR/SHIPMENT/RETURN/MI...',
    BIZ_DOC_NO VARCHAR(64) NOT NULL COMMENT '来源单号',
    WAREHOUSE_CODE VARCHAR(32) NOT NULL,
    ITEM_CODE VARCHAR(64) NOT NULL,
    BATCH_NO VARCHAR(64) DEFAULT NULL,
    QTY DECIMAL(18,4) NOT NULL COMMENT '变动量（恒正）',
    BEFORE_QTY DECIMAL(18,4) NOT NULL COMMENT '该维度在手-前',
    AFTER_QTY DECIMAL(18,4) NOT NULL COMMENT '该维度在手-后',
    REMARK VARCHAR(500) DEFAULT NULL,
    CREATE_BY VARCHAR(64) DEFAULT NULL,
    CREATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP,
    UPDATE_BY VARCHAR(64) DEFAULT NULL,
    UPDATE_DATE DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    DEL_FLAG VARCHAR(1) NOT NULL DEFAULT '0',
    VER_NO INT NOT NULL DEFAULT 0,
    PRIMARY KEY (ID),
    UNIQUE KEY UK_INV_TXN_NO (TXN_NO),
    KEY IDX_INV_TXN_DOC (BIZ_DOC_TYPE, BIZ_DOC_NO),
    KEY IDX_INV_TXN_DIM (ITEM_CODE, WAREHOUSE_CODE, CREATE_DATE),
    KEY IDX_INV_TXN_TYPE (TYPE_CODE, CREATE_DATE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出入库流水（行级一维一条，BR-4.4-13/BR-4.11-15/BR-4.4-48）';

-- ---------- 3) 收货单入库确认列（spec inbound-workbench，FR-4.4-1-7） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_proc_gr'
        AND COLUMN_NAME = 'CONFIRM_BY') = 0,
    'ALTER TABLE erp_proc_gr
       ADD COLUMN CONFIRM_BY VARCHAR(64) DEFAULT NULL COMMENT ''入库确认人（POSTED→CONFIRMED，FR-4.4-1-7）'',
       ADD COLUMN CONFIRM_AT DATETIME DEFAULT NULL COMMENT ''入库确认时间'' AFTER POSTING_DATE',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 4) 类型种子（7 已建域 + 6 预注册未建域，design D1） ----------
INSERT IGNORE INTO erp_inv_doc_type (ID, TYPE_CODE, DIRECTION, TYPE_NAME, FLOW_PREFIX, DEFAULT_ALLOC, NEED_BATCH, NEED_SERIAL, ENABLED, CREATE_BY) VALUES
  ('dt-purchase-in',  'PURCHASE_IN',        'IN',  '采购入库',        'TX', 'MANUAL',   1, 0, 1, 'system'),
  ('dt-sales-out',    'SALES_OUT',          'OUT', '销售出库',        'TX', 'AUTO_FIFO',1, 0, 1, 'system'),
  ('dt-qret-out',     'QUALITY_RETURN_OUT', 'OUT', '质量退货出库',    'TX', 'MANUAL',   1, 0, 1, 'system'),
  ('dt-oret-out',     'OTHER_RETURN_OUT',   'OUT', '其他退货出库',    'TX', 'MANUAL',   1, 0, 1, 'system'),
  ('dt-sret-in',      'SALES_RETURN_IN',    'IN',  '销售退货入库',    'TX', 'MANUAL',   1, 0, 1, 'system'),
  ('dt-material-out', 'MATERIAL_OUT',       'OUT', '领料出库',        'TX', 'AUTO_FIFO',1, 0, 1, 'system'),
  ('dt-vmi-transfer', 'VMI_TRANSFER_IN',    'IN',  '物权转移入库',    'TX', 'MANUAL',   1, 0, 1, 'system'),
  -- 预注册：未建域类型只配置不出页面（proposal What Changes）
  ('dt-wip-in',       'WIP_IN',             'IN',  '完工入库',        'TX', 'MANUAL',   1, 0, 1, 'system'),
  ('dt-scrap-out',    'SCRAP_OUT',          'OUT', '报废出库',        'TX', 'MANUAL',   1, 1, 1, 'system'),
  ('dt-transfer-in',  'TRANSFER_IN',        'IN',  '调拨入库',        'TX', 'MANUAL',   1, 0, 1, 'system'),
  ('dt-transfer-out', 'TRANSFER_OUT',       'OUT', '调拨出库',        'TX', 'AUTO_FIFO',1, 0, 1, 'system'),
  ('dt-adjust-in',    'ADJUST_IN',          'IN',  '盘点调整入库',    'TX', 'MANUAL',   1, 0, 1, 'system'),
  ('dt-adjust-out',   'ADJUST_OUT',         'OUT', '盘点调整出库',    'TX', 'MANUAL',   1, 0, 1, 'system');

-- ---------- 5) 存量库菜单 PERM 补丁（沿 101/102/103 尾部范式） ----------
-- 099 已被 marker 跳过不会重跑：099 产物四行 PERM 已更新，存量库由此处幂等补齐。
UPDATE erp_admin_menu
   SET PERM = 'ROLE_WAREHOUSE,ROLE_ADMIN'
 WHERE ID IN ('M4-4-1', 'M4-4-2', 'M4-4-3', 'M4-4-4');
