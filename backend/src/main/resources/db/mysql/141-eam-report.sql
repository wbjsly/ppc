-- 141-eam-report.sql
-- add-asset-report：资产报表（design D1 极简：零新表零新列，仅参数种子 + 菜单权限回填）
-- 幂等：INSERT IGNORE + information_schema 判存/PREPARE + 幂等 UPDATE

-- ---------- 1. 参数种子（三叶 CSV 导出行数上限，design D6） ----------
INSERT IGNORE INTO erp_sys_param (ID, PARAM_KEY, PARAM_VALUE, VALUE_TYPE, PARAM_GROUP, REMARK, CREATE_BY)
VALUES ('param-eam-report-export-max', 'EAM_REPORT_EXPORT_MAX', '50000', 'NUMBER', 'EAM',
        '资产报表 CSV 导出行数上限，超出返回 422 提示缩小期间/筛选范围', 'seed');

-- ---------- 2. 菜单权限回填（7.8.1/7.8.2/7.8.3 行已由 099 种子预插，此处仅补权限；099 EXTRA_PERM 同步重生成） ----------
UPDATE erp_admin_menu SET PERM = 'ROLE_ASSET_ADMIN,ROLE_FIN_ASSET,ROLE_DEPT_HEAD,ROLE_FIN_MGR,ROLE_ADMIN'
WHERE ID IN ('M7-8', 'M7-8-1', 'M7-8-2', 'M7-8-3') AND (PERM IS NULL OR PERM = '');
