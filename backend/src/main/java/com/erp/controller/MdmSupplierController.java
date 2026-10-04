package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.mdm.MdmSupplierCert;
import com.erp.entity.mdm.MdmSupplierVersion;
import com.erp.service.mdm.MdmSupplierService;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** 供应商准入审核（供方管理 1.4.1，/api/mdm/suppliers） */
@RestController
@RequestMapping("/api/mdm/suppliers")
public class MdmSupplierController {

    private final MdmSupplierService service;

    public MdmSupplierController(MdmSupplierService service) {
        this.service = service;
    }

    @GetMapping
    public R<Page<MdmSupplier>> page(@RequestParam(defaultValue = "1") long current,
                                     @RequestParam(defaultValue = "10") long size,
                                     @RequestParam(required = false) String keyword,
                                     @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @GetMapping("/{id}")
    public R<MdmSupplier> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    /** 建档（黑名单 HIT 硬阻断 / 查重 409 → forceCreate+dupNote 放行） */
    @PostMapping
    public R<MdmSupplier> create(@RequestBody MdmSupplier supplier,
                                 @RequestParam(defaultValue = "false") boolean forceCreate) {
        return R.ok(service.create(supplier, forceCreate));
    }

    @PutMapping
    public R<MdmSupplier> update(@RequestBody MdmSupplier supplier) {
        return R.ok(service.update(supplier));
    }

    /** 单步准入审核：result=APPROVED|REJECTED（原因必填） */
    @PutMapping("/{id}/review")
    public R<MdmSupplier> review(@PathVariable String id,
                                 @RequestParam String result,
                                 @RequestParam(required = false) String reason) {
        return R.ok(service.review(id, result, reason));
    }

    /** 状态迁移（五态矩阵守卫；启用含恢复态判定） */
    @PutMapping("/{id}/status")
    public R<MdmSupplier> changeStatus(@PathVariable String id,
                                       @RequestParam String toStatus,
                                       @RequestParam(required = false) String reason) {
        return R.ok(service.changeStatus(id, toStatus, reason));
    }

    /** 证照核验解除（CERT_EXPIRED → QUALIFIED） */
    @PutMapping("/{id}/certs-verify")
    public R<MdmSupplier> verifyCerts(@PathVariable String id,
                                      @RequestParam(required = false) String reason) {
        return R.ok(service.verifyCerts(id, reason));
    }

    @GetMapping("/{id}/certs")
    public R<List<MdmSupplierCert>> certs(@PathVariable String id) {
        return R.ok(service.certs(id));
    }

    @PostMapping("/{id}/certs")
    public R<MdmSupplierCert> saveCert(@PathVariable String id, @RequestBody MdmSupplierCert cert) {
        cert.setSupplierId(id);
        return R.ok(service.saveCert(cert));
    }

    @PutMapping("/certs/{certId}")
    public R<MdmSupplierCert> updateCert(@PathVariable String certId,
                                         @RequestBody MdmSupplierCert cert) {
        cert.setId(certId);
        return R.ok(service.saveCert(cert));
    }

    @DeleteMapping("/certs/{certId}")
    public R<Void> deleteCert(@PathVariable String certId) {
        service.deleteCert(certId);
        return R.ok();
    }

    @GetMapping("/{id}/impact")
    public R<Map<String, Object>> impact(@PathVariable String id) {
        return R.ok(service.impact(id));
    }

    /** 合格供应商下拉（options 前置懒巡检，仅 QUALIFIED） */
    @GetMapping("/options")
    public R<List<Map<String, String>>> options() {
        return R.ok(service.options());
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmSupplierVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }
}
