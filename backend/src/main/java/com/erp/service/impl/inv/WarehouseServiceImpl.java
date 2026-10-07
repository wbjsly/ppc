package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvWarehouseDao;
import com.erp.entity.inv.InvWarehouse;
import com.erp.service.inv.WarehouseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 仓库档案（spec warehouse-master）：
 * 编码 WH-NNNN 系统生成且创建后不可改；状态 1 启用 / 0 停用，停用后不被新发货单与新预留选用。
 */
@Slf4j
@Service
public class WarehouseServiceImpl implements WarehouseService {

    private static final String ST_ENABLED = "1";
    private static final String ST_DISABLED = "0";
    private static final String CODE_PREFIX = "WH-";

    private final InvWarehouseDao warehouseDao;

    public WarehouseServiceImpl(InvWarehouseDao warehouseDao) {
        this.warehouseDao = warehouseDao;
    }

    @Override
    public List<InvWarehouse> list(String keyword, String status) {
        LambdaQueryWrapper<InvWarehouse> qw = new LambdaQueryWrapper<InvWarehouse>()
                .orderByAsc(InvWarehouse::getWhCode);
        if (status != null && !status.isEmpty()) {
            qw.eq(InvWarehouse::getStatus, status);
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim();
            qw.and(w -> w.like(InvWarehouse::getWhCode, k).or().like(InvWarehouse::getWhName, k));
        }
        return warehouseDao.selectList(qw);
    }

    @Override
    public InvWarehouse get(String id) {
        InvWarehouse wh = warehouseDao.selectById(id);
        if (wh == null) {
            throw new ServiceException(404, "仓库不存在：" + id);
        }
        return wh;
    }

    @Override
    @Transactional
    public InvWarehouse create(InvWarehouse warehouse) {
        requireRole("维护仓库档案", "ROLE_WAREHOUSE");
        if (warehouse == null || isBlank(warehouse.getWhName())) {
            throw new ServiceException(422, "仓库名称必填");
        }
        InvWarehouse wh = new InvWarehouse();
        wh.setWhName(warehouse.getWhName().trim());
        wh.setOrgUnit(warehouse.getOrgUnit());
        wh.setCapacityDesc(warehouse.getCapacityDesc());
        wh.setRemark(warehouse.getRemark());
        wh.setStatus(ST_ENABLED);
        wh.setWhCode(nextCode());
        try {
            warehouseDao.insert(wh);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "仓库编码冲突，请重试：" + wh.getWhCode());
        }
        return wh;
    }

    @Override
    @Transactional
    public InvWarehouse update(InvWarehouse warehouse) {
        requireRole("维护仓库档案", "ROLE_WAREHOUSE");
        if (warehouse == null || isBlank(warehouse.getId())) {
            throw new ServiceException(422, "仓库 ID 必填");
        }
        InvWarehouse stored = get(warehouse.getId());
        // 编码创建后不可修改（spec warehouse-master）
        if (!isBlank(warehouse.getWhCode()) && !stored.getWhCode().equals(warehouse.getWhCode())) {
            throw new ServiceException(422, "仓库编码创建后不可修改：" + stored.getWhCode());
        }
        if (isBlank(warehouse.getWhName())) {
            throw new ServiceException(422, "仓库名称必填");
        }
        stored.setWhName(warehouse.getWhName().trim());
        stored.setOrgUnit(warehouse.getOrgUnit());
        stored.setCapacityDesc(warehouse.getCapacityDesc());
        stored.setRemark(warehouse.getRemark());
        warehouseDao.updateById(stored);
        return stored;
    }

    @Override
    @Transactional
    public void enable(String id) {
        requireRole("维护仓库档案", "ROLE_WAREHOUSE");
        InvWarehouse wh = get(id);
        if (ST_ENABLED.equals(wh.getStatus())) {
            return;
        }
        wh.setStatus(ST_ENABLED);
        warehouseDao.updateById(wh);
    }

    @Override
    @Transactional
    public void disable(String id) {
        requireRole("维护仓库档案", "ROLE_WAREHOUSE");
        InvWarehouse wh = get(id);
        if (ST_DISABLED.equals(wh.getStatus())) {
            return;
        }
        if (InvWarehouse.DEFAULT_WH_CODE.equals(wh.getWhCode())) {
            throw new ServiceException(422, "默认仓不可停用（库存仓库维度改造的回填目标仓）");
        }
        wh.setStatus(ST_DISABLED);
        warehouseDao.updateById(wh);
    }

    @Override
    public List<InvWarehouse> listEnabled() {
        return warehouseDao.selectEnabled();
    }

    /** WH- + 4 位流水，取既有流水最大值 + 1（WH-MAIN 等非流水编码忽略） */
    private String nextCode() {
        int max = 0;
        for (String code : warehouseDao.selectCodedWhs(CODE_PREFIX)) {
            if (code == null || !code.startsWith(CODE_PREFIX)) {
                continue;
            }
            String tail = code.substring(CODE_PREFIX.length());
            if (tail.length() != 4) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(tail));
            } catch (NumberFormatException ignore) {
                // 非流水编码（如 WH-MAIN）跳过
            }
        }
        return CODE_PREFIX + String.format("%04d", max + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new java.util.ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权限" + action);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
