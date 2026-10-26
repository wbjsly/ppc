package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrPayAccountMappingDao;
import com.erp.dao.pm.PmCostCollectDao;
import com.erp.entity.hr.HrPayAccountMapping;
import com.erp.entity.pm.PmCostCollect;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.pm.PmCostCollectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 五类成本统一归集实现（add-cost-collection-freeze，design D2）。
 * 凭证科目映射集中于 hr_pay_account_mapping FIXED 键（PM_COST_*，沿培训费用先例）；
 * 科目缺失 → L1 拒绝（fail fast，不静默）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmCostCollectServiceImpl implements PmCostCollectService {

    private final PmCostCollectDao collectDao;
    private final HrPayAccountMappingDao mappingDao;
    private final GlVoucherService voucherService;

    /** 类别 → 映射键对（借=DR，贷=CR） */
    private static final Map<String, String[]> TYPE_KEYS = Map.of(
            TYPE_MATERIAL, new String[]{"PM_COST_MATERIAL_DR", "PM_COST_MATERIAL_CR"},
            TYPE_SUBCONTRACT, new String[]{"PM_COST_SUBCONTRACT_DR", "PM_COST_SUBCONTRACT_CR"},
            TYPE_TRAVEL, new String[]{"PM_COST_TRAVEL_DR", "PM_COST_TRAVEL_CR"},
            TYPE_LABOR, new String[]{"PM_COST_LABOR_DR", "PM_COST_LABOR_CR"},
            TYPE_INDIRECT, new String[]{"PM_COST_INDIRECT_DR", "PM_COST_INDIRECT_CR"});

    @Override
    @Transactional
    public String collect(String costType, String sourceType, String sourceId,
                          String projectId, String wbsId, BigDecimal amount, LocalDate date) {
        if (!TYPE_KEYS.containsKey(costType)) {
            throw new ServiceException(422, "未知成本类别：" + costType);
        }
        if (amount == null || amount.signum() == 0) {
            throw new ServiceException(422, "归集金额须非零");
        }
        if (projectId == null || projectId.isBlank()) {
            throw new ServiceException(422, "归集必须关联项目（BR-4.8-20）");
        }
        // 幂等：同来源已归集则跳过（防重复凭证）
        Long existing = collectDao.selectCount(new LambdaQueryWrapper<PmCostCollect>()
                .eq(PmCostCollect::getSourceType, sourceType)
                .eq(PmCostCollect::getSourceId, sourceId)
                .eq(PmCostCollect::getDelFlag, "0"));
        if (existing != null && existing > 0) {
            log.info("cost already collected, skip: {} {}", sourceType, sourceId);
            return collectDao.selectOne(new LambdaQueryWrapper<PmCostCollect>()
                    .eq(PmCostCollect::getSourceType, sourceType)
                    .eq(PmCostCollect::getSourceId, sourceId)
                    .last("LIMIT 1")).getId();
        }
        String[] keys = TYPE_KEYS.get(costType);
        String dr = fixedAccount(keys[0]);
        String cr = fixedAccount(keys[1]);
        String summary = "项目成本-" + costType + " " + sourceType + " " + sourceId;
        FinVoucherRef ref = createVoucher(dr, cr, amount, summary, sourceType + ":" + sourceId);
        PmCostCollect c = new PmCostCollect();
        c.setId(uuid());
        c.setProjectId(projectId);
        c.setWbsId(wbsId);
        c.setCostType(costType);
        c.setAmount(amount);
        c.setSourceType(sourceType);
        c.setSourceId(sourceId);
        c.setCollectDate(date == null ? LocalDate.now() : date);
        c.setVoucherId(ref.voucherId);
        c.setCreateBy("system");
        c.setCreateDate(LocalDateTime.now());
        collectDao.insert(c);
        return c.getId();
    }

    @Override
    public Map<String, Object> summaryByType(String projectId) {
        Map<String, Object> out = new LinkedHashMap<>();
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal material = BigDecimal.ZERO;
        BigDecimal sub = BigDecimal.ZERO;
        BigDecimal travel = BigDecimal.ZERO;
        BigDecimal indirect = BigDecimal.ZERO;
        for (PmCostCollect c : collectDao.selectList(new LambdaQueryWrapper<PmCostCollect>()
                .eq(PmCostCollect::getProjectId, projectId)
                .eq(PmCostCollect::getDelFlag, "0"))) {
            switch (c.getCostType()) {
                case TYPE_LABOR -> labor = labor.add(c.getAmount());
                case TYPE_MATERIAL -> material = material.add(c.getAmount());
                case TYPE_SUBCONTRACT -> sub = sub.add(c.getAmount());
                case TYPE_TRAVEL -> travel = travel.add(c.getAmount());
                case TYPE_INDIRECT -> indirect = indirect.add(c.getAmount());
                default -> { }
            }
        }
        out.put("LABOR", labor);
        out.put("MATERIAL", material);
        out.put("SUBCONTRACT", sub);
        out.put("TRAVEL", travel);
        out.put("INDIRECT", indirect);
        out.put("total", labor.add(material).add(sub).add(travel).add(indirect));
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String projectId, String costType, String dateFrom, String dateTo) {
        LambdaQueryWrapper<PmCostCollect> qw = new LambdaQueryWrapper<PmCostCollect>()
                .eq(PmCostCollect::getDelFlag, "0")
                .orderByDesc(PmCostCollect::getCollectDate);
        if (projectId != null && !projectId.isBlank()) {
            qw.eq(PmCostCollect::getProjectId, projectId);
        }
        if (costType != null && !costType.isBlank()) {
            qw.eq(PmCostCollect::getCostType, costType);
        }
        if (dateFrom != null && !dateFrom.isBlank()) {
            qw.ge(PmCostCollect::getCollectDate, LocalDate.parse(dateFrom));
        }
        if (dateTo != null && !dateTo.isBlank()) {
            qw.le(PmCostCollect::getCollectDate, LocalDate.parse(dateTo));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmCostCollect c : collectDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", c.getId());
            row.put("projectId", c.getProjectId());
            row.put("wbsId", c.getWbsId());
            row.put("costType", c.getCostType());
            row.put("amount", c.getAmount());
            row.put("sourceType", c.getSourceType());
            row.put("sourceId", c.getSourceId());
            row.put("collectDate", c.getCollectDate());
            row.put("voucherId", c.getVoucherId());
            out.add(row);
        }
        return out;
    }

    @Override
    public BigDecimal actualTotal(String projectId) {
        Map<String, Object> s = summaryByType(projectId);
        return (BigDecimal) s.get("total");
    }

    // ---- 凭证 ----

    /** 凭证引用（voucherId） */
    private record FinVoucherRef(String voucherId) {
    }

    private FinVoucherRef createVoucher(String dr, String cr, BigDecimal amount,
                                        String summary, String anchor) {
        List<GlVoucherService.FinVoucherLineSpec> lines = List.of(
                GlVoucherService.FinVoucherLineSpec.of(dr, "Dr", amount, summary),
                GlVoucherService.FinVoucherLineSpec.of(cr, "Cr", amount, summary));
        com.erp.entity.fin.FinVoucher v = voucherService.create(
                "ACC", LocalDate.now(), summary, VOUCHER_SOURCE, anchor, null, lines);
        return new FinVoucherRef(v.getId());
    }

    private String fixedAccount(String key) {
        HrPayAccountMapping m = mappingDao.selectOne(new LambdaQueryWrapper<HrPayAccountMapping>()
                .eq(HrPayAccountMapping::getMappingType, HrPayAccountMapping.TYPE_FIXED)
                .eq(HrPayAccountMapping::getMappingKey, key)
                .eq(HrPayAccountMapping::getDelFlag, "0"));
        if (m == null) {
            throw new ServiceException(500, "项目成本科目映射缺失：" + key + "（迁移 171 未执行？）");
        }
        return m.getAccountCode();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
