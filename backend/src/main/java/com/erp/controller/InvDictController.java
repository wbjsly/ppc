package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.mdm.MdmItemDict;
import com.erp.service.inv.InvDictService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仓库属性字典（4.1.3，spec warehouse-attribute-config）。
 * 五类白名单：WAREHOUSE_TYPE / BIN_TYPE / TEMP_LEVEL / HAZARD_LEVEL / CLEAN_LEVEL。
 * 权限：ROLE_WAREHOUSE,ROLE_ADMIN（服务层二次校验，design D5 权限面与 MDM 分离）。
 */
@Tag(name = "仓库属性字典")
@RestController
@RequestMapping("/api/inv/dicts")
public class InvDictController {

    private final InvDictService service;

    public InvDictController(InvDictService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "字典条目查询", description = "按类型与状态查询（类型限五类白名单）")
    public R<List<MdmItemDict>> list(@RequestParam String dictType,
                                     @RequestParam(required = false) String status) {
        return R.ok(service.list(dictType, status));
    }

    @GetMapping("/active")
    @Operation(summary = "启用条目（下拉）", description = "停用条目不出现，既有引用不受影响")
    public R<List<MdmItemDict>> active(@RequestParam String dictType) {
        return R.ok(service.listActive(dictType));
    }

    @PostMapping
    @Operation(summary = "新增条目", description = "类型白名单 422、类型内编码唯一 409")
    public R<MdmItemDict> create(@RequestBody MdmItemDict item) {
        return R.ok(service.create(item));
    }

    @PutMapping
    @Operation(summary = "变更条目", description = "编码创建后锁定（422），名称与排序可改")
    public R<MdmItemDict> update(@RequestBody MdmItemDict item) {
        return R.ok(service.update(item));
    }

    @PutMapping("/{id}/enable")
    @Operation(summary = "启用条目")
    public R<Void> enable(@PathVariable String id) {
        service.enable(id);
        return R.ok();
    }

    @PutMapping("/{id}/disable")
    @Operation(summary = "停用条目", description = "退出新数据可选范围，既有引用不变")
    public R<Void> disable(@PathVariable String id) {
        service.disable(id);
        return R.ok();
    }
}
