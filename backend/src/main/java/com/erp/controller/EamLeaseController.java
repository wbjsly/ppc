package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamLeaseContract;
import com.erp.service.eam.EamLeaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 租赁管理（add-asset-transfer-lease，spec asset-transfer-lease）。
 * 7.4.3：合同 CRUD/分类/使用权确认/摊余计划/月度处理/冲回/重计量/临期/终止。
 */
@Tag(name = "租赁管理")
@RestController
@RequestMapping("/api/eam/leases")
public class EamLeaseController {

    private final EamLeaseService service;

    public EamLeaseController(EamLeaseService service) {
        this.service = service;
    }

    @Operation(summary = "合同分页")
    @GetMapping
    public R<Page<EamLeaseContract>> page(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(service.page(current, size, status, keyword));
    }

    @Operation(summary = "合同详情（含摊余计划与快照）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @Operation(summary = "录入（关键条款不完整 422 逐项列示）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody EamLeaseContract contract) {
        return R.ok(service.create(contract));
    }

    @Operation(summary = "编辑（仅草稿）")
    @PutMapping
    public R<Map<String, Object>> update(@RequestBody EamLeaseContract contract) {
        return R.ok(service.update(contract));
    }

    @Operation(summary = "删除（仅草稿）")
    @DeleteMapping("/{id}")
    public R<Map<String, Object>> delete(@PathVariable String id) {
        return R.ok(service.delete(id));
    }

    @Operation(summary = "系统辅助分类判断（≤12 月 EXEMPT 候选 / 否则 ROU 候选）")
    @GetMapping("/{id}/suggest")
    public R<Map<String, Object>> suggest(@PathVariable String id) {
        return R.ok(service.suggestClassify(id));
    }

    @Operation(summary = "分类人工确认（disputeByFinMgr=1 争议由财务经理留痕）")
    @PostMapping("/{id}/classify")
    public R<Map<String, Object>> classify(@PathVariable String id,
                                           @RequestParam String classify,
                                           @RequestParam String basis,
                                           @RequestParam(defaultValue = "false") boolean disputeByFinMgr) {
        return R.ok(service.classify(id, classify, basis, disputeByFinMgr));
    }

    @Operation(summary = "使用权确认（ROU：PV+建卡+全周期计划；EXEMPT：豁免留痕）")
    @PostMapping("/{id}/confirm")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(service.confirm(id));
    }

    @Operation(summary = "摊余计划查看")
    @GetMapping("/{id}/schedule")
    public R<Map<String, Object>> schedule(@PathVariable String id) {
        return R.ok(service.schedule(id));
    }

    @Operation(summary = "月度租赁处理（月标签幂等 422：利息+付款+豁免直线租金）")
    @PostMapping("/monthly/{periodTag}")
    public R<Map<String, Object>> monthly(@PathVariable String periodTag) {
        return R.ok(service.monthlyProcess(periodTag));
    }

    @Operation(summary = "整单冲回（红冲凭证+计划行复位+放行重提）")
    @PostMapping("/reverse/{periodTag}")
    public R<Map<String, Object>> reverse(@PathVariable String periodTag) {
        return R.ok(service.reverse(periodTag));
    }

    @Operation(summary = "变更重新计量提交（财务资产会计单签）")
    @PostMapping("/{id}/remeasure")
    public R<Map<String, Object>> remeasure(@PathVariable String id,
                                            @RequestParam(required = false) BigDecimal revisedRate,
                                            @RequestParam(required = false) Integer extraMonths,
                                            @RequestParam(required = false) BigDecimal revisedPayment,
                                            @RequestParam String reason) {
        return R.ok(service.remeasure(id, revisedRate, extraMonths, revisedPayment, reason));
    }

    @Operation(summary = "临期清单（6 月/1 月分级 + 默示续租暂估标记）")
    @GetMapping("/expiry")
    public R<List<Map<String, Object>>> expiry() {
        return R.ok(service.expiryList());
    }

    @Operation(summary = "到期终止（type=RETURN 退租核销 / BUY 购买转自有；差异超容差 L1 阻断）")
    @PostMapping("/{id}/terminate")
    public R<Map<String, Object>> terminate(@PathVariable String id,
                                            @RequestParam String type,
                                            @RequestParam(required = false) BigDecimal damageAmount,
                                            @RequestParam(required = false) String reason) {
        return R.ok(service.terminate(id, type, damageAmount, reason));
    }
}
