-- 109 出库策略菜单 PERM 存量回填（change add-outbound-strategy，spec outbound-strategy
--     出库策略页面与菜单；099 种子 INSERT IGNORE 对已执行库不更新 → 存量库须 UPDATE 回填，
--     与 generate-menu-seed.cjs 输出保持同口径，038 迁移范式）
-- 幂等：UPDATE 天然幂等

UPDATE erp_admin_menu
   SET PERM = 'ROLE_WAREHOUSE,ROLE_ADMIN'
 WHERE ID IN ('M4-6-1', 'M4-6-2', 'M4-6-3');
