package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArProvision;
import com.erp.service.fin.ArAgingService;
import com.erp.service.fin.ArConfirmService;
import com.erp.service.fin.ArProvisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 应收管理接口（add-ar-management：8.2.1 确认 / 8.2.2 账龄 / 8.2.3 坏账计提）。
 */
@Tag(name = "应收管理")
@RestController
@RequestMapping("/api/fin/ar-mgmt")
public class ArManagementController {

    private final ArConfirmService confirmService;
    private final ArAgingService agingService;
    private final ArProvisionService provisionService;

    public ArManagementController(ArConfirmService confirmService,
                                  ArAgingService agingService,
                                  ArProvisionService provisionService) {
        this.confirmService = confirmService;
        this.agingService = agingService;
        this.provisionService = provisionService;
    }

    // ================= 8.2.1 应收确认 =================

    @Operation(summary = "确认清单分页（confirmed=true 已确认/false 未确认）")
    @GetMapping("/confirm")
    public R<Page<ArInvoice>> confirmPage(@RequestParam(defaultValue = "1") long current,
                                          @RequestParam(defaultValue = "20") long size,
                                          @RequestParam(defaultValue = "false") boolean confirmed,
                                          @RequestParam(required = false) String keyword) {
        return R.ok(confirmService.confirmPage(current, size, confirmed, keyword));
    }

    @Operation(summary = "单笔确认（账龄起算锚点，已确认不可撤销）")
    @PostMapping("/confirm/{id}")
    public R<Map<String, Object>> confirm(@PathVariable String id) {
        return R.ok(confirmService.confirm(id));
    }

    @Operation(summary = "批量确认（上限 200）")
    @PostMapping("/confirm/batch")
    public R<Map<String, Object>> batchConfirm(@RequestBody List<String> arIds) {
        return R.ok(confirmService.batchConfirm(arIds));
    }

    // ================= 8.2.2 账龄分析 =================

    @Operation(summary = "账龄六段分析（超90占比=BR-4.3-14 判定源 + 环比）")
    @GetMapping("/aging")
    public R<Map<String, Object>> aging(@RequestParam(required = false) String customerId,
                                        @RequestParam(required = false)
                                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate) {
        return R.ok(agingService.aging(customerId, baseDate));
    }

    @Operation(summary = "某客户分段基数（坏账试算复用）")
    @GetMapping("/aging/buckets")
    public R<List<Map<String, Object>>> bucketBases(@RequestParam String customerId,
                                                    @RequestParam(required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate baseDate) {
        return R.ok(agingService.bucketBases(customerId, baseDate));
    }

    // ================= 8.2.3 坏账计提 =================

    @Operation(summary = "坏账计提试算（只算不落库）")
    @PostMapping("/provisions/trial")
    public R<Map<String, Object>> trial(@RequestParam String customerId, @RequestParam String period) {
        return R.ok(provisionService.trial(customerId, period));
    }

    @Operation(summary = "确认计提（冲回上期+本期重提+台账落库）")
    @PostMapping("/provisions/confirm")
    public R<Map<String, Object>> confirmProvision(@RequestParam String customerId,
                                                   @RequestParam String period) {
        return R.ok(provisionService.confirmProvision(customerId, period));
    }

    @Operation(summary = "计提台账分页")
    @GetMapping("/provisions")
    public R<Page<ArProvision>> provisionPage(@RequestParam(defaultValue = "1") long current,
                                              @RequestParam(defaultValue = "20") long size,
                                              @RequestParam(required = false) String period,
                                              @RequestParam(required = false) String customerId) {
        return R.ok(provisionService.provisionPage(current, size, period, customerId));
    }

    @Operation(summary = "计提台账详情（六段快照+凭证链）")
    @GetMapping("/provisions/{id}")
    public R<Map<String, Object>> provisionDetail(@PathVariable String id) {
        return R.ok(provisionService.provisionDetail(id));
    }
}
