package com.erp.service.impl.vmi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.vmi.VmiDisposalDao;
import com.erp.dao.vmi.VmiStockDao;
import com.erp.entity.vmi.VmiDisposal;
import com.erp.entity.vmi.VmiStock;
import com.erp.service.vmi.VmiDisposalService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 处置建议单实现（BR-4.2-39，spec vmi-consignment，design D4 惰性扫描）。 */
@Slf4j
@Service
public class VmiDisposalServiceImpl implements VmiDisposalService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final VmiDisposalDao disposalDao;
    private final VmiStockDao stockDao;

    /** 寄售库存账龄处置阈值（VMI_AGING_LIMIT_DAYS，默认 180 天，design D10） */
    @Value("${app.proc.vmi-aging-limit-days:180}")
    private int agingLimitDays;

    public VmiDisposalServiceImpl(VmiDisposalDao disposalDao, VmiStockDao stockDao) {
        this.disposalDao = disposalDao;
        this.stockDao = stockDao;
    }

    @Override
    @Transactional
    public int scan() {
        LocalDate today = LocalDate.now();
        int created = 0;
        for (VmiStock s : stockDao.selectList(new LambdaQueryWrapper<VmiStock>())) {
            if (s.getQty() == null || s.getQty().signum() <= 0) {
                continue;
            }
            if (s.getIssuedQty() != null && s.getIssuedQty().signum() > 0) {
                continue;   // 有累计领用 → 不满足 BR-4.2-39
            }
            if (s.getInboundDate() == null) {
                continue;
            }
            long age = ChronoUnit.DAYS.between(s.getInboundDate(), today);
            if (age <= agingLimitDays) {
                continue;
            }
            Long open = disposalDao.selectCount(new LambdaQueryWrapper<VmiDisposal>()
                    .eq(VmiDisposal::getStockId, s.getId())
                    .eq(VmiDisposal::getStatus, VmiDisposal.ST_OPEN));
            if (open != null && open > 0) {
                continue;   // 同批次去重（design D4）
            }
            VmiDisposal d = new VmiDisposal();
            d.setDisposalNo(nextDisposalNo());
            d.setStockId(s.getId());
            d.setItemCode(s.getItemCode());
            d.setItemName(s.getItemName());
            d.setBatchNo(s.getBatchNo());
            d.setSupplierId(s.getSupplierId());
            d.setSupplierName(s.getSupplierName());
            d.setQty(s.getQty());
            d.setAgeDays((int) age);
            d.setSuggestType(VmiDisposal.SUGGEST_RETURN);
            d.setStatus(VmiDisposal.ST_OPEN);
            d.setCreateBy(SecurityUtils.getCurrentUserId());
            disposalDao.insert(d);
            created++;
            log.info("处置建议单 {} 生成 item={} batch={} age={}d qty={}", d.getDisposalNo(),
                    s.getItemCode(), s.getBatchNo(), age, s.getQty());
        }
        return created;
    }

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status,
                                          String suggestType) {
        Page<VmiDisposal> p = disposalDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<VmiDisposal>()
                        .eq(hasText(status), VmiDisposal::getStatus, status)
                        .eq(hasText(suggestType), VmiDisposal::getSuggestType, suggestType)
                        .orderByDesc(VmiDisposal::getAgeDays));
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VmiDisposal d : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.getId());
            row.put("disposalNo", d.getDisposalNo());
            row.put("stockId", d.getStockId());
            row.put("itemCode", d.getItemCode());
            row.put("itemName", d.getItemName());
            row.put("batchNo", d.getBatchNo());
            row.put("supplierId", d.getSupplierId());
            row.put("supplierName", d.getSupplierName());
            row.put("qty", d.getQty());
            row.put("ageDays", d.getAgeDays());
            row.put("suggestType", d.getSuggestType());
            row.put("status", d.getStatus());
            row.put("resolvedVia", d.getResolvedVia());
            row.put("resolveBy", d.getResolveBy());
            row.put("resolveAt", d.getResolveAt());
            row.put("resolveNote", d.getResolveNote());
            row.put("createDate", d.getCreateDate());
            rows.add(row);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    @Transactional
    public void resolve(String id, Map<String, Object> body) {
        VmiDisposal d = require(id);
        if (!VmiDisposal.ST_OPEN.equals(d.getStatus())) {
            throw new ServiceException(422, "仅 OPEN 建议单可处理，当前 " + d.getStatus());
        }
        String via = body == null || body.get("resolvedVia") == null
                ? d.getSuggestType() : String.valueOf(body.get("resolvedVia"));
        if (!VmiDisposal.SUGGEST_CONVERT.equals(via) && !VmiDisposal.SUGGEST_RETURN.equals(via)) {
            throw new ServiceException(422, "处置方式须为 CONVERT（转自有）或 RETURN（退回供应商）");
        }
        d.setStatus(VmiDisposal.ST_DONE);
        d.setResolvedVia(via);
        d.setResolveBy(SecurityUtils.getCurrentUserId());
        d.setResolveAt(LocalDateTime.now());
        d.setResolveNote(body == null || body.get("note") == null
                ? null : String.valueOf(body.get("note")));
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        disposalDao.updateById(d);
        log.info("处置建议单 {} 处理完成 via={} note={}", d.getDisposalNo(), via, d.getResolveNote());
    }

    @Override
    @Transactional
    public void cancel(String id, String reason) {
        VmiDisposal d = require(id);
        if (!VmiDisposal.ST_OPEN.equals(d.getStatus())) {
            throw new ServiceException(422, "仅 OPEN 建议单可作废，当前 " + d.getStatus());
        }
        if (!hasText(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "作废原因必填（不少于 2 字）");
        }
        d.setStatus(VmiDisposal.ST_CANCELLED);
        d.setResolveNote(reason.trim());
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        disposalDao.updateById(d);
        log.info("处置建议单 {} 作废: {}", d.getDisposalNo(), reason);
    }

    private VmiDisposal require(String id) {
        VmiDisposal d = disposalDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "处置建议单不存在：" + id);
        }
        return d;
    }

    private String nextDisposalNo() {
        String prefix = "VD" + LocalDateTime.now().format(MONTH) + "-";
        Integer max = disposalDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
