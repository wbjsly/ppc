package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmTaxPolicy;
import com.erp.service.mdm.MdmTaxPolicyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 政策台账（税码管理 1.6.2，/api/mdm/tax-policies） */
@RestController
@RequestMapping("/api/mdm/tax-policies")
public class MdmTaxPolicyController {

    private final MdmTaxPolicyService service;

    public MdmTaxPolicyController(MdmTaxPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public R<Map<String, Object>> page(@RequestParam(defaultValue = "1") long current,
                                       @RequestParam(defaultValue = "10") long size,
                                       @RequestParam(required = false) String keyword) {
        var page = service.page(current, size, keyword);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("records", page.getRecords());
        result.put("total", page.getTotal());
        return R.ok(result);
    }

    @GetMapping("/{id}")
    public R<MdmTaxPolicy> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<MdmTaxPolicy> create(@RequestBody MdmTaxPolicy policy) {
        return R.ok(service.create(policy));
    }

    @PutMapping
    public R<MdmTaxPolicy> update(@RequestBody MdmTaxPolicy policy) {
        return R.ok(service.update(policy));
    }

    /** 删除：被税码引用 422（引用计数），未引用硬删 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        service.delete(id);
        return R.ok();
    }

    /** 关联回链：该政策关联的税码清单 + 版本流水倒序 */
    @GetMapping("/{id}/tax-codes")
    public R<Map<String, Object>> taxCodes(@PathVariable String id) {
        return R.ok(service.taxCodesOf(id));
    }

    /** 版本快照列表（升序） */
    @GetMapping("/{id}/versions")
    public R<java.util.List<com.erp.entity.mdm.MdmTaxPolicyVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    /** 两版本逐字段对比（仅变更字段） */
    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from, @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }
}
