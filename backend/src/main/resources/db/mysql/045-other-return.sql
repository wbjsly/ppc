-- 045-other-return.sql
-- 其他退货（2.6.2）与红字凭证台账（2.6.3）
-- 1) erp_proc_return 增结构化退货原因类型列
-- 2) M2-6-2 / M2-6-3 菜单 PERM 回填
-- 幂等：information_schema + PREPARE（037/044 范式）；UPDATE 天然幂等

-- ---------- 1) RETURN_REASON_TYPE（D4：结构化原因枚举） ----------
SET @ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA = DATABASE()
        AND TABLE_NAME = 'erp_proc_return'
        AND COLUMN_NAME = 'RETURN_REASON_TYPE') = 0,
    'ALTER TABLE erp_proc_return
       ADD COLUMN RETURN_REASON_TYPE VARCHAR(16) NULL
         COMMENT ''退货原因类型：WRONG_ITEM 发错货 / OVER_SHIP 多发货 / QUALITY_FOUND 到货后发现质量问题 / OTHER 其他（NULL 兼容存量，读侧展示为其他）'' AFTER RETURN_REASON',
    'SELECT 1');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- ---------- 2) 菜单 PERM（M2-6-2 其他退货 / M2-6-3 红字凭证） ----------
UPDATE erp_admin_menu
   SET PERM = 'ROLE_PM,ROLE_WAREHOUSE'
 WHERE ID = 'M2-6-2' AND (PERM IS NULL OR PERM = '');

UPDATE erp_admin_menu
   SET PERM = 'ROLE_PM,ROLE_WAREHOUSE'
 WHERE ID = 'M2-6-3' AND (PERM IS NULL OR PERM = '');
