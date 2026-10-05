package com.erp.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.servlet.http.HttpServletResponse;

/**
 * 权限规则集中定义（01 文档 2.4）：
 * /api/auth/**、doc.html 匿名；menus 写操作 hasRole("ADMIN")；法人主体写操作 hasRole("ADMIN")；其余 /api/** 需认证。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;

    public SecurityConfig(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf().disable()
            .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            .and()
            .authorizeRequests()
                .antMatchers("/api/auth/**").permitAll()
                .antMatchers("/doc.html", "/webjars/**", "/v3/api-docs/**", "/swagger-resources/**").permitAll()
                .antMatchers(HttpMethod.GET, "/api/system/menus/user").authenticated()
                .antMatchers(HttpMethod.POST, "/api/system/menus/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/system/menus/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/system/menus/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/legal-entities/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/legal-entities/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/cost-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/cost-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/profit-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/profit-centers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/org-units/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/org-units/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/items/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/items/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/items/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/item-categories/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/item-categories/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/customer-groups/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/customer-groups/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/customer-views/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/customer-views/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/credit-limits/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/credit-limits/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/price-agreements/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/price-agreements/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/suppliers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/suppliers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/suppliers/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/supplier-merges/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/supplier-merges/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/exchange-rates/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/exchange-rates/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/exchange-rates/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/exchange-rate-batch/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/tax-codes/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/tax-codes/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/tax-codes/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/tax-code-batch/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/mdm/tax-policies/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/mdm/tax-policies/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/mdm/tax-policies/**").hasRole("ADMIN")
                // 质量协同（add-quality-collaboration）：六角色写权限。必须先于下方 /api/proc/** 的 ADMIN 规则，
                // 否则 POST /api/proc/returns/** 会把仓库/质量角色先拦成 403。
                // 细粒度（如双签节点必须由 ROLE_REQUIRED 角色签）在 ApprovalServiceImpl 内二次校验。
                .antMatchers(HttpMethod.POST, "/api/qms/lots/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG")
                .antMatchers(HttpMethod.PUT, "/api/qms/lots/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG")
                .antMatchers(HttpMethod.POST, "/api/qms/standards/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.PUT, "/api/qms/standards/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.POST, "/api/qms/exempts/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.POST, "/api/qms/ncrs/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/ncrs/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                // 让步接收：申请/提交（ENG/MGR）+ 核销放行（质检员/仓库）；双签签署走 approvals 规则
                .antMatchers(HttpMethod.POST, "/api/qms/concessions/quality-gate").hasAnyRole("ADMIN",
                        "WAREHOUSE", "INSPECTOR", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/concessions/**").hasAnyRole("ADMIN", "QUALITY_ENG",
                        "QUALITY_MGR", "INSPECTOR", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/qms/capa/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/capa/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/copq/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/copq/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/scar/**").hasAnyRole("ADMIN", "SQE", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/qms/scar/**").hasAnyRole("ADMIN", "SQE", "QUALITY_MGR")
                .antMatchers(HttpMethod.POST, "/api/qms/gauges/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.PUT, "/api/qms/gauges/**").hasAnyRole("ADMIN", "QUALITY_ENG")
                .antMatchers(HttpMethod.POST, "/api/qms/spc/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG")
                // 审批底座：待办/签署入口按角色放行，节点角色不符由服务层 403
                .antMatchers(HttpMethod.POST, "/api/qms/approvals/**").hasAnyRole("ADMIN", "INSPECTOR", "QUALITY_ENG", "QUALITY_MGR",
                        "TECH_OWNER", "QUALITY_DIRECTOR", "SQE", "WAREHOUSE", "PM", "RECEIVER")
                // 2.6.1 质量退货：具体路径在前（出库过账 WAREHOUSE），兜底创建/编辑 ADMIN
                .antMatchers(HttpMethod.POST, "/api/proc/returns/*/postings").hasAnyRole("ADMIN", "WAREHOUSE")
                // 质量退货提交由质量侧发起（NCR 自动带出单）；创建/编辑兜底 ADMIN
                .antMatchers(HttpMethod.POST, "/api/proc/returns/**").hasAnyRole("ADMIN", "QUALITY_ENG", "QUALITY_MGR")
                .antMatchers(HttpMethod.PUT, "/api/proc/returns/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/proc/returns/**").hasRole("ADMIN")
                // 评标评分（add-tender-bidding 4.1）：放行评委，必须先于 /api/proc/** 的 ADMIN 规则，
                // 否则顺序在前的 POST /api/proc/** 会先把评委拦成 403
                .antMatchers(HttpMethod.POST, "/api/proc/tenders/*/scores").hasAnyRole("ADMIN", "BID_JUDGE")
                // 收货管理（add-goods-receipt D7）：同样必须先于 /api/proc/** 的 ADMIN 规则。
                // 顺序要点：具体路径在前——否则 POST /grs/** 的 RECEIVER 规则会吞掉过账/放行的 WAREHOUSE 门禁
                .antMatchers(HttpMethod.POST, "/api/proc/grs/*/postings").hasAnyRole("ADMIN", "WAREHOUSE")
                .antMatchers(HttpMethod.POST, "/api/proc/grs/**").hasAnyRole("ADMIN", "RECEIVER")
                .antMatchers(HttpMethod.POST, "/api/proc/gr-differences/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.POST, "/api/proc/gr-adjustments/**").hasAnyRole("ADMIN", "PM")
                .antMatchers(HttpMethod.POST, "/api/proc/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.PUT, "/api/proc/**").hasRole("ADMIN")
                .antMatchers(HttpMethod.DELETE, "/api/proc/**").hasRole("ADMIN")
                .antMatchers("/api/**").authenticated()
                .anyRequest().permitAll()
            .and()
            .exceptionHandling().authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"未登录或登录已过期\"}");
            })
            .and()
            .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
