package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmOrgUnit;
import com.erp.entity.mdm.MdmOrgUnitVersion;
import com.erp.service.mdm.MdmOrgUnitService;
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

@RestController
@RequestMapping("/api/mdm/org-units")
public class MdmOrgUnitController {

    private final MdmOrgUnitService service;

    public MdmOrgUnitController(MdmOrgUnitService service) {
        this.service = service;
    }

    /** 树形查询（legalEntityId/ouType/keyword/status 可空） */
    @GetMapping
    public R<List<MdmOrgUnit>> tree(@RequestParam(required = false) String legalEntityId,
                                    @RequestParam(required = false) String ouType,
                                    @RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) String status) {
        return R.ok(service.tree(legalEntityId, ouType, keyword, status));
    }

    @GetMapping("/{id}")
    public R<MdmOrgUnit> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<MdmOrgUnit> create(@RequestBody MdmOrgUnit unit) {
        return R.ok(service.create(unit));
    }

    @PutMapping
    public R<MdmOrgUnit> update(@RequestBody MdmOrgUnit unit) {
        return R.ok(service.update(unit));
    }

    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }

    /** C-0-08 下游引用选项（按主体过滤，仅启用，含 level/type） */
    @GetMapping("/options")
    public R<List<Map<String, String>>> options(@RequestParam(required = false) String legalEntityId) {
        return R.ok(service.options(legalEntityId));
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmOrgUnitVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }
}
