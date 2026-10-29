package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.MenuPermOverrideDao;
import com.erp.dao.system.SysMenuDao;
import com.erp.entity.system.MenuPermOverride;
import com.erp.entity.system.SysMenu;
import com.erp.service.SysMenuService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class SysMenuServiceImpl implements SysMenuService {

    private final SysMenuDao menuDao;
    private final MenuPermOverrideDao overrideDao;

    public SysMenuServiceImpl(SysMenuDao menuDao, MenuPermOverrideDao overrideDao) {
        this.menuDao = menuDao;
        this.overrideDao = overrideDao;
    }

    @Override
    public List<SysMenu> getUserMenus(List<String> roles) {
        List<SysMenu> all = menuDao.selectList(
                new LambdaQueryWrapper<SysMenu>()
                        .eq(SysMenu::getStatus, "1")
                        .orderByAsc(SysMenu::getSortOrder)
        );
        if (roles == null || roles.isEmpty()) {
            return Collections.emptyList();
        }
        boolean isAdmin = roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r));
        if (isAdmin) {
            return all;
        }
        Set<String> roleSet = roles.stream()
                .map(r -> r.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // override 合并（add-role-permission-console design D2）：读时单点合并「基线 ∪ override」
        // override 为绝对指令：ADD=强制包含 / REMOVE=强制排除；仅存差异格，行数=实际改动数
        Map<String, Map<String, String>> ovr = new HashMap<>();
        for (MenuPermOverride row : overrideDao.selectByRoles(new ArrayList<>(roleSet))) {
            ovr.computeIfAbsent(row.getMenuId(), k -> new HashMap<>())
                    .put(row.getRoleCode().toUpperCase(Locale.ROOT), row.getAction());
        }

        return all.stream()
                .filter(m -> {
                    Map<String, String> cell = ovr.get(m.getId());
                    for (String role : roleSet) {
                        String action = cell == null ? null : cell.get(role);
                        if (MenuPermOverride.ACT_ADD.equals(action)) {
                            return true;
                        }
                        if (MenuPermOverride.ACT_REMOVE.equals(action)) {
                            continue; // 该角色被强制排除，看其余角色
                        }
                        if (baselineHas(m, role)) {
                            return true;
                        }
                    }
                    return false;
                })
                .collect(Collectors.toList());
    }

    /** 基线成员判定（空 PERM = 全员可见，沿既有语义） */
    private boolean baselineHas(SysMenu m, String roleUpper) {
        if (m.getPerm() == null || m.getPerm().isEmpty()) {
            return true;
        }
        return Arrays.stream(m.getPerm().split(","))
                .map(String::trim)
                .map(r -> r.toUpperCase(Locale.ROOT))
                .anyMatch(roleUpper::equals);
    }

    @Override
    public List<SysMenu> listAll() {
        return menuDao.selectList(
                new LambdaQueryWrapper<SysMenu>()
                        .orderByAsc(SysMenu::getSortOrder)
        );
    }

    @Override
    public SysMenu getById(String id) {
        return menuDao.selectById(id);
    }

    @Override
    @Transactional
    public SysMenu create(SysMenu menu) {
        menu.setParentId(menu.getParentId() == null ? "" : menu.getParentId());
        if (!menu.getParentId().isEmpty()) {
            requireExists(menu.getParentId());
            requireDepthWithinLimit(menu.getParentId());
        }
        menuDao.insert(menu);
        return menu;
    }

    @Override
    @Transactional
    public SysMenu update(SysMenu menu) {
        if (menu.getId() == null || menuDao.selectById(menu.getId()) == null) {
            throw new ServiceException(404, "菜单不存在");
        }
        menu.setParentId(menu.getParentId() == null ? "" : menu.getParentId());
        if (menu.getId().equals(menu.getParentId())) {
            throw new ServiceException(400, "上级菜单不能是自身");
        }
        if (!menu.getParentId().isEmpty()) {
            requireExists(menu.getParentId());
            requireDepthWithinLimit(menu.getParentId());
            if (isDescendant(menu.getParentId(), menu.getId())) {
                throw new ServiceException(400, "上级菜单不能是自身的子菜单");
            }
        }
        menuDao.updateById(menu);
        return menu;
    }

    @Override
    @Transactional
    public void delete(String id) {
        Long children = menuDao.selectCount(
                new LambdaQueryWrapper<SysMenu>().eq(SysMenu::getParentId, id));
        if (children != null && children > 0) {
            throw new ServiceException(400, "存在子菜单，无法删除");
        }
        menuDao.deleteById(id);
    }

    private void requireExists(String parentId) {
        if (menuDao.selectById(parentId) == null) {
            throw new ServiceException(404, "上级菜单不存在");
        }
    }

    /** 08 文档菜单树最多三级；上级若已是第三级，则其子级会成为第四级，拒绝 */
    private void requireDepthWithinLimit(String parentId) {
        if (depthOf(parentId) >= 3) {
            throw new ServiceException(400, "菜单最多三级（对齐 08 菜单清单层级）");
        }
    }

    /** 节点深度：顶级=1，逐级沿父链上溯计数 */
    private int depthOf(String id) {
        int depth = 0;
        String current = id;
        Set<String> visited = new HashSet<>();
        while (current != null && !current.isEmpty() && visited.add(current)) {
            SysMenu m = menuDao.selectById(current);
            if (m == null) {
                break;
            }
            depth++;
            current = m.getParentId();
        }
        return depth;
    }

    /** 从 candidateId 沿父链向上追溯，若经过 fromId 则说明 candidate 是 from 的后代 */
    private boolean isDescendant(String candidateId, String fromId) {
        String current = candidateId;
        Set<String> visited = new HashSet<>();
        while (current != null && !current.isEmpty() && visited.add(current)) {
            if (current.equals(fromId)) {
                return true;
            }
            SysMenu m = menuDao.selectById(current);
            current = m == null ? null : m.getParentId();
        }
        return false;
    }
}
