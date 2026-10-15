-- 132-qms-copq-dimensions.sql
-- rework-copq-analysis：COPQ 工序/客户维度（design D3，FR-4.12-6-3 四维排名）
-- 幂等：information_schema 判存 + PREPARE（同 131）

-- ---------- 工序维度（可选录入，聚合排名用） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_copq_entry'
               AND COLUMN_NAME = 'PROCESS_CODE');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_copq_entry ADD COLUMN PROCESS_CODE VARCHAR(64) DEFAULT NULL COMMENT ''工序编码（可选维度）'' AFTER BATCH_NO',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 客户维度（可选录入，聚合排名用） ----------
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME = 'erp_qms_copq_entry'
               AND COLUMN_NAME = 'CUSTOMER_NAME');
SET @sql := IF(@col = 0,
    'ALTER TABLE erp_qms_copq_entry ADD COLUMN CUSTOMER_NAME VARCHAR(128) DEFAULT NULL COMMENT ''客户名称（可选维度）'' AFTER SUPPLIER_NAME',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
