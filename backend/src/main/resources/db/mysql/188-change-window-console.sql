-- ============================================================
-- 188-change-window-console.sql
-- change: add-change-window-console（16.4 变更窗口，design D3/D4）
--   ① erp_admin_perm_request 幂等加 OUT_WINDOW 列（窗口外 CCB 升级标记）
--   ② CCB 三方会签角色种子（C-5.6-03：运维负责人/架构委员会代表/业务 Owner）
--      并授予管理员账号（种子期兜底，避免窗口外单无人可签，design D4）
--   菜单 16.4.1~3 path 已为 /m/16.4.x（099 种子原生），前端按 /m/16.4.x
--   注册真实路由（沿 16.1~16.3 惯例），无需菜单 UPDATE。
-- ============================================================
-- 幂等：information_schema + PREPARE 受控 ALTER + INSERT IGNORE，可重跑
-- ============================================================

-- 1. OUT_WINDOW 列（design D3：构链时判定窗口外即置 '1'）
SET @has_col := (SELECT COUNT(*) FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE()
                   AND TABLE_NAME = 'erp_admin_perm_request'
                   AND COLUMN_NAME = 'OUT_WINDOW');
SET @ddl := IF(@has_col = 0,
    'ALTER TABLE erp_admin_perm_request ADD COLUMN OUT_WINDOW CHAR(1) NOT NULL DEFAULT ''0'' COMMENT ''窗口外变更标记（1=提交时窗口外，CCB 升级）'' AFTER APPR_INSTANCE_ID',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. CCB 三方会签角色（design D4：固定三角色，人员指派走角色管理）
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('role-ccb-ops',  'ROLE_CCB_OPS',  'CCB 运维负责人',     33, '1', 'migrate-188', NOW(), '0', 0),
       ('role-ccb-arch', 'ROLE_CCB_ARCH', 'CCB 架构委员会代表', 34, '1', 'migrate-188', NOW(), '0', 0),
       ('role-ccb-biz',  'ROLE_CCB_BIZ',  'CCB 业务 Owner',     35, '1', 'migrate-188', NOW(), '0', 0);

-- 3. 授予管理员账号（冒烟/测试窗口外场景的签署兜底；生产由角色管理指派）
INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID)
VALUES ('ur-admin-ccb-ops',  'user-admin', 'role-ccb-ops'),
       ('ur-admin-ccb-arch', 'user-admin', 'role-ccb-arch'),
       ('ur-admin-ccb-biz',  'user-admin', 'role-ccb-biz');
