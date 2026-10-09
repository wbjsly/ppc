-- 116 库存报表（4.14）：物料 ABC 分类列 + 三菜单 PERM 存量回填
--     （change add-inventory-reports，spec inventory-reports ADDED / item-master-creation MODIFIED；design D8/D9）
-- 幂等：information_schema 判存在加列 / UPDATE 幂等

-- ---------- 1) 物料 ABC 分类（design D8：可空 NULL=未分类，报表分组维度；盘点 ABC 排程共用底座） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_mdm_item'
        AND COLUMN_NAME = 'ABC_CLASS') = 0,
    'ALTER TABLE erp_mdm_item
       ADD COLUMN ABC_CLASS VARCHAR(8) DEFAULT NULL COMMENT ''ABC 分类（A/B/C，NULL=未分类；库存报表分组维度）'' AFTER CLEAN_LEVEL',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2) 4.14 三菜单 PERM 存量回填（099 产物同步三行，存量库由此幂等补齐） ----------
UPDATE erp_admin_menu
   SET PERM = 'ROLE_WAREHOUSE,ROLE_ADMIN'
 WHERE ID IN ('M4-14-1', 'M4-14-3');
UPDATE erp_admin_menu
   SET PERM = 'ROLE_WAREHOUSE,ROLE_ADMIN,ROLE_FINANCE,ROLE_FINANCE_MGR'
 WHERE ID = 'M4-14-2';
