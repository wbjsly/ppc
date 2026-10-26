-- 121 生产管理-齐套检查（5.5，spec 4.5-04）：5.5 菜单 PERM 存量回填 + 齐套两参数
--     （change add-kitting-check，spec kitting-check ADDED 九需求 / notification-center ADDED 三需求；
--       design D7 本迁移：菜单权限双写 + sys_param，无新表——
--       erp_sys_notice(052) / erp_mrp_mo_shortage(120) / erp_mrp_suggestion(119) 均现成）
-- 幂等：UPDATE / INSERT IGNORE（可重复执行）

-- ---------- 1) 5.5 齐套检查菜单 PERM 存量回填（design D7：新装库由 099 生成，
--     已执行过 099 的库靠本 UPDATE 收敛；父 M5-5 与两子同权限防孤儿；
--     四角色 = 计划两方 + 仓库，偏差 D1 无采购员/车间主管） ----------
UPDATE erp_admin_menu
   SET PERM = 'ROLE_PLANNER,ROLE_PLAN_MGR,ROLE_WAREHOUSE,ROLE_ADMIN'
 WHERE ID IN ('M5-5', 'M5-5-1', 'M5-5-2');

-- ---------- 2) 齐套调度间隔（分钟，design D3：KittingSweepScheduler 默认 5 分钟） ----------
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-kitting-sweep-interval-min', 'KITTING_SWEEP_INTERVAL_MIN', '5', 'COUNT', 'MRP',
 '齐套自动重检调度间隔（分钟）：扫描未关闭工单双向重算齐套（到货恢复/领料新缺），0=停用（design D3）', 'seed', '0', 0);

-- ---------- 3) 齐套在途日期过滤开关（design D1 护栏 + 任务 1.3 数据摸底） ----------
-- 摸底实测（2026-10-10，dev 库）：APPROVED PO 行 245 条，req_date 空 198 条 = 80.8% > 30% 护栏
-- → 默认 OFF（空值不过滤、恢复旧行为全量计入，避免齐套率暴跌误报风暴；proposal 偏差 D5 已补记）；
--   采购域补录 req_date 后可置 ON（仅计 reqDate ≤ planStartDate 的在途，空值不计入保守报缺）
INSERT IGNORE INTO erp_sys_param
(ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY, DEL_FLAG, VER_NO) VALUES
('param-kitting-transit-date-filter', 'KITTING_TRANSIT_DATE_FILTER', 'OFF', 'ENUM', 'MRP',
 '齐套在途到货日期过滤：ON=仅计 reqDate≤计划开工日的在途（空值不计入，保守报缺）；OFF=全部在途计入（旧行为）。空值率>30% 时保持 OFF（偏差 D5）', 'seed', '0', 0);
