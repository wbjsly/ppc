package com.erp.plan;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

/**
 * 需求计划测试登录态辅助（与 BomTestAuth/RoutingTestAuth 同构）：
 * 进程内直挂 SecurityContext，用后必须 logout 清理。
 */
final class PlanTestAuth {

    private PlanTestAuth() {
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
