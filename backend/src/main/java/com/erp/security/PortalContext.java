package com.erp.security;

import com.erp.common.ServiceException;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.system.SysUser;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 门户上下文（spec supplier-portal-account，C-4.9-06 行级隔离，design D2 第二层）。
 * 所有 /api/portal/** 服务 MUST 经 requireSupplierId() 取绑定供应商，
 * 忽略调用方入参指定的他人 supplierId（越权默认拒绝）。
 */
@Component
public class PortalContext {

    private final SysUserDao userDao;

    public PortalContext(SysUserDao userDao) {
        this.userDao = userDao;
    }

    /** 当前是否门户账号（ROLE_SUPPLIER） */
    public boolean isPortal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities() != null
                && auth.getAuthorities().stream().anyMatch(a -> "ROLE_SUPPLIER".equals(a.getAuthority()));
    }

    public String currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    public SysUser currentUser() {
        String uid = currentUserId();
        return uid == null ? null : userDao.selectById(uid);
    }

    /**
     * 门户账号取绑定 supplierId；非门户或未绑定 → 403（越权默认拒绝）。
     */
    public String requireSupplierId() {
        if (!isPortal()) {
            throw new ServiceException(403, "需供应商门户账号（C-4.9-06）");
        }
        SysUser u = currentUser();
        if (u == null || !StringUtils.hasText(u.getSupplierId())) {
            throw new ServiceException(403, "门户账号未绑定供应商，禁止访问");
        }
        if (!"1".equals(u.getStatus())) {
            throw new ServiceException(403, "门户账号已停用");
        }
        return u.getSupplierId().trim();
    }
}
