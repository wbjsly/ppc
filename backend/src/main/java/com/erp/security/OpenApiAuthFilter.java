package com.erp.security;

import com.erp.entity.intf.IntfCredential;
import com.erp.service.intf.GatewayDecision;
import com.erp.service.intf.OpenApiGatewayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 开放调用入口网关（design D1）：只拦 `/api/open/**`，且**全量短路**——
 * 校验失败直接写 7.1.4 统一错误体并返回，不再执行 filterChain，
 * 因此 SecurityConfig 侧 `/api/open/**` 声明 authenticated() 也不会出现"未认证放行"。
 *
 * 顺序见 {@link OpenApiGatewayService#evaluate}：审计预写入(fail-closed) → 时间戳 → 凭证 → 签名 → 限流 → 熔断。
 */
@Slf4j
public class OpenApiAuthFilter extends OncePerRequestFilter {

    private static final String PREFIX = "/api/open/";

    private final OpenApiGatewayService gateway;

    public OpenApiAuthFilter(OpenApiGatewayService gateway) {
        this.gateway = gateway;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().contains(PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        byte[] body = readBody(request);
        CachedBodyRequest wrapped = new CachedBodyRequest(request, body);
        String path = request.getRequestURI();

        GatewayDecision decision = gateway.evaluate(wrapped, path, body);
        if (decision.getRequestId() != null) {
            response.setHeader("X-Request-Id", decision.getRequestId());
        }

        if (!decision.isOk()) {
            if (decision.getRetryAfter() != null) {
                response.setHeader("Retry-After", String.valueOf(decision.getRetryAfter()));
            }
            writeError(response, decision.getHttpStatus(), decision.getCode(), decision.getMessage(),
                    decision.getViolatedRule(), decision.getRequestId());
            gateway.complete(decision, decision.getHttpStatus(), decision.getCode(), 0L);
            return;
        }

        request.setAttribute("INTF_CREDENTIAL", decision.getCredential());
        applyAuthentication(decision);
        long start = System.currentTimeMillis();
        try {
            chain.doFilter(wrapped, response);
        } finally {
            gateway.complete(decision, response.getStatus(), null, System.currentTimeMillis() - start);
        }
    }

    /** Scope 注入 authorities（BR-4.9-20 的 Scope 校验依据），主体 = 伙伴编码 */
    private void applyAuthentication(GatewayDecision decision) {
        IntfCredential cred = decision.getCredential();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_OPEN_API"));
        if (cred != null && cred.getScope() != null && !cred.getScope().trim().isEmpty()) {
            for (String s : cred.getScope().split(",")) {
                if (!s.trim().isEmpty()) {
                    authorities.add(new SimpleGrantedAuthority("SCOPE_" + s.trim().toUpperCase()));
                }
            }
        }
        String principal = cred == null ? decision.getCaller() : cred.getPartnerCode();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, authorities));
    }

    /** 7.1.4 统一错误响应 */
    private void writeError(HttpServletResponse response, int status, String code, String message,
                            String violatedRule, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"error\":{\"code\":\"").append(escape(code)).append("\",")
                .append("\"message\":\"").append(escape(message)).append("\",");
        if (violatedRule != null && !violatedRule.isEmpty()) {
            sb.append("\"violated_rule\":\"").append(escape(violatedRule)).append("\",");
        }
        sb.append("\"request_id\":\"").append(escape(requestId)).append("\",\"details\":[]}}");
        response.getWriter().write(sb.toString());
    }

    private static String escape(String v) {
        if (v == null) {
            return "";
        }
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private static byte[] readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLength() == 0) {
            return new byte[0];
        }
        return request.getInputStream().readAllBytes();
    }

    /** 请求体回放包装：签名需要原始字节，业务仍需正常读取 body（design D1） */
    static class CachedBodyRequest extends HttpServletRequestWrapper {

        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body == null ? new byte[0] : body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return in.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
