-- 013 成本中心加利润中心归属列（依赖 012 的种子 PC-0001，按编号顺序执行）
-- 注：MySQL 8 不支持 ADD COLUMN IF NOT EXISTS（MariaDB 语法）；marker 保证本脚本仅执行一次，
-- 若 marker 表丢失导致重跑，ADD COLUMN 报 duplicate column 会被 DbBootstrap 记 warn 跳过，不影响后续回填。

ALTER TABLE erp_mdm_cost_center
    ADD COLUMN PROFIT_CENTER_ID VARCHAR(64) AFTER LEGAL_ENTITY_ID;

-- 存量种子回填：CC-PROD-01 → PC-0001（同属 le-0001）
UPDATE erp_mdm_cost_center
SET PROFIT_CENTER_ID = 'pc-0001'
WHERE CC_CODE = 'CC-PROD-01'
  AND (PROFIT_CENTER_ID IS NULL OR PROFIT_CENTER_ID = '');
