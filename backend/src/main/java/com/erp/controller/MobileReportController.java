package com.erp.controller;

import com.erp.common.R;
import com.erp.service.iot.IotReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 移动报工薄适配层（add-iot-mobile 13.1，复用 shop-floor-reporting 不改其本体）。
 * 权限（SecurityConfig）：现场执行口 = SHOP_FLOOR/ADMIN 等现场角色。
 */
@Tag(name = "移动物联-移动报工")
@RestController
@RequestMapping("/api/iot/reporting")
public class MobileReportController {

    private final IotReportService service;

    public MobileReportController(IotReportService service) {
        this.service = service;
    }

    @GetMapping("/my-orders")
    @Operation(summary = "13.1.1 我的工单（已释放工单按工位/人员推送）")
    public R<List<Map<String, Object>>> myOrders(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String wcCode) {
        return R.ok(service.myOrders(userId, wcCode));
    }

    @PostMapping("/push-escalations/scan")
    @Operation(summary = "72h 离线推送升级扫描（FR-4.11-1-1 异常处理②）")
    public R<Map<String, Object>> scanPushEscalations() {
        return R.ok(service.scanPushEscalations());
    }

    @PostMapping("/verify-material")
    @Operation(summary = "扫码领料校验（FR-4.11-1-2：BOM/批次/容差/库存四校验）")
    public R<Map<String, Object>> verifyMaterial(@RequestBody Map<String, Object> body) {
        return R.ok(service.verifyMaterial(
                (String) body.get("dispatchId"),
                (String) body.get("scanCode"),
                (java.math.BigDecimal) body.get("qty"),
                (String) body.get("reason")));
    }

    @PostMapping("/first-article")
    @Operation(summary = "13.1.2 首件提交（CTQ 数据+拍照，离线条件放行 BR-4.11-10）")
    public R<Map<String, Object>> submitFirstArticle(@RequestBody Map<String, Object> body) {
        return R.ok(service.submitFirstArticle(body));
    }

    @GetMapping("/first-article")
    @Operation(summary = "首件记录查询")
    public R<Map<String, Object>> firstArticleOf(@RequestParam String dispatchId) {
        return R.ok(service.firstArticleOf(dispatchId));
    }

    @PostMapping("/first-article/{id}/confirm")
    @Operation(summary = "13.1.3 IPQC 首件确认（PASS 解锁批量报工；离线批次复核不合格转差异单）")
    public R<Map<String, Object>> confirmFirstArticle(@PathVariable String id,
                                                      @RequestBody Map<String, Object> body) {
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        return R.ok(service.confirmFirstArticle(id, pass, (String) body.get("remark")));
    }

    @PostMapping("/output")
    @Operation(summary = "13.1.2 移动工序报工（首件门禁+幂等去重+超产分级+连续不良告警）")
    public R<Map<String, Object>> reportOutput(@RequestBody Map<String, Object> body) {
        return R.ok(service.reportOutput(body));
    }

    @PutMapping("/{rptId}/review")
    @Operation(summary = "13.1.4 报工审核（车间主任超产/异常报工在线审核，双因子上游校验）")
    public R<Map<String, Object>> reviewReport(@PathVariable String rptId,
                                               @RequestBody Map<String, Object> body) {
        boolean pass = Boolean.TRUE.equals(body.get("pass"));
        return R.ok(service.reviewReport(rptId, pass, (String) body.get("opinion")));
    }
}
