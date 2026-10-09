package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.inv.InvBatch;
import com.erp.service.inv.BatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 批次台账（4.2.1，spec batch-master）。
 * 权限：ROLE_WAREHOUSE,ROLE_ADMIN（服务层二次校验，design D7）。
 */
@Tag(name = "批次台账")
@RestController
@RequestMapping("/api/inv/batches")
public class BatchController {

    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "批次查询", description = "物料/批次号关键字/供应商批次/效期区间筛选，效期升序")
    public R<List<InvBatch>> query(@RequestParam(required = false) String itemCode,
                                   @RequestParam(required = false) String keyword,
                                   @RequestParam(required = false) String supplierBatchNo,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryFrom,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiryTo,
                                   @RequestParam(required = false) String status) {
        return R.ok(service.query(itemCode, keyword, supplierBatchNo, expiryFrom, expiryTo, status));
    }

    @GetMapping("/{id}")
    @Operation(summary = "批次详情")
    public R<InvBatch> get(@PathVariable String id) {
        return R.ok(service.get(id));
    }

    @PostMapping
    @Operation(summary = "新建批次", description = "批次号留空=系统生成 B+yyMMdd+-+4位流水；同物料唯一 409；批次管理物料效期必填 422")
    public R<InvBatch> create(@RequestBody InvBatch batch) {
        return R.ok(service.create(batch));
    }

    @PutMapping
    @Operation(summary = "变更批次", description = "批次号/物料创建后锁定（422）")
    public R<InvBatch> update(@RequestBody InvBatch batch) {
        return R.ok(service.update(batch));
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用批次")
    public R<Void> enable(@PathVariable String id) {
        service.enable(id);
        return R.ok();
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "关闭批次", description = "退出后续可选范围，不影响既有引用")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }
}
