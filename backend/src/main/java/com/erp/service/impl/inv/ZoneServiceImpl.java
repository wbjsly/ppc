package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBinDao;
import com.erp.dao.inv.InvWarehouseDao;
import com.erp.dao.inv.InvZoneDao;
import com.erp.dao.inv.InvZoneVersionDao;
import com.erp.entity.inv.InvBin;
import com.erp.entity.inv.InvWarehouse;
import com.erp.entity.inv.InvZone;
import com.erp.entity.inv.InvZoneVersion;
import com.erp.service.inv.ZoneService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 仓库区域（4.1.2，spec warehouse-zone-planning，design D1/D3/D7）：
 * 编码仓库内唯一（UK 兜底 + 预检 409）、创建后不可改；停用仓库禁建区域；
 * 变更走 verNo 乐观锁 + 版本快照 N+1；停用不级联仓位。
 */
@Slf4j
@Service
public class ZoneServiceImpl implements ZoneService {

    private static final String ST_ENABLED = "1";
    private static final String ST_DISABLED = "0";

    private final InvZoneDao zoneDao;
    private final InvZoneVersionDao versionDao;
    private final InvBinDao binDao;
    private final InvWarehouseDao warehouseDao;
    private final ObjectMapper objectMapper;

    public ZoneServiceImpl(InvZoneDao zoneDao, InvZoneVersionDao versionDao,
                           InvBinDao binDao, InvWarehouseDao warehouseDao,
                           ObjectMapper objectMapper) {
        this.zoneDao = zoneDao;
        this.versionDao = versionDao;
        this.binDao = binDao;
        this.warehouseDao = warehouseDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<InvZone> list(String whCode, String keyword, String status) {
        LambdaQueryWrapper<InvZone> qw = new LambdaQueryWrapper<InvZone>()
                .eq(!isBlank(whCode), InvZone::getWhCode, whCode)
                .eq(!isBlank(status), InvZone::getStatus, status)
                .and(!isBlank(keyword), w -> w
                        .like(InvZone::getZoneCode, keyword.trim())
                        .or().like(InvZone::getZoneName, keyword.trim()))
                .orderByAsc(InvZone::getSortOrder)
                .orderByAsc(InvZone::getZoneCode);
        return zoneDao.selectList(qw);
    }

    @Override
    public InvZone get(String id) {
        InvZone zone = zoneDao.selectById(id);
        if (zone == null) {
            throw new ServiceException(404, "区域不存在：" + id);
        }
        return zone;
    }

    @Override
    @Transactional
    public InvZone create(InvZone zone) {
        requireRole("维护仓库区域", "ROLE_WAREHOUSE");
        if (zone == null || isBlank(zone.getWhCode()) || isBlank(zone.getZoneCode())) {
            throw new ServiceException(422, "所属仓库与区域编码必填");
        }
        if (isBlank(zone.getZoneName())) {
            throw new ServiceException(422, "区域名称必填");
        }
        // 停用仓库禁建区域（spec「区域档案维护」scenario）
        InvWarehouse wh = warehouseDao.selectOne(new LambdaQueryWrapper<InvWarehouse>()
                .eq(InvWarehouse::getWhCode, zone.getWhCode())
                .last("LIMIT 1"));
        if (wh == null) {
            throw new ServiceException(422, "所属仓库不存在：" + zone.getWhCode());
        }
        if (ST_DISABLED.equals(wh.getStatus())) {
            throw new ServiceException(422, "仓库已停用，不可新建区域：" + wh.getWhName());
        }
        // 仓库内唯一预检（UK_INV_ZONE_CODE 兜底并发）
        Long exists = zoneDao.selectCount(new LambdaQueryWrapper<InvZone>()
                .eq(InvZone::getWhCode, zone.getWhCode())
                .eq(InvZone::getZoneCode, zone.getZoneCode().trim()));
        if (exists != null && exists > 0) {
            throw new ServiceException(409, "区域编码在该仓库内已存在：" + zone.getZoneCode());
        }
        InvZone entity = new InvZone();
        entity.setWhCode(zone.getWhCode());
        entity.setZoneCode(zone.getZoneCode().trim());
        entity.setZoneName(zone.getZoneName().trim());
        entity.setSortOrder(zone.getSortOrder() == null ? 0 : zone.getSortOrder());
        entity.setStatus(ST_ENABLED);
        entity.setDefBinType(zone.getDefBinType());
        entity.setDefTempLevel(zone.getDefTempLevel());
        entity.setDefHazardLevel(zone.getDefHazardLevel());
        entity.setDefCleanLevel(zone.getDefCleanLevel());
        entity.setDefCapacityPallet(zone.getDefCapacityPallet());
        entity.setRemark(zone.getRemark());
        try {
            zoneDao.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "区域编码在该仓库内已存在：" + entity.getZoneCode());
        }
        saveSnapshot(entity, "CREATE", "新建区域");
        return entity;
    }

    @Override
    @Transactional
    public InvZone update(InvZone zone) {
        requireRole("维护仓库区域", "ROLE_WAREHOUSE");
        if (zone == null || isBlank(zone.getId())) {
            throw new ServiceException(422, "区域 ID 必填");
        }
        InvZone stored = get(zone.getId());
        // 编码创建后不可改（spec scenario）
        if (!isBlank(zone.getZoneCode()) && !stored.getZoneCode().equals(zone.getZoneCode())) {
            throw new ServiceException(422, "区域编码创建后不可修改：" + stored.getZoneCode());
        }
        if (isBlank(zone.getZoneName())) {
            throw new ServiceException(422, "区域名称必填");
        }
        // 归属仓库不可改（编号前缀与仓位引用依赖）
        if (!isBlank(zone.getWhCode()) && !stored.getWhCode().equals(zone.getWhCode())) {
            throw new ServiceException(422, "区域所属仓库不可修改");
        }
        stored.setZoneName(zone.getZoneName().trim());
        stored.setSortOrder(zone.getSortOrder() == null ? stored.getSortOrder() : zone.getSortOrder());
        stored.setDefBinType(zone.getDefBinType());
        stored.setDefTempLevel(zone.getDefTempLevel());
        stored.setDefHazardLevel(zone.getDefHazardLevel());
        stored.setDefCleanLevel(zone.getDefCleanLevel());
        stored.setDefCapacityPallet(zone.getDefCapacityPallet());
        stored.setRemark(zone.getRemark());
        int updated = zoneDao.updateById(stored);
        if (updated == 0) {
            // verNo 乐观锁落败（并发覆盖保护）
            throw new ServiceException(409, "区域已被其他用户修改，请刷新后重试");
        }
        saveSnapshot(stored, "UPDATE", "变更区域属性");
        return stored;
    }

    @Override
    @Transactional
    public void enable(String id) {
        requireRole("维护仓库区域", "ROLE_WAREHOUSE");
        InvZone stored = get(id);
        if (ST_ENABLED.equals(stored.getStatus())) {
            return;
        }
        stored.setStatus(ST_ENABLED);
        int updated = zoneDao.updateById(stored);
        if (updated == 0) {
            throw new ServiceException(409, "区域已被其他用户修改，请刷新后重试");
        }
        saveSnapshot(stored, "ENABLE", "启用区域");
    }

    @Override
    @Transactional
    public void disable(String id) {
        requireRole("维护仓库区域", "ROLE_WAREHOUSE");
        InvZone stored = get(id);
        if (ST_DISABLED.equals(stored.getStatus())) {
            return;
        }
        stored.setStatus(ST_DISABLED);
        int updated = zoneDao.updateById(stored);
        if (updated == 0) {
            throw new ServiceException(409, "区域已被其他用户修改，请刷新后重试");
        }
        // 停用不级联仓位（spec「仓位启停与约束」scenario）——此处仅改区域状态，不动 bin
        saveSnapshot(stored, "DISABLE", "停用区域（不级联仓位）");
    }

    @Override
    public List<Map<String, Object>> listWithBinCount(String whCode, String keyword, String status) {
        List<InvZone> zones = list(whCode, keyword, status);
        if (zones.isEmpty()) {
            return new ArrayList<>();
        }
        // 仓位数按区域聚合（规划页左栏计数）
        List<InvBin> bins = binDao.selectList(new LambdaQueryWrapper<InvBin>()
                .in(InvBin::getZoneCode, zones.stream().map(InvZone::getZoneCode)
                        .collect(Collectors.toSet()))
                .eq(!isBlank(whCode), InvBin::getWhCode, whCode));
        Map<String, Long> countByZone = bins.stream()
                .collect(Collectors.groupingBy(InvBin::getZoneCode, Collectors.counting()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (InvZone z : zones) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("zone", z);
            row.put("binCount", countByZone.getOrDefault(z.getZoneCode(), 0L));
            result.add(row);
        }
        return result;
    }

    /** 版本快照（design D1：整行新值、VERSION_NO 递增；仿 MdmCostCenterServiceImpl.saveSnapshot） */
    private void saveSnapshot(InvZone source, String opType, String diffSummary) {
        int next = versionDao.selectCount(new LambdaQueryWrapper<InvZoneVersion>()
                .eq(InvZoneVersion::getEntityId, source.getId())).intValue() + 1;
        InvZoneVersion v = new InvZoneVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diffSummary);
        v.setOpType(opType);
        versionDao.insert(v);
    }

    private String toJson(InvZone z) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", z.getId());
        map.put("whCode", z.getWhCode());
        map.put("zoneCode", z.getZoneCode());
        map.put("zoneName", z.getZoneName());
        map.put("sortOrder", z.getSortOrder());
        map.put("status", z.getStatus());
        map.put("defBinType", z.getDefBinType());
        map.put("defTempLevel", z.getDefTempLevel());
        map.put("defHazardLevel", z.getDefHazardLevel());
        map.put("defCleanLevel", z.getDefCleanLevel());
        map.put("defCapacityPallet", z.getDefCapacityPallet());
        map.put("remark", z.getRemark());
        map.put("verNo", z.getVerNo());
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException ex) {
            throw new ServiceException(500, "区域版本快照序列化失败");
        }
    }

    private void requireRole(String action, String... allowed) {
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
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
