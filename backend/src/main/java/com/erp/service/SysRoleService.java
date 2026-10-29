package com.erp.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.erp.entity.system.SysRole;

import java.util.List;

/**
 * 角色管理业务能力契约（add-role-permission-console spec：角色全生命周期管理与删除护栏）。
 */
public interface SysRoleService {

    /** 全部启用角色，按 SORT_ORDER 排序（菜单 PERM 下拉选项，既有消费方不变） */
    List<SysRole> listAll();

    /** 全部角色分页（含停用；keyword 模糊匹配编码/名称） */
    IPage<SysRole> page(long current, long size, String keyword);

    /** 新建角色：编码唯一（含已删行占位，UK_SYS_ROLE_CODE 不区分逻辑删除）；初始无任何菜单可见性 */
    SysRole create(String roleCode, String roleName, Integer sortOrder);

    /** 改名 / 排序 / 启停（status ∈ 0/1）；ROLE_ADMIN 禁停用 */
    SysRole update(String id, String roleName, Integer sortOrder, String status);

    /** 逻辑删除；护栏：被用户绑定 / 菜单 PERM 基线引用 / override 引用 → 422 指明来源 */
    void delete(String id);
}
