package com.erp.service;

import com.erp.entity.system.SysRole;

import java.util.List;

/**
 * 角色查询业务能力契约（菜单 PERM 下拉选项数据源）。
 */
public interface SysRoleService {

    /** 全部启用角色，按 SORT_ORDER 排序 */
    List<SysRole> listAll();
}
