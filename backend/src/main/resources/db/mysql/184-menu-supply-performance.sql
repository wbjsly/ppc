-- ============================================================
-- 184-menu-supply-performance.sql
-- change: add-supply-performance-menu（M12.9 供应绩效菜单，design D4）
--   仅菜单 PERM 幂等补空：M12-9/12.9.1/12.9.2 三行
--   PERM = ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN（决策丙并集）
--   099 重生成后同口径；此处只补 PERM 为空的存量行。无 DDL 无新角色。
-- ============================================================

UPDATE erp_admin_menu SET PERM = 'ROLE_SCMDIR,ROLE_PMGR,ROLE_PM,ROLE_FINANCE_MGR,ROLE_GM,ROLE_ADMIN'
 WHERE ID IN ('M12-9', 'M12-9-1', 'M12-9-2') AND (PERM IS NULL OR PERM = '');
