package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.entity.mdm.MdmTaxCodeVersion;
import com.erp.service.mdm.MdmTaxCodeService;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/** 税码维护（税码管理 1.6.1，/api/mdm/tax-codes） */
@RestController
@RequestMapping("/api/mdm/tax-codes")
public class MdmTaxCodeController {

    private final MdmTaxCodeService service;

    public MdmTaxCodeController(MdmTaxCodeService service) {
        this.service = service;
    }

    /** 分页（含 lifecycle 计算态筛选） */
    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String scope,
                                       @RequestParam(required = false) String calcType,
                                       @RequestParam(required = false) String lifecycle) {
        var page = service.page(current, size, keyword, scope, calcType, lifecycle);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @GetMapping("/{id}")
    public R<MdmTaxCode> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    /** 创建（BR-4.1-16 必填 + BR-4.1-17 税码序列区间校验） */
    @PostMapping
    public R<MdmTaxCode> create(@RequestBody MdmTaxCode tax) {
        return R.ok(service.create(tax));
    }

    /** 变更（编号锁定 C-4.1-01 + 历史 append-only + 重跑区间校验） */
    @PutMapping
    public R<MdmTaxCode> update(@RequestBody MdmTaxCode tax) {
        return R.ok(service.update(tax));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }

    /** 按日期试算（4.14 预铺：命中当时生效记录 / 缺失明示 reasons） */
    @GetMapping("/trial")
    public R<Map<String, Object>> trial(@RequestParam(required = false) String taxCode,
                                        @RequestParam(required = false) String date) {
        // LocalDate @RequestParam 依赖全局转换器（本项目无）→ 手动解析 yyyy-MM-dd
        LocalDate d = null;
        if (date != null && !date.isBlank()) {
            try {
                d = LocalDate.parse(date);
            } catch (java.time.format.DateTimeParseException e) {
                throw new com.erp.common.ServiceException(422, "日期格式须为 yyyy-MM-dd");
            }
        }
        return R.ok(service.trial(taxCode, d));
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmTaxCodeVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }
}
