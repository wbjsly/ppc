package com.erp.controller;

import com.erp.common.R;
import com.erp.service.intf.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 凭证一次性分发链接（spec api-credential-management 3.2）。
 * 位于 `/credential/**`（非 `/api`），由 token 承担鉴权：10 分钟有效、下载后标记来源、「已接收」确认后清空临时明文。
 */
@Tag(name = "凭证一次性分发")
@RestController
@RequestMapping("/credential")
public class CredentialLinkController {

    private final CredentialService service;

    public CredentialLinkController(CredentialService service) {
        this.service = service;
    }

    @Operation(summary = "下载凭证（一次性链接，10 分钟有效）")
    @GetMapping("/download")
    public R<Map<String, Object>> download(@RequestParam String token, HttpServletRequest request) {
        return R.ok(service.download(token, clientIp(request), request.getHeader("User-Agent")));
    }

    @Operation(summary = "调用方「已接收」确认（清空临时明文口令）")
    @PostMapping("/ack")
    public R<Map<String, Object>> ack(@RequestParam String token) {
        return R.ok(service.ack(token));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
