package com.erp.controller;

import com.erp.common.R;
import com.erp.service.eam.EamReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 资产报表（add-asset-report，7.8.1~7.8.3，只读 + CSV 导出）。
 */
@Tag(name = "资产报表")
@RestController
@RequestMapping("/api/eam/reports")
@RequiredArgsConstructor
public class EamReportController {

    private final EamReportService service;

    @Operation(summary = "变动事件流水分页（七路 union，日期倒序）")
    @GetMapping("/change/events")
    public R<Map<String, Object>> changeEvents(@RequestParam(defaultValue = "1") long current,
                                               @RequestParam(defaultValue = "20") long size,
                                               @RequestParam(required = false) String fromDate,
                                               @RequestParam(required = false) String toDate,
                                               @RequestParam(required = false) String evtType,
                                               @RequestParam(required = false) String categoryCode,
                                               @RequestParam(required = false) String costCenter) {
        return R.ok(service.changeEvents(current, size, fromDate, toDate, evtType, categoryCode, costCenter));
    }

    @Operation(summary = "变动三栏勾对汇总（期末=期初+增−减 + reconOk）")
    @GetMapping("/change/summary")
    public R<Map<String, Object>> changeSummary(@RequestParam(required = false) String fromDate,
                                                @RequestParam(required = false) String toDate) {
        return R.ok(service.changeSummary(fromDate, toDate));
    }

    @Operation(summary = "折旧汇总（分类/成本中心双维 + 环比差异/超容差 alerted + RECON_DIFF 页头）")
    @GetMapping("/depreciation/summary")
    public R<Map<String, Object>> depreciationSummary(@RequestParam(required = false) String periodTag) {
        return R.ok(service.depreciationSummary(periodTag));
    }

    @Operation(summary = "当期折旧明细（与计提单一致）")
    @GetMapping("/depreciation/details")
    public R<List<Map<String, Object>>> depreciationDetails(@RequestParam(required = false) String periodTag) {
        return R.ok(service.depreciationDetails(periodTag));
    }

    @Operation(summary = "资产净值台账（当前口径，排除处置/在建/归档卡）")
    @GetMapping("/depreciation/ledger")
    public R<Map<String, Object>> netAssetLedger(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "20") long size,
                                                 @RequestParam(required = false) String categoryCode,
                                                 @RequestParam(required = false) String costCenter,
                                                 @RequestParam(required = false) String deprStatus) {
        return R.ok(service.netAssetLedger(current, size, categoryCode, costCenter, deprStatus));
    }

    @Operation(summary = "盘点报告列表（历次计划含覆盖率/归档态）")
    @GetMapping("/count/reports")
    public R<Map<String, Object>> countReportList(@RequestParam(defaultValue = "1") long current,
                                                  @RequestParam(defaultValue = "20") long size,
                                                  @RequestParam(required = false) String keyword) {
        return R.ok(service.countReportList(current, size, keyword));
    }

    @Operation(summary = "盘点报告详情（四类分布/TOP/原因聚合/SUMMARY）")
    @GetMapping("/count/reports/{planId}")
    public R<Map<String, Object>> countReportDetail(@PathVariable String planId) {
        return R.ok(service.countReportDetail(planId));
    }

    @Operation(summary = "导出变动事件 CSV（筛选一致，超限 422；BOM+中文表头，同盘点导出先例）")
    @GetMapping(value = "/export/change", produces = "text/csv;charset=UTF-8")
    public String exportChange(@RequestParam(required = false) String fromDate,
                               @RequestParam(required = false) String toDate,
                               @RequestParam(required = false) String evtType,
                               @RequestParam(required = false) String categoryCode,
                               @RequestParam(required = false) String costCenter) {
        return service.exportChangeCsv(fromDate, toDate, evtType, categoryCode, costCenter);
    }

    @Operation(summary = "导出折旧明细 CSV")
    @GetMapping(value = "/export/depreciation", produces = "text/csv;charset=UTF-8")
    public String exportDepreciation(@RequestParam(required = false) String periodTag) {
        return service.exportDepreciationCsv(periodTag);
    }

    @Operation(summary = "导出净值台账 CSV")
    @GetMapping(value = "/export/ledger", produces = "text/csv;charset=UTF-8")
    public String exportLedger(@RequestParam(required = false) String categoryCode,
                               @RequestParam(required = false) String costCenter,
                               @RequestParam(required = false) String deprStatus) {
        return service.exportLedgerCsv(categoryCode, costCenter, deprStatus);
    }
}
