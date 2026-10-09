package com.erp.service.portal;

import java.util.List;
import java.util.Map;

/**
 * 供应商门户账号（spec supplier-portal-account）：绑定 supplierId 不可变、
 * 同供应商可多账号、停用即失访；创建限 ADMIN（SecurityConfig POST /api/proc/**）。
 */
public interface PortalAccountService {

    /** 创建门户账号（校验供应商 QUALIFIED、用户名唯一，绑定 ROLE_SUPPLIER） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 停用（STATUS=0，立即失去门户访问） */
    Map<String, Object> disable(String id);

    /** 启用 */
    Map<String, Object> enable(String id);

    /** 账号列表（可按 supplierId 过滤，含供应商名称与绑定状态） */
    List<Map<String, Object>> list(String supplierId);
}
