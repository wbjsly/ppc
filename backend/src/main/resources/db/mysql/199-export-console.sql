-- 199-export-console.sql（add-export-console，spec bi-export-lifecycle / bi-export-overlimit-approval）
-- 幂等：可重复执行。
-- ① erp_bi_export_task 加列（优先级/用途/清理/提醒）；② 分级审批角色；③ 菜单 14.5 权限。

-- ---- ① erp_bi_export_task 加列 ----
SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_export_task' AND COLUMN_NAME = 'PRIORITY');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_export_task ADD COLUMN PRIORITY varchar(8) NULL COMMENT ''SMALL/NORMAL/LOW（按 estRows 分级）'' AFTER EST_ROWS', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_export_task' AND COLUMN_NAME = 'PURPOSE');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_export_task ADD COLUMN PURPOSE varchar(500) NULL COMMENT ''超量导出用途说明（≥10字）'' AFTER APPROVAL_STATUS', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_export_task' AND COLUMN_NAME = 'CLEAN_AT');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_export_task ADD COLUMN CLEAN_AT datetime NULL COMMENT ''生命周期清理时间'' AFTER EXPIRE_AT', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

SET @c := (SELECT COUNT(*) FROM information_schema.COLUMNS
           WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'erp_bi_export_task' AND COLUMN_NAME = 'REMIND_AT');
SET @sql := IF(@c = 0, 'ALTER TABLE erp_bi_export_task ADD COLUMN REMIND_AT datetime NULL COMMENT ''删除前24h提醒时间'' AFTER CLEAN_AT', 'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- ---- ② 分级审批角色（FR-4.10-4-2：≤5万行经理 / >5万行总监）----
INSERT IGNORE INTO erp_admin_role (ID, ROLE_CODE, ROLE_NAME, SORT_ORDER, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO)
VALUES ('role-dept-mgr-199', 'ROLE_DEPT_MANAGER', '部门经理（导出超量审批）', 60, '1', 'migration-199', NOW(), '0', 0),
       ('role-dept-dir-199', 'ROLE_DEPT_DIRECTOR', '部门总监（导出超量审批）', 59, '1', 'migration-199', NOW(), '0', 0);
INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID)
SELECT CONCAT('ur-199-mgr-', u.ID), u.ID, 'role-dept-mgr-199' FROM erp_admin_user u
WHERE u.USERNAME = 'admin' AND NOT EXISTS (SELECT 1 FROM erp_admin_user_role x WHERE x.USER_ID = u.ID AND x.ROLE_ID = 'role-dept-mgr-199');
INSERT IGNORE INTO erp_admin_user_role (ID, USER_ID, ROLE_ID)
SELECT CONCAT('ur-199-dir-', u.ID), u.ID, 'role-dept-dir-199' FROM erp_admin_user u
WHERE u.USERNAME = 'admin' AND NOT EXISTS (SELECT 1 FROM erp_admin_user_role x WHERE x.USER_ID = u.ID AND x.ROLE_ID = 'role-dept-dir-199');

-- ---- ③ 菜单 14.5.1~3 权限 ----
UPDATE erp_admin_menu SET PERM = 'ROLE_BI_DESIGNER,ROLE_ADMIN'
WHERE MENU_CODE IN ('14.5.1', '14.5.2', '14.5.3') AND (PERM IS NULL OR PERM = '' OR PERM <> 'ROLE_BI_DESIGNER,ROLE_ADMIN');
