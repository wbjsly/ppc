package com.erp.service;

import com.erp.entity.system.SysMenu;

import java.util.List;

/**
 * 菜单管理业务能力契约。
 */
public interface SysMenuService {

    /** 当前用户可见菜单：admin 看全部启用菜单，其他角色按 PERM 匹配；空 PERM 所有登录用户可见 */
    List<SysMenu> getUserMenus(List<String> roles);

    /** 全量查询（管理页面） */
    List<SysMenu> listAll();

    /** 单条查询 */
    SysMenu getById(String id);

    /** 新增（校验上级菜单存在且不成环） */
    SysMenu create(SysMenu menu);

    /** 更新（校验上级菜单不成环） */
    SysMenu update(SysMenu menu);

    /** 逻辑删除（存在子菜单时拒绝） */
    void delete(String id);
}
