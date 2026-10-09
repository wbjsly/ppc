package com.erp.controller;

import com.erp.common.R;
import com.erp.service.inv.WaveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 波次管理（4.8.1 波次拣货 / 4.8.2 集货发运，spec wave-management）：
 * 查询认证即可，写限 ADMIN/WAREHOUSE（服务层二次校验，design D7）。
 */
@Tag(name = "拣货波次")
@RestController
@RequestMapping("/api/inv/waves")
public class WaveController {

    private final WaveService service;
    private final com.erp.service.inv.FreezePauseService freezePauseService;

    public WaveController(WaveService service,
                          com.erp.service.inv.FreezePauseService freezePauseService) {
        this.service = service;
        this.freezePauseService = freezePauseService;
    }

    @PostMapping("/{id}/resume")
    @Operation(summary = "恢复冻结挂起（来源冻结单已 RELEASED 方可，WAREHOUSE/ADMIN）",
            description = "freeze-management ADDED 需求①：解冻不自动恢复，主管手动恢复回暂停前状态")
    public R<Map<String, Object>> resume(@PathVariable String id) {
        freezePauseService.resume("WAVE", id);
        return R.ok(Map.of("id", id, "resumed", true));
    }

    @PostMapping("/preview")
    @Operation(summary = "按规则生成波次候选预览（不落库，BR-4.4-42 聚类+超容拆分）")
    public R<Map<String, Object>> preview() {
        return R.ok(service.preview());
    }

    @PostMapping
    @Operation(summary = "确认候选组建波次（逐单校验 DRAFT，失效剔除）")
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups =
                (List<Map<String, Object>>) payload.get("groups");
        return R.ok(service.createFromPreview(groups));
    }

    @GetMapping
    @Operation(summary = "波次分页")
    public R<Map<String, Object>> page(@RequestParam(required = false) String status,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "20") long size) {
        return R.ok(service.page(status, keyword, current, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "波次详情（头+归属单据+分配行+改批记录）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "作废波次（未开拣，原因必填；解绑单据+作废任务+关未决审批）")
    public R<Map<String, Object>> cancel(@PathVariable String id,
                                         @RequestParam String reason) {
        return R.ok(service.cancel(id, reason));
    }

    @PostMapping("/{id}/allocate")
    @Operation(summary = "波次级统一分配（共享预算，不足拆单，生成 WAVE 任务）")
    public R<Map<String, Object>> allocate(@PathVariable String id) {
        return R.ok(service.allocate(id));
    }

    @PostMapping("/{id}/allocate-confirm")
    @Operation(summary = "确认分配（→ ALLOCATED + 生成 WAVE 合并任务；LOCKED 行 422 C-4.4-08）")
    public R<Map<String, Object>> confirmAllocate(@PathVariable String id) {
        return R.ok(service.confirmAllocate(id));
    }

    @PostMapping("/{id}/adjust")
    @Operation(summary = "人工改批/改仓位（C-4.4-08 强制审批：行锁定+主管审批）")
    public R<Map<String, Object>> adjust(@PathVariable String id,
                                         @RequestBody Map<String, Object> body) {
        return R.ok(service.adjust(id,
                String.valueOf(body.get("lineId")),
                String.valueOf(body.get("field")),
                String.valueOf(body.get("newValue")),
                body.get("reason") == null ? null : String.valueOf(body.get("reason"))));
    }

    // ---------- 4.8.2 集货发运 ----------

    @PostMapping("/{id}/docs/{shipId}/sort")
    @Operation(summary = "分播复核确认（不平落 WAVE_SORT 差异；全过 → STAGING）")
    public R<Map<String, Object>> sortConfirm(@PathVariable String id,
                                              @PathVariable String shipId,
                                              @RequestBody List<Map<String, Object>> actualLines) {
        return R.ok(service.sortConfirm(id, shipId, actualLines));
    }

    @PostMapping("/{id}/docs/{shipId}/load")
    @Operation(summary = "装车确认（比对分播快照，不一致 422 阻断该单 C-4.4-06）")
    public R<Map<String, Object>> loadConfirm(@PathVariable String id,
                                              @PathVariable String shipId,
                                              @RequestBody List<Map<String, Object>> scannedLines) {
        return R.ok(service.loadConfirm(id, shipId, scannedLines));
    }

    @PostMapping("/{id}/ship")
    @Operation(summary = "发运确认（逐单独立过账 BR-4.4-46，失败单独重试）")
    public R<Map<String, Object>> shipConfirm(@PathVariable String id) {
        return R.ok(service.shipConfirm(id));
    }

    @PostMapping("/{id}/docs/{shipId}/retry")
    @Operation(summary = "失败订单单独重试过账")
    public R<Map<String, Object>> retryShip(@PathVariable String id,
                                            @PathVariable String shipId) {
        return R.ok(service.retryShip(id, shipId));
    }
}
