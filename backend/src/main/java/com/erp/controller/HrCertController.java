package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.hr.HrCertCategory;
import com.erp.entity.hr.HrEmployeeCert;
import com.erp.service.hr.HrCertService;
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

/** 资格认证 HR 侧（qualification-certification，10.9.2 台账/审核 + 10.9.3 预警）。 */
@Tag(name = "资格认证")
@RestController
@RequestMapping("/api/hr/certs")
@RequiredArgsConstructor
public class HrCertController {

    private final HrCertService certService;

    // ---- 类别字典 ----

    @GetMapping("/categories")
    @Operation(summary = "证书类别字典（含 DEMO 种子：电工/焊工关键类，健康证非关键）")
    public R<List<HrCertCategory>> categories() {
        return R.ok(certService.categoryList());
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "新建类别（编码 CATE+流水自动生成，创建后不可改）")
    public R<HrCertCategory> createCategory(@RequestBody Map<String, Object> payload) {
        return R.ok(certService.createCategory(payload));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "更新类别（编码不可改；停用后不可被新证书引用）")
    public R<HrCertCategory> updateCategory(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        return R.ok(certService.updateCategory(id, payload));
    }

    // ---- 台账与审核 ----

    @GetMapping
    @Operation(summary = "台账（按状态/类别过滤）")
    public R<List<HrEmployeeCert>> ledger(@RequestParam(required = false) String status,
                                          @RequestParam(required = false) String cateId) {
        return R.ok(certService.ledger(status, cateId));
    }

    @GetMapping("/pending")
    @Operation(summary = "审核队列（PENDING）")
    public R<List<HrEmployeeCert>> pending() {
        return R.ok(certService.pendingList());
    }

    @PostMapping("/{id}/audit")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "审核（通过=同类别旧证归档→VALID→矩阵写入；驳回=REJECTED+意见）")
    public R<HrEmployeeCert> audit(@PathVariable String id, @RequestBody Map<String, Object> body) {
        boolean pass = !"false".equals(String.valueOf(body.get("pass")));
        return R.ok(certService.audit(id, pass, String.valueOf(body.get("opinion"))));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAnyRole('HR','HR_MGR','ADMIN')")
    @Operation(summary = "换证归档（旧证 ARCHIVED，矩阵 CERT 行联动失效）")
    public R<HrEmployeeCert> archive(@PathVariable String id) {
        return R.ok(certService.archive(id));
    }

    // ---- 到期预警（10.9.3 三清单） ----

    @GetMapping("/alert-lists")
    @Operation(summary = "三清单：临期复审 / 已失效 / 带病排班")
    public R<Map<String, Object>> alertLists() {
        return R.ok(certService.alertLists());
    }

    @PostMapping("/flip-scan")
    @PreAuthorize("hasAnyRole('HR_MGR','ADMIN')")
    @Operation(summary = "手动触发每日翻转（通常由每日任务执行，幂等）")
    public R<Map<String, Object>> flipScan() {
        return R.ok(certService.dailyFlipScan());
    }
}
