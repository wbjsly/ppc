package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.fin.TaxReturn;
import com.erp.service.fin.TaxFilingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 纳税申报接口（M9-5-1/2，spec tax-filing）。
 * 权限对齐 M9-5 菜单三角色；审批签署走 /api/qms/approvals 底座。
 */
@RestController
@RequestMapping("/api/fin/tax-returns")
@RequiredArgsConstructor
@Tag(name = "纳税申报（M9-5-1/2）")
public class TaxFilingController {

    private final TaxFilingService service;

    @GetMapping("/summarize")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "进销项数据汇总（按税率/认证态，BR-4.14-31 跨期提示）")
    public R<Map<String, Object>> summarize(@RequestParam String period) {
        return R.ok(service.summarize(period));
    }

    @GetMapping("/reconcile")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "勾稽校验（C-4.14-04：申报数 vs 账载 POSTED 净发生额，栏次级差异行）")
    public R<Map<String, Object>> reconcile(@RequestParam String period) {
        return R.ok(service.reconcile(period));
    }

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "增值税申报表生成（主表+附表1/2+附加税；BR-32 留抵结转 / BR-34 联动）")
    public R<Map<String, Object>> generate(@RequestBody(required = false) Map<String, Object> body) {
        return R.ok(service.generate(body == null ? null : (String) body.get("period"), body));
    }

    @PostMapping("/generate-cit")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "所得税预缴申报生成（累计利润×税率+调整项；账载差异同口径阻断）")
    public R<Map<String, Object>> generateCit(@RequestBody Map<String, Object> body) {
        String period = String.valueOf(body.get("period"));
        return R.ok(service.generateCit(period, body));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "提交三级审批（专员复核→主管审核→经理终审；通过即锁定）")
    public R<Map<String, Object>> submit(@PathVariable String id) {
        return R.ok(service.submit(id));
    }

    @PostMapping("/{id}/register")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "缴款回执离线登记（提交→回执→缴款顺序校验，FR-4.14-5-6）")
    public R<Map<String, Object>> register(@PathVariable String id,
                                           @RequestBody Map<String, Object> payload) {
        return R.ok(service.registerStatus(id, payload));
    }

    @PostMapping("/{id}/mark-overdue")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "逾期申报事件单（滞纳金估算 + 升级财务经理）")
    public R<Map<String, Object>> markOverdue(@PathVariable String id,
                                              @RequestBody Map<String, String> body) {
        return R.ok(service.markOverdue(id, body.get("faultNote")));
    }

    @PostMapping("/{id}/adjust")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "跨期调整（BR-4.14-31：快照 + 二级审批；仅锁定期可用）")
    public R<Map<String, Object>> adjust(@PathVariable String id,
                                         @RequestBody Map<String, Object> payload) {
        return R.ok(service.adjust(id, payload));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "申报单分页（期间/税种/状态筛选）")
    public R<Page<TaxReturn>> page(@RequestParam(defaultValue = "1") long current,
                                   @RequestParam(defaultValue = "20") long size,
                                   @RequestParam(required = false) String period,
                                   @RequestParam(required = false) String taxType,
                                   @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, period, taxType, status));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "详情（申报单+栏次+审批轨迹+调整记录）")
    public R<Map<String, Object>> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @GetMapping("/archive")
    @PreAuthorize("hasAnyRole('FINANCE','FIN_MGR','ADMIN')")
    @Operation(summary = "申报归档（按所属期聚合已申报产物，保存期限 ≥10 年，FR-4.14-5-7）")
    public R<Map<String, Object>> archive(@RequestParam String period) {
        return R.ok(service.archive(period));
    }
}
