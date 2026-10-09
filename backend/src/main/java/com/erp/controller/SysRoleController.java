package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.system.SysRole;
import com.erp.service.SysRoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/system/roles")
public class SysRoleController {

    private final SysRoleService roleService;

    public SysRoleController(SysRoleService roleService) {
        this.roleService = roleService;
    }

    /** 全部启用角色（菜单 PERM 下拉选项） */
    @GetMapping
    public R<List<SysRole>> getAll() {
        return R.ok(roleService.listAll());
    }
}
