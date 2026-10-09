package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvWarehouseDao;
import com.erp.dao.mdm.MdmItemDictDao;
import com.erp.dao.mdm.MdmOrgUnitDao;
import com.erp.entity.inv.InvWarehouse;
import com.erp.entity.mdm.MdmItemDict;
import com.erp.entity.mdm.MdmOrgUnit;
import com.erp.service.inv.WarehouseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 仓库档案（spec warehouse-master）：
 * 编码 WH-NNNN 系统生成且创建后不可改；状态 1 启用 / 0 停用，停用后不被新发货单与新预留选用。
 * 4.1.1 补差距（change add-warehouse-zone-management）：仓库类型走 WAREHOUSE_TYPE 字典校验，
 * 所属组织存组织主数据 ID 并做存在性/启用校验（DC-02，NULL 放行兼容存量）。
 */
@Slf4j
@Service
public class WarehouseServiceImpl implements WarehouseService {

    private static final String ST_ENABLED = "1";
    private static final String ST_DISABLED = "0";
    private static final String CODE_PREFIX = "WH-";

    private final InvWarehouseDao warehouseDao;
    private final MdmItemDictDao dictDao;
    private final MdmOrgUnitDao orgUnitDao;

    public WarehouseServiceImpl(InvWarehouseDao warehouseDao,
                                MdmItemDictDao dictDao,
                                MdmOrgUnitDao orgUnitDao) {
        this.warehouseDao = warehouseDao;
        this.dictDao = dictDao;
        this.orgUnitDao = orgUnitDao;
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
        requireValidWhType(warehouse.getWhType());
        requireValidOrgUnit(warehouse.getOrgUnit());
        InvWarehouse wh = new InvWarehouse();
        wh.setWhName(warehouse.getWhName().trim());
        wh.setOrgUnit(warehouse.getOrgUnit());
        wh.setWhType(warehouse.getWhType());
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
        requireValidWhType(warehouse.getWhType());
        requireValidOrgUnit(warehouse.getOrgUnit());
        stored.setWhName(warehouse.getWhName().trim());
        stored.setOrgUnit(warehouse.getOrgUnit());
        stored.setWhType(warehouse.getWhType());
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

    /**
     * 仓库类型字典校验（spec warehouse-master MODIFIED：取值 MUST 命中 WAREHOUSE_TYPE 启用条目）。
     * 为空视为未分类放行（存量与迁移回填不受影响；新建时由前端下拉保证必填）。
     */
    private void requireValidWhType(String whType) {
        if (isBlank(whType)) {
            return;
        }
        boolean active = dictDao.selectByType("WAREHOUSE_TYPE").stream()
                .anyMatch(d -> whType.equals(d.getDictCode()));
        if (!active) {
            throw new ServiceException(422, "仓库类型不在启用字典内：" + whType);
        }
    }

    /**
     * 所属组织引用校验（DC-02 引用完整性，L1）：非空时组织必须存在且启用；NULL 放行（存量两仓未关联）。
     */
    private void requireValidOrgUnit(String orgUnitId) {
        if (isBlank(orgUnitId)) {
            return;
        }
        MdmOrgUnit org = orgUnitDao.selectById(orgUnitId);
        if (org == null) {
            throw new ServiceException(422, "所属组织不存在：" + orgUnitId);
        }
        if (!"1".equals(org.getStatus())) {
            throw new ServiceException(422, "所属组织已停用：" + org.getOuName());
        }
    }
}
