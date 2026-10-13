-- ============================================================
-- 124-mrp-rework.sql  5.8 返工返修（change add-rework-management）
-- 内容：
--   1) erp_mrp_mo 加 MO_TYPE / SRC_TYPE / SRC_NO / SRC_QTY（载体与判定单锚点，design D1/D3）
--   2) erp_mrp_mo_bom 加 LINE_TYPE（INPUT 不合格品投入 / ADDL 追加料——齐套与超范围分支依据）
--   3) erp_mrp_routing 加 ROUTE_TYPE（NORMAL/REWORK）
--   4) erp_inv_stock 加 WIP_QTY（在制池：QTY = QC_QTY + AVAILABLE_QTY + WIP_QTY）
--   5) erp_inv_freeze 加 REF_MO_ID（钉住 = 活动行 + REF_MO_ID 非空，不解除冻结标记）
--   6) sys_param 3 参（费率/重置成本兜底，偏差 D3）
--   7) M5-8 菜单 PERM 幂等 UPDATE（新装库由 099 生成；存量库靠本节收敛）
-- 幂等：条件 ALTER（information_schema + PREPARE）/ INSERT IGNORE / UPDATE 幂等；可连续多遍执行
-- ============================================================

-- ---------- 1) 工单头：类型维度与判定单锚点 ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mrp_mo'
        AND COLUMN_NAME = 'MO_TYPE') = 0,
    'ALTER TABLE erp_mrp_mo ADD COLUMN MO_TYPE VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' COMMENT ''工单类型 NORMAL/RW（BR-4.5-31 返工 RW- 前缀分池）'' AFTER MO_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mrp_mo'
        AND COLUMN_NAME = 'SRC_TYPE') = 0,
    'ALTER TABLE erp_mrp_mo ADD COLUMN SRC_TYPE VARCHAR(16) DEFAULT NULL COMMENT ''判定来源 NCR/RETURN（仅 RW）'' AFTER MO_TYPE',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mrp_mo'
        AND COLUMN_NAME = 'SRC_NO') = 0,
    'ALTER TABLE erp_mrp_mo ADD COLUMN SRC_NO VARCHAR(64) DEFAULT NULL COMMENT ''判定单号（NCR/退货判定；REWORK_ROUND=同 SRC_NO 已建数+1 派生）'' AFTER SRC_TYPE',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mrp_mo'
        AND COLUMN_NAME = 'SRC_QTY') = 0,
    'ALTER TABLE erp_mrp_mo ADD COLUMN SRC_QTY DECIMAL(18,4) DEFAULT NULL COMMENT ''判定批准数量（C-4.5-13 投入上限锚点）'' AFTER SRC_NO',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 2) 工单 BOM 快照行类型 ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mrp_mo_bom'
        AND COLUMN_NAME = 'LINE_TYPE') = 0,
    'ALTER TABLE erp_mrp_mo_bom ADD COLUMN LINE_TYPE VARCHAR(16) NOT NULL DEFAULT ''ADDL'' COMMENT ''行类型 INPUT=返工不合格品投入（WIP 池判满足）/ADDL=追加料（可用库存）'' AFTER ITEM_NAME',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 3) 路线头类型 ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mrp_routing'
        AND COLUMN_NAME = 'ROUTE_TYPE') = 0,
    'ALTER TABLE erp_mrp_routing ADD COLUMN ROUTE_TYPE VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' COMMENT ''路线类型 NORMAL/REWORK（FR-4.5-7-3 返工路线，唯一在途按类型分池）'' AFTER ITEM_CODE',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 4) 库存在制池 ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_stock'
        AND COLUMN_NAME = 'WIP_QTY') = 0,
    'ALTER TABLE erp_inv_stock ADD COLUMN WIP_QTY DECIMAL(18,4) NOT NULL DEFAULT 0 COMMENT ''在制池（返工转在制：入=转在制过账，出=入库/报废闭环；QTY = QC_QTY + AVAILABLE_QTY + WIP_QTY）'' AFTER AVAILABLE_QTY',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 5) 冻结行钉住关联 ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_inv_freeze'
        AND COLUMN_NAME = 'REF_MO_ID') = 0,
    'ALTER TABLE erp_inv_freeze ADD COLUMN REF_MO_ID VARCHAR(64) DEFAULT NULL COMMENT ''钉住返工工单（124：转在制不解除冻结标记，入库回调置 NULL 并 RELEASE）'' AFTER NCR_ID',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------- 6) sys_param（费率/重置成本兜底，偏差 D3；参数不在线编辑） ----------
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-rework-labor-rate', 'REWORK_LABOR_RATE', '60', 'NUMBER', 'MRP',
 '返工人工费率兜底（元/工时，FR-4.5-7-7 归集）：工作中心费率字段优先，缺失时取本参数', 'seed', '0', 0);
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-rework-machine-rate', 'REWORK_MACHINE_RATE', '100', 'NUMBER', 'MRP',
 '返工设备费率兜底（元/小时，按设备运行时间归集）', 'seed', '0', 0);
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-rework-reset-cost-basis', 'REWORK_RESET_COST_BASIS', 'STANDARD', 'ENUM', 'MRP',
 '重置成本基准：STANDARD=物料标准成本列；PARAM=按 REWORK_RESET_COST 参数值（成本列缺失时用），超基准提示质量主管评估经济性', 'seed', '0', 0);

-- ---------- 7) M5-8 菜单 PERM 存量回填（与 099 生成结果一致） ----------
UPDATE erp_admin_menu
   SET PERM = 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN,ROLE_QUALITY_ENG,ROLE_QUALITY_MGR'
 WHERE ID = 'M5-8';
UPDATE erp_admin_menu
   SET PERM = 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_ADMIN'
 WHERE ID = 'M5-8-1';
UPDATE erp_admin_menu
   SET PERM = 'ROLE_QUALITY_ENG,ROLE_QUALITY_MGR,ROLE_ADMIN,ROLE_PLANNER'
 WHERE ID = 'M5-8-2';
