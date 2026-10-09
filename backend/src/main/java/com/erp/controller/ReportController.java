package com.erp.controller;

import com.erp.common.R;
import com.erp.service.SysParamService;
import com.erp.service.bi.ExportService;
import com.erp.service.inv.InvReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库存报表（4.14，spec inventory-reports）：实时查询/周转/库龄呆滞/补数/呆滞扫描/双路径导出。
 * 查询只读仅需认证（菜单 PERM 管可见性）；导出 ≤EXPORT_ROW_LIMIT 同步 CSV、超限走 bi 导出治理（C-4.10-05）。
 */
@Tag(name = "库存管理-库存报表")
@RestController
@RequestMapping("/api/inv/reports")
public class ReportController {

    private final InvReportService reportService;
    private final ExportService exportService;
    private final SysParamService sysParamService;

    public ReportController(InvReportService reportService, ExportService exportService,
                            SysParamService sysParamService) {
        this.reportService = reportService;
        this.exportService = exportService;
        this.sysParamService = sysParamService;
    }

    // ---------- 4.14.1 实时查询 ----------

    @GetMapping("/realtime")
    @Operation(summary = "实时库存位行明细（库龄列 + 批次合计，spec 实时库存位行查询）")
    public R<Map<String, Object>> realtime(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String abcClass,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(reportService.realtime(warehouseCode, itemCode, batchNo, keyword,
                abcClass, current, size));
    }

    // ---------- 4.14.2 周转 ----------

    @GetMapping("/turnover")
    @Operation(summary = "期间周转指标（M-WMS-001，口径切换 + 快照降级标注）")
    public R<Map<String, Object>> turnover(@RequestParam(required = false) String from,
                                           @RequestParam(required = false) String to,
                                           @RequestParam(required = false) String scope) {
        return R.ok(reportService.turnover(from, to, scope));
    }

    @GetMapping("/turnover/summary")
    @Operation(summary = "周转汇总（按物料/仓库/ABC 分组）")
    public R<List<Map<String, Object>>> turnoverSummary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String scope,
            @RequestParam(defaultValue = "ITEM") String groupBy) {
        return R.ok(reportService.turnoverSummary(from, to, scope, groupBy));
    }

    @GetMapping("/turnover/trend")
    @Operation(summary = "跨月周转趋势（缺快照月份不输出点，前端断开）")
    public R<List<Map<String, Object>>> turnoverTrend(@RequestParam(defaultValue = "6") int months) {
        return R.ok(reportService.turnoverTrend(months));
    }

    @PostMapping("/dayclose/backfill")
    @Operation(summary = "日结补数（幂等回补最近一个缺失日快照，spec 补数入口）")
    public R<Map<String, Object>> backfill() {
        return R.ok(reportService.backfillDayClose());
    }

    // ---------- 4.14.3 库龄与呆滞 ----------

    @GetMapping("/aging")
    @Operation(summary = "库龄五桶分布 + 呆滞金额/占比（SLOW_MOVING_AGE_DAYS 阈值）")
    public R<Map<String, Object>> aging(@RequestParam(required = false) String warehouseCode,
                                        @RequestParam(required = false) String itemCode,
                                        @RequestParam(required = false) String abcClass) {
        return R.ok(reportService.agingBuckets(warehouseCode, itemCode, abcClass));
    }

    @GetMapping("/aging/list")
    @Operation(summary = "库龄明细（staleOnly=仅呆滞行），库龄降序分页")
    public R<Map<String, Object>> agingList(
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String abcClass,
            @RequestParam(defaultValue = "false") boolean staleOnly,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        return R.ok(reportService.agingList(warehouseCode, itemCode, abcClass, staleOnly,
                current, size));
    }

    @PostMapping("/slow-moving/scan")
    @Operation(summary = "呆滞扫描（随日结调度每日自动执行；此为手工触发/运维入口，幂等）")
    public R<Map<String, Object>> scan() {
        int n = reportService.dailySlowMovingScan();
        return R.ok(Map.of("notified", n));
    }

    // ---------- 7.1 双路径导出 ----------

    /** 导出入口：POST /export.csv?type=&...（同步 CSV 或后台任务 JSON） */
    @org.springframework.web.bind.annotation.PostMapping("/export.csv")
    @Operation(summary = "导出（同步 CSV / 超限转后台）")
    public void exportCsv(
            @RequestParam String type,
            @RequestParam(required = false) String warehouseCode,
            @RequestParam(required = false) String itemCode,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String abcClass,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String groupBy,
            @RequestParam(required = false) String staleOnly,
            HttpServletResponse response) throws java.io.IOException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("warehouseCode", warehouseCode);
        params.put("itemCode", itemCode);
        params.put("batchNo", batchNo);
        params.put("keyword", keyword);
        params.put("abcClass", abcClass);
        params.put("from", from);
        params.put("to", to);
        params.put("scope", scope);
        params.put("groupBy", groupBy == null ? "ITEM" : groupBy);
        params.put("staleOnly", staleOnly);
        int rowLimit = sysParamService.getInt("EXPORT_ROW_LIMIT", 10000);
        params.put("maxRows", rowLimit + 1L);   // 多取 1 行作估行依据（真实导出截断口径）

        Map<String, Object> data = reportService.exportRows(type, params);
        @SuppressWarnings("unchecked")
        List<List<Object>> rows = (List<List<Object>>) data.get("rows");

        if (rows.size() > rowLimit) {
            // 超限 → 后台导出任务（审批/水印/通知沿 bi 治理）；响应体无法改 JSON，抛 422 携带指引
            throw new com.erp.common.ServiceException(422, "预估 " + rows.size()
                    + " 行超过 EXPORT_ROW_LIMIT=" + rowLimit + "，请使用后台导出（POST /api/bi/export"
                    + " dataset=inv-report）走审批流程");
        }

        @SuppressWarnings("unchecked")
        List<String> headers = (List<String>) data.get("headers");
        String filename = type + "-report-" + LocalDate.now() + ".csv";
        response.setContentType("text/csv;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''"
                + URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20"));
        java.io.PrintWriter w = response.getWriter();
        w.write('\uFEFF');   // Excel BOM
        w.println(String.join(",", headers));
        for (List<Object> row : rows) {
            w.println(joinCsv(row));
        }
        w.flush();
    }

    // 说明：Task 7.1 的「超限走 submit」入口由 bi ExportService 承载（dataset=inv-report，
    // 见 ExportServiceImpl 注册）；此处同步路径已按 spec 对齐双路径分流。

    private static String joinCsv(List<Object> row) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String v = row.get(i) == null ? "" : String.valueOf(row.get(i));
            if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
                sb.append('"').append(v.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
