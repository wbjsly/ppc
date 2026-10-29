package com.erp.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.erp.common.R;
import com.erp.entity.system.SysRole;
import com.erp.service.SysRoleService;
import io.swagger.v3.oas.annotations.Operation;
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

/**
 * 角色管理台（add-role-permission-console spec：角色全生命周期管理与删除护栏）。
 * 写操作限 ADMIN（SecurityConfig 细规则）。
 */
@RestController
@RequestMapping("/api/system/roles")
public class SysRoleController {

    private final SysRoleService roleService;

    public SysRoleController(SysRoleService roleService) {
        this.roleService = roleService;
    }

    /** 全部启用角色（菜单 PERM 下拉选项，既有消费方不变） */
    @GetMapping
    public R<List<SysRole>> getAll() {
        return R.ok(roleService.listAll());
    }

    @Operation(summary = "角色分页（keyword 模糊匹配编码/名称，含停用）")
    @GetMapping("/page")
    public R<IPage<SysRole>> page(@RequestParam(defaultValue = "1") long current,
                                  @RequestParam(defaultValue = "20") long size,
                                  @RequestParam(required = false) String keyword) {
        return R.ok(roleService.page(current, size, keyword));
    }

    @Operation(summary = "新建角色（编码唯一；初始无任何菜单可见性，经权限矩阵授予）")
    @PostMapping
    public R<SysRole> create(@RequestBody SysRole body) {
        return R.ok(roleService.create(body.getRoleCode(), body.getRoleName(), body.getSortOrder()));
    }

    @Operation(summary = "改名 / 排序 / 启停（status 0/1；ROLE_ADMIN 禁停用）")
    @PutMapping("/{id}")
    public R<SysRole> update(@PathVariable String id, @RequestBody SysRole body) {
        return R.ok(roleService.update(id, body.getRoleName(), body.getSortOrder(), body.getStatus()));
    }

    @Operation(summary = "删除角色（被用户绑定/菜单 PERM/矩阵覆盖引用时 422）")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        roleService.delete(id);
        return R.ok(null);
    }
}
