package com.erp.controller;

import com.erp.common.R;
import com.erp.util.SecurityUtils;
import com.erp.service.intf.EdiMessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 开放入口的 EDI 报文接收入口（spec edi-message-processing 5.2 入口①）。
 * 位于 /api/open/** —— 传输层（API Key + HMAC + 时间戳 + 限流 + 熔断 + 审计）由 OpenApiAuthFilter 先行校验，
 * 本控制器只负责把报文交给四级校验管线。
 */
@Tag(name = "开放 EDI 接收")
@RestController
@RequestMapping("/api/open/edi")
public class OpenEdiController {

    private final EdiMessageService ediMessageService;

    public OpenEdiController(EdiMessageService ediMessageService) {
        this.ediMessageService = ediMessageService;
    }

    @Operation(summary = "接收 EDI 报文（ORDERS / DESADV / INVOIC）")
    @PostMapping("/{msgType}")
    public R<Map<String, Object>> receive(@PathVariable String msgType,
                                          @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("raw");
        String json = raw == null ? toJson(body) : String.valueOf(raw);
        String partner = SecurityUtils.getCurrentUserId();
        return R.ok(ediMessageService.receive("OPEN", partner, msgType, json));
    }

    private static String toJson(Object o) {
        if (o == null) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules()
                    .writeValueAsString(o);
        } catch (Exception e) {
            return String.valueOf(o);
        }
    }
}
