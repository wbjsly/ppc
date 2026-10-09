package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.entity.mdm.MdmLegalEntityVersion;
import com.erp.service.mdm.MdmLegalEntityService;
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
@RequestMapping("/api/mdm/legal-entities")
public class MdmLegalEntityController {

    private final MdmLegalEntityService service;

    public MdmLegalEntityController(MdmLegalEntityService service) {
        this.service = service;
    }

    @GetMapping
    public R<Page<MdmLegalEntity>> page(@RequestParam(defaultValue = "1") long current,
                                        @RequestParam(defaultValue = "10") long size,
                                        @RequestParam(required = false) String keyword,
                                        @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, status));
    }

    @GetMapping("/{id}")
    public R<MdmLegalEntity> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<MdmLegalEntity> create(@RequestBody MdmLegalEntity entity) {
        return R.ok(service.create(entity));
    }

    @PutMapping
    public R<MdmLegalEntity> update(@RequestBody MdmLegalEntity entity) {
        return R.ok(service.update(entity));
    }

    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }

    @GetMapping("/options")
    public R<List<Map<String, String>>> options() {
        return R.ok(service.options());
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmLegalEntityVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }
}
