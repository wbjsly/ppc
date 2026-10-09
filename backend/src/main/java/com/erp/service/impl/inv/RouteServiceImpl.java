package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvRouteDao;
import com.erp.entity.inv.InvRoute;
import com.erp.service.inv.RouteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配送线路（4.8.1，spec wave-management；design D7 权限面）：
 * 编码唯一 409、停用退役不物理删除（C-0-05）、写限 ADMIN/WAREHOUSE。
 */
@Slf4j
@Service
public class RouteServiceImpl implements RouteService {

    private final InvRouteDao routeDao;

    public RouteServiceImpl(InvRouteDao routeDao) {
        this.routeDao = routeDao;
    }

    @Override
    public Map<String, Object> page(String keyword, String status, long current, long size) {
        Page<InvRoute> p = routeDao.selectPage(new Page<>(Math.max(current, 1), Math.max(size, 1)),
                new LambdaQueryWrapper<InvRoute>()
                        .eq(hasText(status), InvRoute::getStatus, status)
                        .and(hasText(keyword), w -> w
                                .like(InvRoute::getRouteCode, keyword.trim())
                                .or().like(InvRoute::getRouteName, keyword.trim()))
                        .orderByAsc(InvRoute::getRouteCode));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("records", p.getRecords());
        out.put("total", p.getTotal());
        return out;
    }

    @Override
    public List<InvRoute> listActive() {
        return routeDao.selectList(new LambdaQueryWrapper<InvRoute>()
                .eq(InvRoute::getStatus, InvRoute.ST_ACTIVE)
                .orderByAsc(InvRoute::getRouteCode));
    }

    @Override
    @Transactional
    public Map<String, Object> create(InvRoute route) {
        requireWrite("新增配送线路");
        if (route == null || !hasText(route.getRouteCode()) || !hasText(route.getRouteName())) {
            throw new ServiceException(422, "线路编码与名称必填");
        }
        String code = route.getRouteCode().trim();
        Long dup = routeDao.selectCount(new LambdaQueryWrapper<InvRoute>()
                .eq(InvRoute::getRouteCode, code));
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "线路编码已存在：" + code);
        }
        InvRoute r = new InvRoute();
        r.setRouteCode(code);
        r.setRouteName(route.getRouteName().trim());
        r.setStatus(hasText(route.getStatus()) ? route.getStatus() : InvRoute.ST_ACTIVE);
        r.setRemark(route.getRemark());
        routeDao.insert(r);
        log.info("route created: {} {}", r.getRouteCode(), r.getRouteName());
        return detail(r.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, InvRoute route) {
        requireWrite("编辑配送线路");
        InvRoute db = require(id);
        if (route == null || !hasText(route.getRouteName())) {
            throw new ServiceException(422, "线路名称必填");
        }
        // 编码不可改（C-0-05 稳定标识）；名称/状态/备注可改
        db.setRouteName(route.getRouteName().trim());
        if (hasText(route.getStatus())) {
            if (!InvRoute.ST_ACTIVE.equals(route.getStatus())
                    && !InvRoute.ST_INACTIVE.equals(route.getStatus())) {
                throw new ServiceException(422, "非法线路状态：" + route.getStatus());
            }
            db.setStatus(route.getStatus());
        }
        db.setRemark(route.getRemark());
        lockUpdate(db);
        return detail(id);
    }

    @Override
    @Transactional
    public Map<String, Object> changeStatus(String id, String status) {
        requireWrite("变更配送线路状态");
        InvRoute db = require(id);
        if (!InvRoute.ST_ACTIVE.equals(status) && !InvRoute.ST_INACTIVE.equals(status)) {
            throw new ServiceException(422, "非法线路状态：" + status);
        }
        db.setStatus(status);
        lockUpdate(db);
        log.info("route {} status -> {}", db.getRouteCode(), status);
        return detail(id);
    }

    // ---------- 内部 ----------

    private Map<String, Object> detail(String id) {
        InvRoute r = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("routeCode", r.getRouteCode());
        out.put("routeName", r.getRouteName());
        out.put("status", r.getStatus());
        out.put("remark", r.getRemark());
        return out;
    }

    private InvRoute require(String id) {
        InvRoute r = routeDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "配送线路不存在：" + id);
        }
        return r;
    }

    /** 乐观锁更新（@Version 实体必须带锁行读出的 verNo，否则静默 no-op） */
    private void lockUpdate(InvRoute db) {
        if (routeDao.updateById(db) == 0) {
            throw new ServiceException(409, "线路更新冲突，请刷新后重试");
        }
    }

    private void requireWrite(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        if (roles.stream().anyMatch(r -> "ROLE_WAREHOUSE".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要仓库主管或管理员角色");
    }

    private static boolean hasText(String v) {
        return v != null && !v.trim().isEmpty();
    }
}
