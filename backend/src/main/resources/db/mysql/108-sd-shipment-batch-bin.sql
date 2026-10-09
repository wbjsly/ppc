-- 108 销售发货行加 BATCH_NO（4.6.3 拣货推荐回写批次；BIN_CODE 已在 107 加）
--     （change add-outbound-strategy，spec outbound-strategy / sales-shipment 行值优先过账）
-- 幂等：information_schema + PREPARE（105/106/107 范式）

SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_sd_shipment_line'
        AND COLUMN_NAME = 'BATCH_NO') = 0,
    'ALTER TABLE erp_sd_shipment_line
       ADD COLUMN BATCH_NO VARCHAR(64) DEFAULT NULL
         COMMENT ''拣货推荐回写批次（空=过账时按 FIFO 选批重走 ATP）'' AFTER WAREHOUSE_CODE',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
