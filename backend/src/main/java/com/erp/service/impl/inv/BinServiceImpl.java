package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBinDao;
import com.erp.dao.inv.InvZoneDao;
import com.erp.entity.inv.InvBin;
import com.erp.entity.inv.InvZone;
import com.erp.service.inv.BinService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库仓位（4.1.2，spec warehouse-zone-planning，design D2/D3/D4/D7）：
 * 编号服务端单点生成（等宽补零保证字典序 = 物理路径序），请求体 binCode 忽略；
 * 属性写入时物化继承区域默认（可覆盖）；批量规划预检 + 单事务，冲突整体拒绝。
 */
@Slf4j
@Service
public class BinServiceImpl implements BinService {

    private static final String ST_ENABLED = "1";
    private static final String ST_DISABLED = "0";

    private final InvBinDao binDao;
    private final InvZoneDao zoneDao;

    /** 批量规划数量上限（design D8：进 app.inv 参数，不硬编码） */
    @Value("${app.inv.bin-batch-max:5000}")
    private int batchMax;

    public BinServiceImpl(InvBinDao binDao, InvZoneDao zoneDao) {
        this.binDao = binDao;
        this.zoneDao = zoneDao;
    }

    @Override
    public List<Map<String, Object>> query(String whCode, String zoneCode, String keyword, String status) {
        LambdaQueryWrapper<InvBin> qw = new LambdaQueryWrapper<InvBin>()
                .eq(!isBlank(whCode), InvBin::getWhCode, whCode)
                .eq(!isBlank(zoneCode), InvBin::getZoneCode, zoneCode)
                .eq(!isBlank(status), InvBin::getStatus, status)
                .like(!isBlank(keyword), InvBin::getBinCode, keyword == null ? null : keyword.trim())
                .orderByAsc(InvBin::getBinCode);
        List<InvBin> bins = binDao.selectList(qw);
        // 携带区域名称与默认属性对照（spec「仓位查询」）
        Map<String, InvZone> zoneByKey = new LinkedHashMap<>();
        if (!bins.isEmpty()) {
            List<InvZone> zones = zoneDao.selectList(new LambdaQueryWrapper<InvZone>()
                    .in(InvZone::getZoneCode, distinctZoneCodes(bins)));
            for (InvZone z : zones) {
                zoneByKey.put(z.getWhCode() + "|" + z.getZoneCode(), z);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (InvBin b : bins) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("bin", b);
            InvZone z = zoneByKey.get(b.getWhCode() + "|" + b.getZoneCode());
            row.put("zoneName", z == null ? null : z.getZoneName());
            row.put("zoneDefault", z);
            result.add(row);
        }
        return result;
    }

    @Override
    public InvBin get(String id) {
        InvBin bin = binDao.selectById(id);
        if (bin == null) {
            throw new ServiceException(404, "仓位不存在：" + id);
        }
        return bin;
    }

    @Override
    @Transactional
    public InvBin create(InvBin bin) {
        requireRole("维护仓库仓位", "ROLE_WAREHOUSE");
        if (bin == null || isBlank(bin.getWhCode()) || isBlank(bin.getZoneCode())) {
            throw new ServiceException(422, "所属仓库与区域必填");
        }
        requireValidRange(bin.getBinSeq(), bin.getColNo(), bin.getLayerNo());
        InvZone zone = requireActiveZone(bin.getWhCode(), bin.getZoneCode());

        InvBin entity = new InvBin();
        entity.setWhCode(bin.getWhCode());
        entity.setZoneCode(bin.getZoneCode());
        entity.setBinSeq(bin.getBinSeq());
        entity.setColNo(bin.getColNo());
        entity.setLayerNo(bin.getLayerNo());
        // 编号服务端生成，请求体 binCode 一律忽略（design D2）
        entity.setBinCode(buildBinCode(zone.getZoneCode(), bin.getBinSeq(), bin.getColNo(), bin.getLayerNo()));
        // 属性继承区域默认，可传入覆盖（design D3 写入时物化）
        entity.setBinType(firstNonBlank(bin.getBinType(), zone.getDefBinType()));
        entity.setTempLevel(firstNonBlank(bin.getTempLevel(), zone.getDefTempLevel()));
        entity.setHazardLevel(firstNonBlank(bin.getHazardLevel(), zone.getDefHazardLevel()));
        entity.setCleanLevel(firstNonBlank(bin.getCleanLevel(), zone.getDefCleanLevel()));
        entity.setCapacityPallet(bin.getCapacityPallet() != null
                ? bin.getCapacityPallet() : zone.getDefCapacityPallet());
        entity.setStatus(ST_ENABLED);
        entity.setRemark(bin.getRemark());
        try {
            binDao.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "仓位编号已存在：" + entity.getBinCode());
        }
        return entity;
    }

    @Override
    @Transactional
    public InvBin update(InvBin bin) {
        requireRole("维护仓库仓位", "ROLE_WAREHOUSE");
        if (bin == null || isBlank(bin.getId())) {
            throw new ServiceException(422, "仓位 ID 必填");
        }
        InvZone zone = requireActiveZone(bin.getWhCode() == null
                ? get(bin.getId()).getWhCode() : bin.getWhCode(),
                bin.getZoneCode() == null ? get(bin.getId()).getZoneCode() : bin.getZoneCode());
        InvBin stored = get(bin.getId());
        // 编号与归属创建后不可改（编号是排序契约）
        if (!isBlank(bin.getBinCode()) && !stored.getBinCode().equals(bin.getBinCode())) {
            throw new ServiceException(422, "仓位编号创建后不可修改：" + stored.getBinCode());
        }
        if (bin.getBinType() != null) stored.setBinType(bin.getBinType());
        if (bin.getTempLevel() != null) stored.setTempLevel(bin.getTempLevel());
        if (bin.getHazardLevel() != null) stored.setHazardLevel(bin.getHazardLevel());
        if (bin.getCleanLevel() != null) stored.setCleanLevel(bin.getCleanLevel());
        if (bin.getCapacityPallet() != null) stored.setCapacityPallet(bin.getCapacityPallet());
        if (bin.getRemark() != null) stored.setRemark(bin.getRemark());
        int updated = binDao.updateById(stored);
        if (updated == 0) {
            throw new ServiceException(409, "仓位已被其他用户修改，请刷新后重试");
        }
        return stored;
    }

    @Override
    @Transactional
    public void enable(String id) {
        requireRole("维护仓库仓位", "ROLE_WAREHOUSE");
        InvBin stored = get(id);
        if (ST_ENABLED.equals(stored.getStatus())) {
            return;
        }
        // 启用前要求区域仍启用（停用区域下不可新增/恢复仓位）
        requireActiveZone(stored.getWhCode(), stored.getZoneCode());
        stored.setStatus(ST_ENABLED);
        if (binDao.updateById(stored) == 0) {
            throw new ServiceException(409, "仓位已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    @Transactional
    public void disable(String id) {
        requireRole("维护仓库仓位", "ROLE_WAREHOUSE");
        InvBin stored = get(id);
        if (ST_DISABLED.equals(stored.getStatus())) {
            return;
        }
        stored.setStatus(ST_DISABLED);
        if (binDao.updateById(stored) == 0) {
            throw new ServiceException(409, "仓位已被其他用户修改，请刷新后重试");
        }
    }

    @Override
    @Transactional
    public Map<String, Object> batchCreate(Map<String, Object> payload) {
        requireRole("批量规划仓位", "ROLE_WAREHOUSE");
        String whCode = str(payload.get("whCode"));
        String zoneCode = str(payload.get("zoneCode"));
        int seqStart = intOf(payload.get("seqStart"));
        int seqEnd = intOf(payload.get("seqEnd"));
        int colStart = intOf(payload.get("colStart"));
        int colEnd = intOf(payload.get("colEnd"));
        int layerStart = intOf(payload.get("layerStart"));
        int layerEnd = intOf(payload.get("layerEnd"));
        if (isBlank(whCode) || isBlank(zoneCode)) {
            throw new ServiceException(422, "所属仓库与区域必填");
        }
        if (seqStart < 1 || seqEnd < seqStart || colStart < 1 || colEnd < colStart
                || layerStart < 1 || layerEnd < layerStart) {
            throw new ServiceException(422, "排/列/层区间非法：起始须 ≥ 1 且起 ≤ 止");
        }
        long total = (long) (seqEnd - seqStart + 1) * (colEnd - colStart + 1) * (layerEnd - layerStart + 1);
        if (total > batchMax) {
            throw new ServiceException(422, "生成数量 " + total + " 超过上限 " + batchMax + "，请拆分区间");
        }
        InvZone zone = requireActiveZone(whCode, zoneCode);

        // 组装目标编号与实体（属性继承，可整体覆盖：payload.override* 非空优先）
        List<InvBin> targets = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        for (int s = seqStart; s <= seqEnd; s++) {
            for (int c = colStart; c <= colEnd; c++) {
                for (int l = layerStart; l <= layerEnd; l++) {
                    InvBin b = new InvBin();
                    b.setWhCode(whCode);
                    b.setZoneCode(zoneCode);
                    b.setBinSeq(s);
                    b.setColNo(c);
                    b.setLayerNo(l);
                    b.setBinCode(buildBinCode(zoneCode, s, c, l));
                    b.setBinType(firstNonBlank(str(payload.get("overrideBinType")), zone.getDefBinType()));
                    b.setTempLevel(firstNonBlank(str(payload.get("overrideTempLevel")), zone.getDefTempLevel()));
                    b.setHazardLevel(firstNonBlank(str(payload.get("overrideHazardLevel")), zone.getDefHazardLevel()));
                    b.setCleanLevel(firstNonBlank(str(payload.get("overrideCleanLevel")), zone.getDefCleanLevel()));
                    b.setCapacityPallet(payload.get("overrideCapacityPallet") != null
                            ? intOf(payload.get("overrideCapacityPallet")) : zone.getDefCapacityPallet());
                    b.setStatus(ST_ENABLED);
                    targets.add(b);
                    codes.add(b.getBinCode());
                }
            }
        }
        // 预检：冲突整体拒绝（design D4，友好列出全部冲突编号）
        List<String> conflicts = new ArrayList<>(binDao.selectExistingCodes(codes));
        if (!conflicts.isEmpty()) {
            conflicts.sort(String::compareTo);
            throw new ServiceException(409, "存在 " + conflicts.size()
                    + " 个编号冲突，整体未生成：" + String.join(", ", conflicts));
        }
        // 单事务插入；唯一键兜底并发竞态 → DuplicateKeyException 回滚（无部分生成）
        for (InvBin b : targets) {
            try {
                binDao.insert(b);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                throw new ServiceException(409, "并发写入冲突，整体已回滚：" + b.getBinCode());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("created", targets.size());
        result.put("from", codes.get(0));
        result.put("to", codes.get(codes.size() - 1));
        return result;
    }

    /** 编号 = 区域码-排2位-列2位-层2位（等宽补零，字典序 = 物理路径序，design D2） */
    static String buildBinCode(String zoneCode, int seq, int col, int layer) {
        return String.format("%s-%02d-%02d-%02d", zoneCode, seq, col, layer);
    }

    private InvZone requireActiveZone(String whCode, String zoneCode) {
        InvZone zone = zoneDao.selectOne(new LambdaQueryWrapper<InvZone>()
                .eq(InvZone::getWhCode, whCode)
                .eq(InvZone::getZoneCode, zoneCode)
                .last("LIMIT 1"));
        if (zone == null) {
            throw new ServiceException(422, "区域不存在：" + whCode + "/" + zoneCode);
        }
        if (ST_DISABLED.equals(zone.getStatus())) {
            throw new ServiceException(422, "区域已停用，不可操作其下仓位：" + zone.getZoneName());
        }
        return zone;
    }

    private void requireValidRange(Integer seq, Integer col, Integer layer) {
        if (seq == null || col == null || layer == null) {
            throw new ServiceException(422, "排/列/层必填");
        }
        if (seq < 1 || col < 1 || layer < 1) {
            throw new ServiceException(422, "排/列/层须 ≥ 1");
        }
    }

    private List<String> distinctZoneCodes(List<InvBin> bins) {
        List<String> codes = new ArrayList<>();
        for (InvBin b : bins) {
            if (!codes.contains(b.getZoneCode())) {
                codes.add(b.getZoneCode());
            }
        }
        return codes;
    }

    private String firstNonBlank(String preferred, String fallback) {
        return isBlank(preferred) ? fallback : preferred;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private int intOf(Object o) {
        if (o == null) {
            return 0;
        }
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式非法：" + o);
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
