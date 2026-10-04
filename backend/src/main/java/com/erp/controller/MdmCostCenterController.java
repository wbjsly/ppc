package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmCostCenter;
import com.erp.entity.mdm.MdmCostCenterVersion;
import com.erp.service.mdm.MdmCostCenterService;
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
@RequestMapping("/api/mdm/cost-centers")
public class MdmCostCenterController {

    private final MdmCostCenterService service;

    public MdmCostCenterController(MdmCostCenterService service) {
        this.service = service;
    }

    /** 树形查询（legalEntityId/keyword/status 可空） */
    @GetMapping
    public R<List<MdmCostCenter>> tree(@RequestParam(required = false) String legalEntityId,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String status) {
        return R.ok(service.tree(legalEntityId, keyword, status));
    }

    @GetMapping("/{id}")
    public R<MdmCostCenter> getOne(@PathVariable String id) {
        List<MdmCostCenter> roots = service.tree(null, null, null);
        return R.ok(findById(roots, id));
    }

    @PostMapping
    public R<MdmCostCenter> create(@RequestBody MdmCostCenter center) {
        return R.ok(service.create(center));
    }

    @PutMapping
    public R<MdmCostCenter> update(@RequestBody MdmCostCenter center) {
        return R.ok(service.update(center));
    }

    @PutMapping("/{id}/disable")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }

    @PutMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable String id) {
        service.setDefault(id);
        return R.ok();
    }

    /** 下游引用选项（按主体过滤） */
    @GetMapping("/options")
    public R<List<Map<String, String>>> options(@RequestParam(required = false) String legalEntityId) {
        return R.ok(service.options(legalEntityId));
    }

    @GetMapping("/{id}/versions")
    public R<List<MdmCostCenterVersion>> versions(@PathVariable String id) {
        return R.ok(service.versions(id));
    }

    @GetMapping("/{id}/diff")
    public R<Map<String, Object>> diff(@PathVariable String id,
                                       @RequestParam int from,
                                       @RequestParam int to) {
        return R.ok(service.diff(id, from, to));
    }

    private MdmCostCenter findById(List<MdmCostCenter> nodes, String id) {
        for (MdmCostCenter node : nodes) {
            if (node.getId().equals(id)) {
                return node;
            }
            if (node.getChildren() != null) {
                MdmCostCenter found = findById(node.getChildren(), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
