package com.erp.security;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 审计上下文过滤器（add-audit-log-console D3）：IP = X-Forwarded-For 首值或 remoteAddr；
 * 终端 = User-Agent 前 100 字符。非静态资源；finally 清理。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuditContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            String ip = request.getHeader("X-Forwarded-For");
            if (ip != null && !ip.isBlank()) {
                int comma = ip.indexOf(',');
                ip = comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
            } else {
                ip = request.getRemoteAddr();
            }
            String ua = request.getHeader("User-Agent");
            String terminal = ua == null ? null : (ua.length() > 100 ? ua.substring(0, 100) : ua);
            AuditContext.set(ip, terminal);
            chain.doFilter(request, response);
        } finally {
            AuditContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.startsWith("/static/") || uri.endsWith(".js")
                || uri.endsWith(".css") || uri.endsWith(".ico"));
    }
}
