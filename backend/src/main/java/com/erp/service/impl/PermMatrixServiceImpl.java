package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.MenuPermOverrideDao;
import com.erp.dao.system.SysMenuDao;
import com.erp.dao.system.SysRoleDao;
import com.erp.entity.system.MenuPermOverride;
import com.erp.entity.system.SysMenu;
import com.erp.entity.system.SysRole;
import com.erp.service.PermMatrixService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PermMatrixServiceImpl implements PermMatrixService {

    private final SysMenuDao menuDao;
    private final SysRoleDao roleDao;
    private final MenuPermOverrideDao overrideDao;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Override
    public List<Map<String, Object>> matrixForRole(String roleCode) {
        requireRole(roleCode);
        Map<String, String> ovr = new HashMap<>();
        for (MenuPermOverride row : overrideDao.selectByRole(roleCode)) {
            ovr.put(row.getMenuId(), row.getAction());
        }
        List<SysMenu> menus = menuDao.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSortOrder));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysMenu m : menus) {
            boolean base = baselineHas(m, roleCode);
            String action = ovr.get(m.getId());
            boolean effective;
            String source;
            if (MenuPermOverride.ACT_ADD.equals(action)) {
                effective = true;
                source = "OVERRIDE_ADD";
            } else if (MenuPermOverride.ACT_REMOVE.equals(action)) {
                effective = false;
                source = "OVERRIDE_REMOVE";
            } else {
                effective = base;
                source = "BASE";
            }
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("id", m.getId());
            cell.put("parentId", m.getParentId());
            cell.put("title", m.getTitle());
            cell.put("path", m.getPath());
            cell.put("icon", m.getIcon());
            cell.put("sortOrder", m.getSortOrder());
            cell.put("status", m.getStatus());
            cell.put("perm", m.getPerm());
            cell.put("baseline", base);
            cell.put("effective", effective);
            cell.put("source", source);
            out.add(cell);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setCell(String roleCode, String menuId, boolean checked) {
        requireRole(roleCode);
        SysMenu m = menuDao.selectById(menuId);
        if (m == null) {
            throw new ServiceException(404, "菜单不存在：" + menuId);
        }
        boolean base = baselineHas(m, roleCode);
        MenuPermOverride existing = overrideDao.selectOne(new LambdaQueryWrapper<MenuPermOverride>()
                .eq(MenuPermOverride::getMenuId, menuId)
                .eq(MenuPermOverride::getRoleCode, roleCode)
                .last("LIMIT 1"));

        if (checked) {
            if (existing != null) {
                if (MenuPermOverride.ACT_REMOVE.equals(existing.getAction())) {
                    // 撤销排除 → 恢复基线口径（物理删格——@TableLogic 删会占唯一键成幽灵格）
                    deleteCellPhysically(menuId, roleCode);
                }
                // 已是 ADD → 幂等无操作
            } else if (!base) {
                insertCell(roleCode, menuId, MenuPermOverride.ACT_ADD);
            }
            // base=true 且无覆盖 → 本就可见，无操作（最小差异写入）
        } else {
            if (existing != null) {
                if (MenuPermOverride.ACT_ADD.equals(existing.getAction())) {
                    deleteCellPhysically(menuId, roleCode);
                }
                // 已是 REMOVE → 幂等无操作
            } else if (base) {
                insertCell(roleCode, menuId, MenuPermOverride.ACT_REMOVE);
            }
            // base=false 且无覆盖 → 本就不可见，无操作
        }
        log.info("perm matrix cell set: role={} menu={} checked={}", roleCode, menuId, checked);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void clearRole(String roleCode) {
        requireRole(roleCode);
        // 清除=物理删除（design D1/D2：释放唯一键 MENU_ID+ROLE_CODE，避免软删幽灵格）
        deleteCellsOfRolePhysically(roleCode);
        log.info("perm matrix overrides cleared: role={}", roleCode);
    }

    private void deleteCellPhysically(String menuId, String roleCode) {
        jdbc.update("DELETE FROM erp_admin_menu_perm_override "
                + "WHERE MENU_ID = ? AND ROLE_CODE = ?", menuId, roleCode);
    }

    private void deleteCellsOfRolePhysically(String roleCode) {
        jdbc.update("DELETE FROM erp_admin_menu_perm_override WHERE ROLE_CODE = ?", roleCode);
    }

    private void insertCell(String roleCode, String menuId, String action) {
        MenuPermOverride row = new MenuPermOverride();
        row.setId(UUID.randomUUID().toString().replace("-", ""));
        row.setMenuId(menuId);
        row.setRoleCode(roleCode);
        row.setAction(action);
        overrideDao.insert(row);
    }

    /** 基线成员判定：空 PERM = 全员可见（沿 getUserMenus 既有语义） */
    private boolean baselineHas(SysMenu m, String roleCode) {
        if (m.getPerm() == null || m.getPerm().isEmpty()) {
            return true;
        }
        for (String r : m.getPerm().split(",")) {
            if (r.trim().equalsIgnoreCase(roleCode)) {
                return true;
            }
        }
        return false;
    }

    private void requireRole(String roleCode) {
        if (!StringUtils.hasText(roleCode)) {
            throw new ServiceException(422, "角色编码不能为空");
        }
        SysRole r = roleDao.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode).last("LIMIT 1"));
        if (r == null) {
            throw new ServiceException(404, "角色不存在：" + roleCode);
        }
    }
}
