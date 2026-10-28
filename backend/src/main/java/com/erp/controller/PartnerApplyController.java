package com.erp.controller;

import com.erp.common.R;
import com.erp.service.scm.PartnerActivateService;
import com.erp.service.scm.PartnerApplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 伙伴引入申请单（add-partner-onboarding，菜单 12.1.1/12.1.2）。
 * SecurityConfig：/api/proc/partner-applies/** 先于 /api/proc/** 兜底（PROCUREMENT/ADMIN）。
 */
@Tag(name = "伙伴引入申请")
@RestController
@RequestMapping("/api/proc/partner-applies")
public class PartnerApplyController {

    private final PartnerApplyService service;
    private final PartnerActivateService activateService;

    public PartnerApplyController(PartnerApplyService service,
                                  PartnerActivateService activateService) {
        this.service = service;
        this.activateService = activateService;
    }

    @Operation(summary = "分页（status/keyword）")
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, keyword));
    }

    @Operation(summary = "详情（含证照/激活链接状态/审批日志）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "保存草稿（信用代码 18 位+重复 L1 校验）")
    @PostMapping
    public R<Map<String, Object>> save(@RequestBody Map<String, Object> payload) {
        return R.ok(service.save(payload));
    }

    @Operation(summary = "提交审批（风险分级动态组链）")
    @PostMapping("/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "撤回（APPROVING → DRAFT）")
    @PutMapping("/{id}/withdraw")
    public R<Map<String, Object>> withdraw(@PathVariable String id) {
        return R.ok(service.withdraw(id));
    }

    @Operation(summary = "保存证照行（附件经 /api/common/attachments 上传，bizType=PARTNER_APPLY_CERT）")
    @PostMapping("/certs")
    public R<Map<String, Object>> saveCert(@RequestBody Map<String, Object> payload) {
        return R.ok(service.saveCert(payload));
    }

    @Operation(summary = "删除证照行（仅 DRAFT）")
    @DeleteMapping("/certs/{certId}")
    public R<Void> deleteCert(@PathVariable String certId) {
        service.deleteCert(certId);
        return R.ok(null);
    }

    @Operation(summary = "激活链接（链接+状态，PROCUREMENT/ADMIN）")
    @GetMapping("/{id}/activate-link")
    public R<Map<String, Object>> activateLink(@PathVariable String id) {
        return R.ok(activateService.activateLink(id));
    }

    @Operation(summary = "重新生成激活链接（旧链立即失效，BR-4.9-11）")
    @PostMapping("/{id}/activate-link/regenerate")
    public R<Map<String, Object>> regenerate(@PathVariable String id) {
        return R.ok(activateService.regenerate(id));
    }
}
