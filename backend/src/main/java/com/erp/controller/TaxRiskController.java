package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.fin.OfflineInvoice;
import com.erp.entity.fin.TaxCert;
import com.erp.service.fin.RiskDashboardService;
import com.erp.service.fin.TaxBurdenService;
import com.erp.service.fin.TaxRiskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * 税务风险监控（9.6.1，tax-risk-monitoring）：看板/证书/离线开票/税负率。
 */
@Tag(name = "税务风险监控")
@RestController
@RequestMapping("/api/fin/tax-risks")
@RequiredArgsConstructor
public class TaxRiskController {

    private final RiskDashboardService dashboardService;
    private final TaxRiskService riskService;
    private final TaxBurdenService burdenService;

    // ---------- 看板 ----------

    @GetMapping("/dashboard")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "风险汇聚看板（8 类信号聚合，单源失败降级）")
    public R<Map<String, Object>> dashboard(@RequestParam(required = false) String periodEnd) {
        return R.ok(dashboardService.dashboard(periodEnd));
    }

    // ---------- 证书台账（C-4.14-14） ----------

    @GetMapping("/certs")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "证书列表（惰性过期 + 距今天数）")
    public R<List<Map<String, Object>>> listCerts(@RequestParam(required = false) String status) {
        return R.ok(riskService.listCerts(status));
    }

    @PostMapping("/certs")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "登记税控设备/数字证书（缺到期日 422）")
    public R<TaxCert> registerCert(@RequestBody Map<String, Object> payload) {
        return R.ok(riskService.registerCert(payload));
    }

    @PutMapping("/certs/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "更新证书（新到期日 → VALID，通道解阻）")
    public R<TaxCert> updateCert(@PathVariable String id,
                                 @RequestBody Map<String, Object> payload) {
        return R.ok(riskService.updateCert(id, payload));
    }

    @GetMapping("/certs/warnings")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "证书到期卡控（≤30 天预警 + 过期不可用清单）")
    public R<Map<String, Object>> certWarnings() {
        return R.ok(riskService.certWarnings());
    }

    // ---------- 离线开票（48h 告警） ----------

    @GetMapping("/offline-invoices")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "离线开票列表（含告警状态）")
    public R<List<OfflineInvoice>> listOffline() {
        return R.ok(riskService.listOffline());
    }

    @PostMapping("/offline-invoices")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "离线开票登记（通道证书过期 422 阻断）")
    public R<OfflineInvoice> registerOffline(@RequestBody Map<String, Object> payload) {
        return R.ok(riskService.registerOffline(payload));
    }

    @PutMapping("/offline-invoices/{id}/upload")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "补传登记（消除 L4 告警）")
    public R<OfflineInvoice> upload(@PathVariable String id) {
        return R.ok(riskService.uploadOffline(id));
    }

    @GetMapping("/offline-invoices/alerts")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "48h 未补传 L4 告警扫描")
    public R<List<Map<String, Object>>> offlineAlerts() {
        return R.ok(riskService.offlineAlerts());
    }

    // ---------- 税负率 ----------

    @GetMapping("/tax-burdens")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "月度税负率指标（增值税双阈值 + 所得税侧；供 FR-4.14-5-5 复核）")
    public R<Map<String, Object>> taxBurdens(@RequestParam String periodEnd,
                                             @RequestParam(defaultValue = "6") int months) {
        return R.ok(burdenService.monthly(periodEnd, months));
    }

    @GetMapping("/tax-burdens/signals")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "税负率 L4 超界信号")
    public R<List<Map<String, Object>>> burdenSignals(@RequestParam String periodEnd,
                                                      @RequestParam(defaultValue = "6") int months) {
        return R.ok(burdenService.signals(periodEnd, months));
    }
}
