-- ============================================================
-- 167 报价审批台（add-quote-approval-console）
--   菜单 PERM 幂等落库：099-menu-seed.sql 仅 INSERT IGNORE 且 DbBootstrap
--   按 marker 跳过已执行脚本，重生成不会更新既有行——以 UPDATE 只补空值。
--   ①M11-2 三行（本 change 目标）
--   ②顺手修复 M10-5/6/8/9/10 族历史失效（add-training-certification 等遗留）
-- ============================================================

-- ---------- 1. M11-2 报价管理 ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_SALES,ROLE_SALES_MGR,ROLE_ADMIN'
 WHERE ID IN ('M11-2', 'M11-2-1') AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_SALES_MGR,ROLE_SALES_DIRECTOR,ROLE_FINANCE_MGR,ROLE_ADMIN'
 WHERE ID = 'M11-2-2' AND (PERM IS NULL OR PERM = '');

-- ---------- 2. M10 族历史 PERM 修复（只补空值，绝不覆盖既有配置） ----------

UPDATE erp_admin_menu SET PERM = 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M10-5', 'M10-5-1', 'M10-5-2', 'M10-5-3', 'M10-5-4')
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M10-6', 'M10-6-1', 'M10-6-2', 'M10-6-3')
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_HR,ROLE_HR_MGR,ROLE_ADMIN'
 WHERE ID IN ('M10-8', 'M10-8-1', 'M10-8-2')
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M10-9', 'M10-9-1', 'M10-9-2', 'M10-9-3')
   AND (PERM IS NULL OR PERM = '');
UPDATE erp_admin_menu SET PERM = 'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN'
 WHERE ID IN ('M10-10', 'M10-10-1', 'M10-10-2', 'M10-10-3', 'M10-10-4')
   AND (PERM IS NULL OR PERM = '');

-- ---------- 3. M10-10 绩效管理菜单行补插 ----------
-- 099-menu-seed.sql 仅 INSERT IGNORE 且按 marker 跳过：M10-10 行是 add-payroll-management
-- 追加进 099 的，从未落库（侧边栏不可见）——以 INSERT IGNORE 幂等补齐。
INSERT IGNORE INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS)
VALUES ('M10-10', 'M10', '10.10', '绩效管理', '', '', '100',
        'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN', '1'),
       ('M10-10-1', 'M10-10', '10.10.1', '绩效方案', '/m/10.10.1', '', '10',
        'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN', '1'),
       ('M10-10-2', 'M10-10', '10.10.2', '评分录入', '/m/10.10.2', '', '20',
        'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN', '1'),
       ('M10-10-3', 'M10-10', '10.10.3', '评分校准', '/m/10.10.3', '', '30',
        'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN', '1'),
       ('M10-10-4', 'M10-10', '10.10.4', '绩效结果', '/m/10.10.4', '', '40',
        'ROLE_HR,ROLE_HR_MGR,ROLE_FINANCE,ROLE_FIN_MGR,ROLE_ADMIN', '1');
