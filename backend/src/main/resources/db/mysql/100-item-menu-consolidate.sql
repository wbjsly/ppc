-- 100 菜单整合：物料管理四菜单合一（consolidate-item-menus，见 design D5）
-- 099 已执行（INSERT IGNORE 不更新存量行），存量库须先清后插，终态与 099 产物逐字段一致
-- 幂等：重复执行 DELETE + INSERT 得到相同结果

DELETE FROM erp_admin_menu WHERE ID IN ('M1-2-1', 'M1-2-2', 'M1-2-3', 'M1-2-4', 'M1-2-5');

INSERT INTO erp_admin_menu (ID, PARENT_ID, MENU_CODE, TITLE, PATH, ICON, SORT_ORDER, PERM, STATUS) VALUES
  ('M1-2-1', 'M1-2', '1.2.1', '物料管理', '/m/1.2.1', '', '10', '', '1'),
  ('M1-2-2', 'M1-2', '1.2.2', '分类维护', '/m/1.2.2', '', '20', '', '1');
