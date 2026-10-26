package com.erp.mo;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.List;

/**
 * 工单管理测试登录态辅助（与 PlanTestAuth/BomTestAuth 同构）：
 * 进程内直挂 SecurityContext，用后必须 logout 清理。
 */
final class MoTestAuth {

    private MoTestAuth() {
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
