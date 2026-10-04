package com.erp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.R;
import com.erp.entity.mdm.MdmProfitCenter;
import com.erp.entity.mdm.MdmProfitCenterVersion;
import com.erp.service.mdm.MdmProfitCenterService;
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
@RequestMapping("/api/mdm/profit-centers")
public class MdmProfitCenterController {

    private final MdmProfitCenterService service;

    public MdmProfitCenterController(MdmProfitCenterService service) {
        this.service = service;
    }

    /** 分页查询（keyword/legalEntityId/status 可空） */
    @GetMapping
    public R<Page<MdmProfitCenter>> page(@RequestParam(defaultValue = "1") long current,
                                         @RequestParam(defaultValue = "10") long size,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String legalEntityId,
                                         @RequestParam(required = false) String status) {
        return R.ok(service.page(current, size, keyword, legalEntityId, status));
    }

    @GetMapping("/{id}")
    public R<MdmProfitCenter> getOne(@PathVariable String id) {
        return R.ok(service.getById(id));
    }

    @PostMapping
    public R<MdmProfitCenter> create(@RequestBody MdmProfitCenter center) {
        return R.ok(service.create(center));
    }

    @PutMapping
    public R<MdmProfitCenter> update(@RequestBody MdmProfitCenter center) {
        return R.ok(service.update(center));
    }

    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }

    /** 下游引用选项（按主体过滤，仅启用） */
    @GetMapping("/options")
    public R<List<Map<String, String>>> options(@RequestParam(required = false) String legalEntityId) {
        return R.ok(service.options(legalEntityId));
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmProfitCenterVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }
}
