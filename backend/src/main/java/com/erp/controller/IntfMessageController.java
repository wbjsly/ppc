package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.common.ServiceException;
import com.erp.entity.intf.IntfEdimap;
import com.erp.entity.intf.IntfMessage;
import com.erp.security.IntfGuard;
import com.erp.service.intf.EdiMessageService;
import com.erp.service.intf.IntfSimulationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 报文台账、映射规则与人工重放（菜单 2.8.3「事件与报文」）。
 * 入口②页面上传、入口③一键模拟伙伴推送（真实签名经开放入口，design D6）。
 */
@Tag(name = "EDI 报文台账")
@RestController
@RequestMapping("/api/intf/messages")
public class IntfMessageController {

    private final EdiMessageService service;
    private final IntfSimulationService simulationService;

    public IntfMessageController(EdiMessageService service, IntfSimulationService simulationService) {
        this.service = service;
        this.simulationService = simulationService;
    }

    @Operation(summary = "报文台账分页")
    @GetMapping
    public R<Page<IntfMessage>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String msgType,
                                     @RequestParam(required = false) String status,
                                     @RequestParam(required = false) String partnerCode) {
        return R.ok(service.page(current, size, msgType, status, partnerCode));
    }

    @Operation(summary = "报文详情（四级校验结果：行/段/字段定位）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "页面上传报文（入口②）")
    @PostMapping("/upload")
    public R<Map<String, Object>> upload(@RequestBody Map<String, Object> body) {
        String partnerCode = str(body.get("partnerCode"));
        String msgType = str(body.get("msgType"));
        Object raw = body.get("raw");
        if (partnerCode == null || msgType == null || raw == null) {
            throw new ServiceException(400, "partnerCode、msgType、raw 必填");
        }
        return R.ok(service.receive("UPLOAD", partnerCode, msgType, String.valueOf(raw)));
    }

    @Operation(summary = "一键模拟伙伴推送（入口③：按伙伴密钥自动签名经开放入口）")
    @PostMapping("/simulate-push")
    public R<Map<String, Object>> simulatePush(@RequestBody Map<String, Object> body) {
        String partnerCode = str(body.get("partnerCode"));
        String msgType = str(body.get("msgType"));
        Object raw = body.get("raw");
        if (partnerCode == null || msgType == null || raw == null) {
            throw new ServiceException(400, "partnerCode、msgType、raw 必填");
        }
        return R.ok(simulationService.pushMessage(partnerCode, msgType, String.valueOf(raw)));
    }

    @Operation(summary = "人工重放（保留原幂等键）")
    @PostMapping("/{id}/replay")
    public R<Map<String, Object>> replay(@PathVariable String id) {
        return R.ok(service.replay(id));
    }

    @Operation(summary = "映射规则分页")
    @GetMapping("/map-rules")
    public R<Page<IntfEdimap>> rules(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String partnerCode,
                                     @RequestParam(required = false) String msgType) {
        return R.ok(service.mapRules(current, size, partnerCode, msgType));
    }

    @Operation(summary = "保存/更新映射规则（伙伴 × 报文类型）")
    @PostMapping("/map-rules")
    public R<Map<String, Object>> saveRule(@RequestBody Map<String, Object> body) {
        IntfGuard.requireAdmin("映射规则维护");
        return R.ok(service.saveMapRule(body));
    }

    private static String str(Object v) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }
}
