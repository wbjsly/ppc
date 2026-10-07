package com.erp.security;

import com.erp.common.ServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 接口对接高危动作的角色闸门（spec interface-console，design D5）：
 * 凭证紧急吊销 / 生产放行会签 / SLA 报告归档发布 / 限流降档恢复 四类动作服务端二次校验 ADMIN，
 * ROLE_INTF_OPS 调用一律 403（C-0-03 发起人 ≠ 复核人的前置条件）。
 */
public final class IntfGuard {

    private IntfGuard() {
    }

    public static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return false;
        }
        return auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    public static void requireAdmin(String action) {
        if (!isAdmin()) {
            throw new ServiceException(403, "「" + action + "」仅限管理员执行（接口运维角色无权）");
        }
    }

    public static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }
}
