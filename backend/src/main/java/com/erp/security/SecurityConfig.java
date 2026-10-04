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
                // 评标评分（add-tender-bidding 4.1）：放行评委，必须先于 /api/proc/** 的 ADMIN 规则，
                // 否则顺序在前的 POST /api/proc/** 会先把评委拦成 403
                .antMatchers(HttpMethod.POST, "/api/proc/tenders/*/scores").hasAnyRole("ADMIN", "BID_JUDGE")
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
