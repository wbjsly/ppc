package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrLeaveBalanceBatchDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrLeaveBalanceBatch;
import com.erp.entity.hr.HrRemind;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrLeaveBalanceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 假期余额池（D5 统一 FEFO：到期日升序跨批次拆分；年假年度发放幂等）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrLeaveBalanceServiceImpl implements HrLeaveBalanceService {

    private final HrLeaveBalanceBatchDao balanceDao;
    private final HrEmployeeDao empDao;
    private final HrRemindDao remindDao;
    private final SysParamService sysParamService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Map<String, Object> balances(String empId) {
        List<HrLeaveBalanceBatch> all = balanceDao.selectList(new LambdaQueryWrapper<HrLeaveBalanceBatch>()
                .eq(HrLeaveBalanceBatch::getEmpId, empId)
                .eq(HrLeaveBalanceBatch::getStatus, HrLeaveBalanceBatch.ST_ACTIVE)
                .eq(HrLeaveBalanceBatch::getDelFlag, "0")
                .orderByAsc(HrLeaveBalanceBatch::getExpiredAt)); // FEFO 序
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, List<HrLeaveBalanceBatch>> byType = new LinkedHashMap<>();
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (HrLeaveBalanceBatch b : all) {
            byType.computeIfAbsent(b.getTypeCode(), k -> new ArrayList<>()).add(b);
            totals.merge(b.getTypeCode(), b.getRemain(), BigDecimal::add);
        }
        out.put("byType", byType);
        out.put("totals", totals);
        return out;
    }

    // ================================================================
    // FEFO 扣减（同类型内；跨批次拆分）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Map<String, Object>> consume(String empId, String typeCode, BigDecimal qty) {
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "扣减量必须大于 0");
        }
        List<HrLeaveBalanceBatch> batches = balanceDao.selectList(new LambdaQueryWrapper<HrLeaveBalanceBatch>()
                .eq(HrLeaveBalanceBatch::getEmpId, empId)
                .eq(HrLeaveBalanceBatch::getTypeCode, typeCode)
                .eq(HrLeaveBalanceBatch::getStatus, HrLeaveBalanceBatch.ST_ACTIVE)
                .eq(HrLeaveBalanceBatch::getDelFlag, "0")
                .orderByAsc(HrLeaveBalanceBatch::getExpiredAt)); // FEFO：先到期先用
        BigDecimal total = batches.stream().map(HrLeaveBalanceBatch::getRemain)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(qty) < 0) {
            throw new ServiceException(422, "余额不足（可申请 "
                    + total.stripTrailingZeros().toPlainString() + "，L1 硬阻断 FR-4.7-6-6）");
        }
        List<Map<String, Object>> trace = new ArrayList<>();
        BigDecimal left = qty;
        for (HrLeaveBalanceBatch b : batches) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = left.min(b.getRemain());
            b.setRemain(b.getRemain().subtract(take));
            b.setStatus(b.getRemain().signum() == 0 ? HrLeaveBalanceBatch.ST_USED
                    : HrLeaveBalanceBatch.ST_ACTIVE);
            b.setUpdateBy(currentUser());
            b.setUpdateDate(LocalDateTime.now());
            balanceDao.updateById(b);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("batchId", b.getId());
            row.put("batchNo", b.getBatchNo());
            row.put("qty", take);
            trace.add(row);
            left = left.subtract(take);
        }
        log.info("FEFO consume {} {} {} from {} batches", empId, typeCode, qty, trace.size());
        return trace;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollback(String empId, String typeCode, List<Map<String, Object>> consumeTrace) {
        if (consumeTrace == null) {
            return;
        }
        for (Map<String, Object> row : consumeTrace) {
            String batchId = String.valueOf(row.get("batchId"));
            // JSON 回读 qty 可能是 Double/Integer——统一容错解析（强转会在内层事务标 rollback-only）
            Object q = row.get("qty");
            BigDecimal qty = q instanceof BigDecimal bd ? bd
                    : new BigDecimal(String.valueOf(q));
            HrLeaveBalanceBatch b = balanceDao.selectById(batchId);
            if (b == null) {
                continue;
            }
            b.setRemain(b.getRemain().add(qty));
            b.setStatus(HrLeaveBalanceBatch.ST_ACTIVE);
            b.setUpdateBy(currentUser());
            b.setUpdateDate(LocalDateTime.now());
            balanceDao.updateById(b);
        }
        log.info("FEFO rollback {} {} trace={}", empId, typeCode, consumeTrace.size());
    }

    // ================================================================
    // 年假年度发放（工龄阶梯，同年幂等）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> annualGrantScan() {
        int year = LocalDate.now().getYear();
        JsonNode ladder = ladder();
        List<HrEmployee> emps = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .in(HrEmployee::getEmpStatus, "PROBATION", "REGULAR"));
        int granted = 0;
        int skipped = 0;
        for (HrEmployee e : emps) {
            if (e.getHireDate() == null) {
                skipped++;
                continue;
            }
            // 同年已有 ANNUAL_GRANT 批次幂等跳过
            long sameYear = balanceDao.selectCount(new LambdaQueryWrapper<HrLeaveBalanceBatch>()
                    .eq(HrLeaveBalanceBatch::getEmpId, e.getId())
                    .eq(HrLeaveBalanceBatch::getTypeCode, "ANNUAL")
                    .eq(HrLeaveBalanceBatch::getSource, HrLeaveBalanceBatch.SRC_ANNUAL)
                    .ge(HrLeaveBalanceBatch::getGeneratedAt, LocalDate.of(year, 1, 1).atStartOfDay())
                    .eq(HrLeaveBalanceBatch::getDelFlag, "0"));
            if (sameYear > 0) {
                skipped++;
                continue;
            }
            int tenure = Period.between(e.getHireDate(), LocalDate.of(year, 1, 1)).getYears();
            BigDecimal days = ladderDays(ladder, tenure);
            if (days == null || days.signum() <= 0) {
                skipped++; // 未满 1 年（仅当年启用不追溯）
                continue;
            }
            LocalDate expired = LocalDate.of(year, 12, 31);
            HrLeaveBalanceBatch b = new HrLeaveBalanceBatch();
            b.setId(uuid());
            b.setBatchNo(nextBatchNo());
            b.setEmpId(e.getId());
            b.setTypeCode("ANNUAL");
            b.setQty(days);
            b.setRemain(days);
            b.setQtyUnit(HrLeaveBalanceBatch.UNIT_DAY);
            b.setSource(HrLeaveBalanceBatch.SRC_ANNUAL);
            b.setGeneratedAt(LocalDate.of(year, 1, 1).atStartOfDay());
            b.setExpiredAt(expired);
            b.setStatus(HrLeaveBalanceBatch.ST_ACTIVE);
            b.setCreateBy("scan");
            b.setCreateDate(LocalDateTime.now());
            balanceDao.insert(b);
            granted++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("granted", granted);
        out.put("skipped", skipped);
        if (granted > 0) {
            log.info("annual leave grant {}: granted={} skipped={}", year, granted, skipped);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> annualExpireScan() {
        LocalDate today = LocalDate.now();
        List<HrLeaveBalanceBatch> overdue = balanceDao.selectList(new LambdaQueryWrapper<HrLeaveBalanceBatch>()
                .eq(HrLeaveBalanceBatch::getTypeCode, "ANNUAL")
                .eq(HrLeaveBalanceBatch::getStatus, HrLeaveBalanceBatch.ST_ACTIVE)
                .lt(HrLeaveBalanceBatch::getExpiredAt, today)
                .eq(HrLeaveBalanceBatch::getDelFlag, "0"));
        int expired = 0;
        for (HrLeaveBalanceBatch b : overdue) {
            HrEmployee e = empDao.selectById(b.getEmpId());
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType("ANNUAL_CLEARED");
            r.setEmpId(b.getEmpId());
            r.setEmpName(e == null ? null : e.getName());
            r.setRemindDate(today);
            r.setDetail("年假批次 " + b.getBatchNo() + " 已到期，"
                    + b.getRemain().stripTrailingZeros().toPlainString() + " 天自动清零（当年不结转，留痕）");
            r.setCreateBy("scan");
            r.setCreateDate(LocalDateTime.now());
            remindDao.insert(r);
            b.setRemain(BigDecimal.ZERO);
            b.setStatus(HrLeaveBalanceBatch.ST_EXPIRED);
            b.setUpdateBy("scan");
            b.setUpdateDate(LocalDateTime.now());
            balanceDao.updateById(b);
            expired++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expired", expired);
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 阶梯取值：工龄年数 ≥ key 的最大档（ANNUAL_LEAVE_LADDER={"1":5,"10":10,"20":15}）。 */
    private BigDecimal ladderDays(JsonNode ladder, int tenureYears) {
        if (ladder == null || !ladder.isObject()) {
            return tenureYears >= 1 ? BigDecimal.valueOf(5) : null;
        }
        BigDecimal best = null;
        int bestKey = -1;
        var it = ladder.fields();
        while (it.hasNext()) {
            var entry = it.next();
            try {
                int key = Integer.parseInt(entry.getKey());
                if (tenureYears >= key && key > bestKey) {
                    bestKey = key;
                    best = new BigDecimal(entry.getValue().asText());
                }
            } catch (Exception ignored) {
                // skip malformed
            }
        }
        return best;
    }

    private JsonNode ladder() {
        String v = sysParamService.getValue("ANNUAL_LEAVE_LADDER");
        try {
            return v == null ? null : objectMapper.readTree(v);
        } catch (Exception e) {
            return null;
        }
    }

    private String nextBatchNo() {
        String prefix = "LBB" + LocalDate.now().getYear() + "-";
        List<String> existing = balanceDao.selectNosByPrefix(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                String tail = no.substring(no.lastIndexOf('-') + 1).replaceAll("[^0-9]", "");
                if (!tail.isEmpty()) {
                    max = Math.max(max, Integer.parseInt(tail));
                }
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
