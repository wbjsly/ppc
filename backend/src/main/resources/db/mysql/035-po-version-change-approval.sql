-- 035 PO 版本变更审批列（change add-framework-agreement-order，design D8 / task 8.3）
-- FR-4.2-9-2 / BR-4.2-42 / C-4.2-07：变更按类型分级——
--   金额调减：直接留痕（BR-4.2-04，APPROVED 免批）；
--   金额增加 ≤ PRICE_TOLERANCE：REQ_ROLE = 采购经理；
--   金额增加 > PRICE_TOLERANCE：REQ_ROLE = 采购总监（升级），并重跑价控与预算。
-- 幂等：information_schema + PREPARE（033/034 范式）。

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_proc_po_version'
        AND COLUMN_NAME = 'REQ_ROLE') = 0,
    'ALTER TABLE erp_proc_po_version
       ADD COLUMN REQ_ROLE VARCHAR(32) NULL COMMENT ''变更审批要求角色：PURCHASE_MANAGER/PURCHASE_DIRECTOR'',
       ADD COLUMN REQ_REASON VARCHAR(500) NULL COMMENT ''升级原因（超容差价差说明，C-4.2-07）'',
       ADD COLUMN REQ_APPROVED_BY VARCHAR(64) NULL,
       ADD COLUMN REQ_APPROVED_DATE DATETIME NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
