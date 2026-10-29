package com.erp.service.system;

import java.util.Set;

/**
 * 特权角色清单（add-role-permission-console design D3，C-5.6-06 / SOP-5.6-A L1）：
 * 岗位基线新值含任一特权角色 → 硬阻断 422（无例外）；迁移 186 存量剥离同源。
 *
 * 构成 = ApprovalNodeSpec.sign() 全部审批节点角色（24 枚）∪ 金额角色：
 *   金额角色 FINANCE / FINANCE_MGR / FIN_MGR / FIN_ASSET / GM 已全部包含于
 *   sign 清单，无额外项（ROLE_FIN 不存在于角色表）。
 *
 * 守护：PrivilegedRolesScanTest 扫描 src/main/java 提取全部
 * ApprovalNodeSpec.sign("ROLE_*") 字面量，断言 ⊆ 本清单——
 * 新增审批角色忘进本清单 → 测试红。
 */
public final class PrivilegedRoles {

    private PrivilegedRoles() {
    }

    public static final Set<String> ALL = Set.of(
            // --- 审批节点角色（ApprovalNodeSpec.sign 扫描同源，勿手工增删，先过扫描测试）---
            "ROLE_ADMIN",
            "ROLE_ASSET_ADMIN",
            "ROLE_CCB_ARCH",
            "ROLE_CCB_BIZ",
            "ROLE_CCB_OPS",
            "ROLE_COMPLIANCE",
            "ROLE_DEPT_HEAD",
            "ROLE_DUTY_MGR",
            "ROLE_EQUIP_MGR",
            "ROLE_EXEC",
            "ROLE_FINANCE",
            "ROLE_FINANCE_MGR",
            "ROLE_FIN_ASSET",
            "ROLE_FIN_MGR",
            "ROLE_GM",
            "ROLE_HR",
            "ROLE_HR_MGR",
            "ROLE_PLAN_MGR",
            "ROLE_PM",
            "ROLE_PMGR",
            "ROLE_PMO",
            "ROLE_PROCESS_MGR",
            "ROLE_PURCHASER",
            "ROLE_QUALITY_MGR",
            "ROLE_SALES_DIRECTOR",
            "ROLE_SALES_MGR",
            "ROLE_TECH_OWNER",
            "ROLE_WAREHOUSE"
    );

    public static boolean contains(String roleCode) {
        return roleCode != null && ALL.contains(roleCode);
    }

    /** 返回入参中属于特权清单的角色（保持入参顺序），用于 422 违规明细 */
    public static java.util.List<String> violating(java.util.Collection<String> roleCodes) {
        if (roleCodes == null) {
            return java.util.List.of();
        }
        return roleCodes.stream().filter(PrivilegedRoles::contains).toList();
    }
}
