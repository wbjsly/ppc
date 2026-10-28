-- ============================================================
-- 迁移 182：通道-供应商结构映射（add-service-monitoring-console design D5）
-- 背景：门户 requireSupplierId() = erp_mdm_supplier.ID，而章节/指标 PARTNER_CODE
--       来自通道伙伴码（12.1.3 手工录入自由文本）——两者无结构关联，门户
--       「服务等级」章节级隔离无法解析"该供应商对应哪些章节"。
-- 方案 B（用户拍板）：erp_intf_channel 加 SUPPLIER_ID 列，12.1.3 创建通道时
--       选择供应商；门户按关联通道的 PARTNER_CODE 集合过滤章节。
-- 幂等：information_schema 判断列存在再 PREPARE（沿 178/179 惯例）。
-- ============================================================

SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_intf_channel'
                   AND COLUMN_NAME = 'SUPPLIER_ID');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_intf_channel ADD COLUMN SUPPLIER_ID VARCHAR(64) DEFAULT NULL COMMENT ''关联供应商（erp_mdm_supplier.ID，门户服务等级章节隔离映射）'' AFTER PARTNER_NAME',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
