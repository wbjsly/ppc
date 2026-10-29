package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.system.MenuPermOverrideDao;
import com.erp.dao.system.SysRoleDao;
import com.erp.entity.system.MenuPermOverride;
import com.erp.entity.system.SysRole;
import com.erp.service.SysRoleService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class SysRoleServiceImpl implements SysRoleService {

    /** 系统超管角色：禁停用、禁删除（护栏兜底——即使引用计数为 0） */
    private static final String SUPER_ROLE = "ROLE_ADMIN";

    private final SysRoleDao roleDao;
    private final MenuPermOverrideDao overrideDao;
    private final JdbcTemplate jdbc;

    public SysRoleServiceImpl(SysRoleDao roleDao, MenuPermOverrideDao overrideDao,
                              JdbcTemplate jdbc) {
        this.roleDao = roleDao;
        this.overrideDao = overrideDao;
        this.jdbc = jdbc;
    }

    @Override
    public List<SysRole> listAll() {
        return roleDao.selectList(
                new LambdaQueryWrapper<SysRole>()
                        .eq(SysRole::getStatus, "1")
                        .orderByAsc(SysRole::getSortOrder)
        );
    }

    @Override
    public IPage<SysRole> page(long current, long size, String keyword) {
        return roleDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<SysRole>()
                        .and(StringUtils.hasText(keyword), w -> w
                                .like(SysRole::getRoleCode, keyword)
                                .or().like(SysRole::getRoleName, keyword))
                        .orderByAsc(SysRole::getSortOrder));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysRole create(String roleCode, String roleName, Integer sortOrder) {
        if (!StringUtils.hasText(roleCode) || !roleCode.matches("ROLE_[A-Z0-9_]+")) {
            throw new ServiceException(422, "角色编码须为 ROLE_ 大写字母/数字/下划线");
        }
        if (!StringUtils.hasText(roleName)) {
            throw new ServiceException(422, "角色名称不能为空");
        }
        // UK_SYS_ROLE_CODE 不区分逻辑删除 → 原始计数（含已删行）判重，避免幽灵键
        Integer occupied = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_role WHERE ROLE_CODE = ?", Integer.class, roleCode);
        if (occupied != null && occupied > 0) {
            throw new ServiceException(422, "角色编码已存在：" + roleCode);
        }
        SysRole r = new SysRole();
        r.setRoleCode(roleCode.trim());
        r.setRoleName(roleName.trim());
        r.setSortOrder(sortOrder == null ? 0 : sortOrder);
        r.setStatus("1");
        roleDao.insert(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SysRole update(String id, String roleName, Integer sortOrder, String status) {
        SysRole r = require(id);
        if (StringUtils.hasText(roleName)) {
            r.setRoleName(roleName.trim());
        }
        if (sortOrder != null) {
            r.setSortOrder(sortOrder);
        }
        if (StringUtils.hasText(status)) {
            if (!"0".equals(status) && !"1".equals(status)) {
                throw new ServiceException(422, "状态仅支持 0/1");
            }
            if (SUPER_ROLE.equals(r.getRoleCode()) && "0".equals(status)) {
                throw new ServiceException(422, "系统超管角色禁止停用");
            }
            r.setStatus(status);
        }
        r.setUpdateDate(LocalDateTime.now());
        roleDao.updateById(r);
        return r;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SysRole r = require(id);
        if (SUPER_ROLE.equals(r.getRoleCode())) {
            throw new ServiceException(422, "系统超管角色禁止删除");
        }
        // 护栏三查（spec：用户绑定 / 菜单 PERM 基线 / override 覆盖层）
        Integer bound = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_user_role WHERE ROLE_ID = ?", Integer.class, id);
        if (bound != null && bound > 0) {
            throw new ServiceException(422, "角色被 " + bound + " 个用户绑定，先解除绑定再删除");
        }
        Integer inPerm = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_admin_menu WHERE DEL_FLAG = '0' AND PERM IS NOT NULL "
                        + "AND CONCAT(',', PERM, ',') LIKE CONCAT('%,', ?, ',%')",
                Integer.class, r.getRoleCode());
        if (inPerm != null && inPerm > 0) {
            throw new ServiceException(422,
                    "角色被 " + inPerm + " 个菜单 PERM 基线引用，请先在权限矩阵取消勾选");
        }
        Long inOverride = overrideDao.selectCount(
                new LambdaQueryWrapper<MenuPermOverride>()
                        .eq(MenuPermOverride::getRoleCode, r.getRoleCode()));
        if (inOverride != null && inOverride > 0) {
            throw new ServiceException(422,
                    "角色存在 " + inOverride + " 条权限矩阵覆盖记录，请先清除覆盖");
        }
        roleDao.deleteById(id);
    }

    private SysRole require(String id) {
        SysRole r = roleDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "角色不存在：" + id);
        }
        return r;
    }
}
