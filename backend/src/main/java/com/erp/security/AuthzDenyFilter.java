package com.erp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.erp.service.AuthzIncidentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 越权 403 汇聚与降权拦截过滤器（add-authz-detection-console design D3/D5）。
 *
 * <p>排序：须在 Spring Security 链（order -100）之前包裹响应，才能捕获安全链写出的 HTTP 403
 * （安全拒绝不会继续到链后的过滤器）。此时 SecurityContext 尚未填充，故直接解析 JWT 取 userId
 * （JwtAuthenticationFilter 在安全链内已验证过同一 token 的口径）。</p>
 *
 * <p>路 B：链执行后响应状态 == 403 → counter.hit(userId)（覆盖 Spring Security 拒绝与
 * PortalGuardInterceptor 拒绝；业务 403 的 HTTP 状态是 200，由 GlobalExceptionHandler 路 A 计数，
 * 两路不重复）。</p>
 *
 * <p>降权拦截（D5）：路径命中敏感清单（/api/system|mdm|fin|qms/**）→ 解析 token 查 ACTIVE
 * 事件行 → 有则直接 403"账号临时降权中"，正常业务（非敏感路径）零 DB 开销放行。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100) // < -100（安全链）：先于安全链包裹响应
@RequiredArgsConstructor
@Slf4j
public class AuthzDenyFilter extends OncePerRequestFilter {

    /** 敏感路径清单（design D5 首版常量；进单测断言四前缀） */
    public static final String[] SENSITIVE_PREFIXES = {
            "/api/system/", "/api/mdm/", "/api/fin/", "/api/qms/"
    };

    private final AuthzDenyCounter counter;
    private final AuthzIncidentService incidentService;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // ---- 降权拦截（敏感路径前置检查，非敏感路径零开销）----
        String path = request.getRequestURI();
        if (isSensitive(path)) {
            String userId = userIdFromHeader(request);
            if (userId != null && incidentService.hasActiveRestriction(userId)) {
                writeForbidden(response, "账号临时降权中（BR-5.6-11），敏感操作暂被禁止，请联系安全管理员");
                return;
            }
        }

        // ---- 路 B：捕获 HTTP 403 计数 ----
        HttpServletResponseWrapper wrapped = new HttpServletResponseWrapper(response) {
            private int status = SC_OK;

            @Override
            public void setStatus(int sc) {
                this.status = sc;
                super.setStatus(sc);
            }

            @Override
            public void sendError(int sc) throws IOException {
                this.status = sc;
                super.sendError(sc);
            }

            @Override
            public void sendError(int sc, String msg) throws IOException {
                this.status = sc;
                super.sendError(sc, msg);
            }

            @Override
            public int getStatus() {
                return status;
            }
        };
        try {
            chain.doFilter(request, wrapped);
        } finally {
            if (wrapped.getStatus() == 403) {
                counter.hit(userIdFromHeader(request));
            }
        }
    }

    private boolean isSensitive(String path) {
        for (String p : SENSITIVE_PREFIXES) {
            if (path.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /** 从 Authorization 头解析 userId（安全链前 SecurityContext 不可用）；无效 token 返回 null */
    private String userIdFromHeader(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        try {
            String token = header.substring(7);
            if (jwtTokenProvider.validateToken(token)) {
                return jwtTokenProvider.getSubjectFromToken(token);
            }
        } catch (Exception e) {
            // 无效 token 不计数
        }
        return null;
    }

    private void writeForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 403);
        body.put("message", message);
        body.put("data", null);
        body.put("timestamp", System.currentTimeMillis());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.startsWith("/static/") || uri.endsWith(".js")
                || uri.endsWith(".css") || uri.endsWith(".ico"));
    }
}
