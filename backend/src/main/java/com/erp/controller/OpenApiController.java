package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.intf.IntfCredential;
import com.erp.security.JwtTokenProvider;
import com.erp.util.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放调用入口（spec open-api-gateway 2.2 / FR-4.9-3-1）。
 * 本控制器所有端点都位于 /api/open/**，由 OpenApiAuthFilter 先行校验（签名/时间戳/限流/熔断/审计）。
 */
@Tag(name = "开放接口入口")
@RestController
@RequestMapping("/api/open")
public class OpenApiController {

    /** 访问令牌 2 小时（FR-4.9-3-1） */
    private static final long TOKEN_TTL_MS = 2L * 60 * 60 * 1000;

    private final JwtTokenProvider jwtTokenProvider;

    public OpenApiController(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Operation(summary = "API Key + Secret 换取访问令牌（2 小时 + Scope）")
    @PostMapping("/token")
    public R<Map<String, Object>> token(HttpServletRequest request) {
        // filter 已校验 Basic 口令并把凭证挂在请求属性上（design D1）
        Object attr = request.getAttribute("INTF_CREDENTIAL");
        IntfCredential cred = attr instanceof IntfCredential ? (IntfCredential) attr : null;
        String partnerCode = cred == null ? SecurityUtils.getCurrentUserId() : cred.getPartnerCode();

        List<String> scopes = new ArrayList<>();
        if (cred != null && cred.getScope() != null && !cred.getScope().trim().isEmpty()) {
            for (String s : cred.getScope().split(",")) {
                if (!s.trim().isEmpty()) {
                    scopes.add(s.trim().toUpperCase());
                }
            }
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put("scope", scopes);
        claims.put("partner", partnerCode);
        claims.put("env", cred == null ? null : cred.getEnv());
        String subject = partnerCode == null ? "unknown" : partnerCode;
        String token = jwtTokenProvider.generateToken(subject, claims, TOKEN_TTL_MS);

        Map<String, Object> data = new HashMap<>();
        data.put("access_token", token);
        data.put("token_type", "Bearer");
        data.put("expires_in", TOKEN_TTL_MS / 1000);
        data.put("scope", scopes);
        data.put("expire_at", LocalDateTime.now().plusSeconds(TOKEN_TTL_MS / 1000));
        return R.ok(data);
    }

    @Operation(summary = "连通性探测（限流/熔断/审计用例入口）")
    @RequestMapping(value = "/ping", method = {RequestMethod.GET, RequestMethod.POST})
    public R<Map<String, Object>> ping(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> data = new HashMap<>();
        data.put("caller", SecurityUtils.getCurrentUserId());
        data.put("server_time", LocalDateTime.now());
        data.put("echo", body);
        return R.ok(data);
    }
}
