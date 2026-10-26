package com.erp.routing;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

/**
 * 工艺路线测试登录态辅助（与 com.erp.mrp.BomTestAuth 同构）：
 * 进程内直挂 SecurityContext，用后必须 logout 清理。
 */
final class RoutingTestAuth {

    private RoutingTestAuth() {
    }

    static void login(String user, String... roles) {
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    static void logout() {
        SecurityContextHolder.clearContext();
    }
}
