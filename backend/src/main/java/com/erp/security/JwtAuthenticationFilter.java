package com.erp.security;

import com.erp.dao.system.SysUserDao;
import com.erp.entity.system.SysUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器（add-account-lifecycle-console design D2）：
 * token 有效后 MUST 回查用户 STATUS——非 '1'（停用/归档）或用户不存在 → 401 终止，
 * 使停用/归档账号既有 token 立即失效（S-5.6-02 会话失效要求，无需服务端会话存储）。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider jwtTokenProvider;
    private final SysUserDao userDao;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider, SysUserDao userDao) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.userDao = userDao;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String token = extractToken(request);
        if (StringUtils.hasText(token) && jwtTokenProvider.validateToken(token)) {
            String userId = jwtTokenProvider.getSubjectFromToken(token);

            // 账号状态实时校验（design D2 修订）：JWT subject 即用户 ID（login 签发口径），
            // 查到且状态非 '1'（停用/归档）→ 401，token 即时断权；
            // 查不到（物理不存在，C-0-05 禁止物理删除故仅边缘场景/测试桩）维持 JWT 语义放行
            SysUser user = userDao.selectById(userId);
            if (user != null && !SysUser.ST_ACTIVE.equals(user.getStatus())) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"账号已停用或已归档\"}");
                return;
            }

            var claims = jwtTokenProvider.getClaims(token);
            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            Object roles = claims.get("roles");
            if (roles instanceof List) {
                for (Object role : (List<?>) roles) {
                    authorities.add(new SimpleGrantedAuthority(role.toString()));
                }
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
