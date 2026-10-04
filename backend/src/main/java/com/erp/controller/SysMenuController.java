package com.erp.controller;

import com.erp.common.R;
import com.erp.entity.system.SysMenu;
import com.erp.service.SysMenuService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/system/menus")
public class SysMenuController {

    private final SysMenuService menuService;

    public SysMenuController(SysMenuService menuService) {
        this.menuService = menuService;
    }

    /** 获取当前用户可见的菜单 */
    @GetMapping("/user")
    public R<List<SysMenu>> getUserMenus() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        List<String> roles = auth == null ? List.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        return R.ok(menuService.getUserMenus(roles));
    }

    /** 全量查询（管理页面） */
    @GetMapping
    public R<List<SysMenu>> getAll() {
        return R.ok(menuService.listAll());
    }

    /** 单条查询 */
    @GetMapping("/{id}")
    public R<SysMenu> getOne(@PathVariable String id) {
        return R.ok(menuService.getById(id));
    }

    /** 新增 */
    @PostMapping
    public R<SysMenu> create(@RequestBody SysMenu menu) {
        return R.ok(menuService.create(menu));
    }

    /** 更新 */
    @PutMapping
    public R<SysMenu> update(@RequestBody SysMenu menu) {
        return R.ok(menuService.update(menu));
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable String id) {
        menuService.delete(id);
        return R.ok();
    }
}
