package com.erp.service.system;

import java.util.Map;
import java.util.Set;

/**
 * 敏感权限清单（add-permission-approval-flow design D2/D9，SOP-5.6-B step3 风险三级之 SENSITIVE）：
 * 目标角色命中本清单 → 申请风险级 = 敏感 → 审批链追加数据 Owner 节点（ownerRole）。
 *
 * <p>初版按规格样例三类定稿（成本价 / 薪酬 / 客户主数据）：
 * <ul>
 *   <li>成本价 → ROLE_COST_ACCOUNTANT（Owner：财务经理）</li>
 *   <li>客户主数据 → ROLE_SALES / ROLE_CS / ROLE_CREDIT_ADMIN（Owner：销售总监 / 财务经理）</li>
 *   <li>薪酬 → 持有该数据的角色（ROLE_HR / ROLE_HR_MGR / ROLE_FINANCE）均已在
 *       {@link PrivilegedRoles} 特权清单（风险取最高 → 走特权会签链），不重复入本清单</li>
 * </ul>
 *
 * <p>守卫：SensitiveRolesScanTest 断言成员与 Owner 均存在于角色表，且成员与特权清单不相交
 * （重叠会让"取最高风险"口径含糊）。运营口径调整 = 改本常量发版（proposal 偏差表 5）。
 */
public final class SensitiveRoles {

    private SensitiveRoles() {
    }

    /** roleCode → 数据 Owner 角色（审批链 SEQ2 节点） */
    public static final Map<String, String> OWNER_BY_ROLE = Map.of(
            "ROLE_COST_ACCOUNTANT", "ROLE_FINANCE_MGR", // 成本价
            "ROLE_CREDIT_ADMIN", "ROLE_FINANCE_MGR",    // 客户信用/额度
            "ROLE_SALES", "ROLE_SALES_DIRECTOR",        // 客户主数据（维护）
            "ROLE_CS", "ROLE_SALES_DIRECTOR"            // 客户数据（客服）
    );

    public static boolean contains(String roleCode) {
        return roleCode != null && OWNER_BY_ROLE.containsKey(roleCode);
    }

    /** 数据 Owner 角色；非敏感角色返回 null */
    public static String ownerOf(String roleCode) {
        return roleCode == null ? null : OWNER_BY_ROLE.get(roleCode);
    }

    /** 入参角色集中的敏感角色（保持顺序），用于风险级判定与 Owner 节点解析 */
    public static java.util.List<String> hitting(java.util.Collection<String> roleCodes) {
        if (roleCodes == null) {
            return java.util.List.of();
        }
        return roleCodes.stream().filter(SensitiveRoles::contains).toList();
    }

    public static Set<String> allMembers() {
        return OWNER_BY_ROLE.keySet();
    }
}
