package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.eam.EamReworkItem;
import com.erp.entity.eam.EamReworkOrder;
import com.erp.service.eam.EamReworkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.util.Map;

/**
 * 资产技改大修（add-asset-rework，7.7.1 资本确认）。
 */
@Tag(name = "资产技改大修")
@RestController
@RequestMapping("/api/eam/reworks")
@RequiredArgsConstructor
public class EamReworkController {

    private final EamReworkService service;

    /** 强类型请求体（同处置 DisposalPayload：避免 Map 强转异常） */
    public record ReworkPayload(EamReworkOrder order, List<EamReworkItem> items) {
        public List<EamReworkItem> items() {
            return items == null ? List.of() : items;
        }
    }

    @Operation(summary = "创建技改单（一卡一单）")
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody ReworkPayload payload) {
        return R.ok(service.create(payload.order(), payload.items()));
    }

    @Operation(summary = "编辑草稿")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable String id, @RequestBody ReworkPayload payload) {
        EamReworkOrder order = payload.order();
        order.setId(id);
        service.update(order, payload.items());
        return R.ok();
    }

    @Operation(summary = "提交判定（BR-38 防拆分标记计算）")
    @PostMapping("/{id}/submit")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @Operation(summary = "财务资产会计判定（BR-37；命中复核标记转财务经理审批）")
    @PostMapping("/{id}/judge")
    public R<Map<String, Object>> judge(@PathVariable String id,
                                        @RequestParam String capJudge,
                                        @RequestParam(required = false) String judgeNote,
                                        @RequestParam(required = false) String judgeEvidence,
                                        @RequestBody(required = false) List<EamReworkItem> items) {
        return R.ok(service.judge(id, capJudge, judgeNote, judgeEvidence, items));
    }

    @Operation(summary = "争议裁定留痕（财务经理）")
    @PostMapping("/{id}/arbitrate")
    public R<Void> arbitrate(@PathVariable String id, @RequestParam String note) {
        service.arbitrate(id, note);
        return R.ok();
    }

    @Operation(summary = "特批续提申请（BR-39）")
    @PostMapping("/{id}/continue-depr")
    public R<Map<String, Object>> continueDepr(@PathVariable String id,
                                               @RequestParam(required = false) String reason) {
        return R.ok(service.requestContinueDepr(id, reason));
    }

    @Operation(summary = "转在建（凭证+1:1 CIP 项目+停提）")
    @PostMapping("/{id}/to-in-rework")
    public R<Map<String, Object>> toInRework(@PathVariable String id) {
        return R.ok(service.toInRework(id));
    }

    @Operation(summary = "完工验收（CIP complete，C-4.13-06 超期起点）")
    @PostMapping("/{id}/accept")
    public R<Map<String, Object>> accept(@PathVariable String id,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate acceptDate) {
        return R.ok(service.accept(id, acceptDate));
    }

    @Operation(summary = "转资重建（BR-40：新原值+原卡归档+新卡）")
    @PostMapping("/{id}/rebuild")
    public R<Map<String, Object>> rebuild(@PathVariable String id,
                                          @RequestParam Integer newLifeYears) {
        return R.ok(service.rebuild(id, newLifeYears));
    }

    @Operation(summary = "费用化确认（借660201/贷2202）")
    @PostMapping("/{id}/expense")
    public R<Map<String, Object>> expense(@PathVariable String id) {
        return R.ok(service.expense(id));
    }

    @Operation(summary = "结案归档")
    @PostMapping("/{id}/close")
    public R<Void> close(@PathVariable String id) {
        service.close(id);
        return R.ok();
    }

    @Operation(summary = "整单冲回（仅在建期且归集为零）")
    @PostMapping("/{id}/reverse")
    public R<Map<String, Object>> reverse(@PathVariable String id) {
        return R.ok(service.reverse(id));
    }

    @Operation(summary = "C-4.13-06 超期扫描（>30 天预警，>60 天升级财务经理）")
    @PostMapping("/overdue-scan")
    public R<Map<String, Object>> overdueScan() {
        return R.ok(service.scanOverdue());
    }

    @Operation(summary = "分页")
    @GetMapping
    @PreAuthorize("hasAnyRole('ASSET_ADMIN','FIN_ASSET','DEPT_HEAD','FIN_MGR','ADMIN')")
    public R<Page<EamReworkOrder>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "20") long size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @Operation(summary = "详情（单据+判定依据+CIP 项目概要）")
    @GetMapping("/{id}")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }
}
