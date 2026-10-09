package com.erp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应商门户越权拦截（spec supplier-portal-account，C-4.9-06，design D2）。
 * 携带 ROLE_SUPPLIER 的请求只允许进入 /api/portal/** 与 /api/auth/**（登录态维护），
 * 访问任何内部端点 → 403 + 安全审计日志（一处覆盖全部内部端点，含未来新增）。
 * 行级过滤由各 portal service 按绑定 supplierId 强制执行（双层隔离的第二层）。
 */
@Slf4j
@Component
public class PortalGuardInterceptor implements HandlerInterceptor {

    /** 门户白名单前缀：门户 API 与认证自身（登录/登出/取用户信息） */
    private static final String[] ALLOW_PREFIXES = {"/api/portal/", "/api/auth/"};

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return true;
        }
        boolean portal = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_SUPPLIER".equals(a.getAuthority()));
        if (!portal) {
            return true;
        }
        String path = request.getRequestURI();
        for (String prefix : ALLOW_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        // 安全审计日志（C-4.9-06：越权尝试记录并告警语义）
        log.warn("[SECURITY-AUDIT] 门户账号越权访问拦截 userId={} path={} ip={}",
                auth.getName(), path, request.getRemoteAddr());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("message", "门户账号无权访问该资源（C-4.9-06）");
        body.put("data", null);
        body.put("timestamp", System.currentTimeMillis());
        response.getWriter().write(objectMapper.writeValueAsString(body));
        return false;
    }
}
