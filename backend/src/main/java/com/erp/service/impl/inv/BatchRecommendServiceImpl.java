package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvStock;
import com.erp.service.SysParamService;
import com.erp.service.inv.BatchRecommendService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FIFO+FEFO 批次推荐（spec outbound-strategy，BR-4.4-19）：
 * 排序 inboundDate → 效期 → createDate（与引擎挑序同口径，design D1 对拍）；
 * 候选池剔除效期锁定、零可用/全冻结（预算 ≤ 0）批次；按批次预算
 * （Σ位行可用 − ACTIVE 预留，与引擎批次预算封顶同口径）逐批累加取量。
 * 效期锁定实时计算（design D4 兜底）：读标记位后按当日现算覆盖内存视图，不回写 DB。
 * 只读模拟：不加锁、不改任何数据。
 */
@Slf4j
@Service
public class BatchRecommendServiceImpl implements BatchRecommendService {

    private final InvStockDao stockDao;
    private final InvBatchDao batchDao;
    private final ReservationDao reservationDao;
    private final SysParamService sysParamService;
    private final com.erp.dao.inv.ExpiryLockLogDao lockLogDao;

    public BatchRecommendServiceImpl(InvStockDao stockDao, InvBatchDao batchDao,
                                     ReservationDao reservationDao, SysParamService sysParamService,
                                     com.erp.dao.inv.ExpiryLockLogDao lockLogDao) {
        this.stockDao = stockDao;
        this.batchDao = batchDao;
        this.reservationDao = reservationDao;
        this.sysParamService = sysParamService;
        this.lockLogDao = lockLogDao;
    }

    @Override
    public Map<String, Object> recommend(String warehouseCode, String itemCode,
                                         BigDecimal qty, boolean binLevel) {
        return doRecommend(warehouseCode, itemCode, qty, binLevel, List.of());
    }

    @Override
    public Map<String, Object> recommendForWave(String warehouseCode, String itemCode,
                                                BigDecimal qty, boolean binLevel,
                                                List<String> excludeSoIds) {
        return doRecommend(warehouseCode, itemCode, qty, binLevel,
                excludeSoIds == null ? List.of() : excludeSoIds);
    }

    /** 核心推荐（excludeSoIds 非空时批次预算排除波次自身预留——spec wave-management 波次级统一分配） */
    private Map<String, Object> doRecommend(String warehouseCode, String itemCode,
                                            BigDecimal qty, boolean binLevel,
                                            List<String> excludeSoIds) {
        if (warehouseCode == null || warehouseCode.trim().isEmpty()) {
            throw new ServiceException(422, "仓库必填");
        }
        if (itemCode == null || itemCode.trim().isEmpty()) {
            throw new ServiceException(422, "物料必填");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "出库数量必须大于 0");
        }
        String wh = warehouseCode.trim();
        String item = itemCode.trim();

        // 批次台账：效期 + 锁定位（实时计算覆盖，design D4）
        Map<String, InvBatch> ledger = new HashMap<>();
        for (InvBatch b : batchDao.selectList(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getItemCode, item))) {
            ledger.put(b.getBatchNo(), b);
        }
        BigDecimal lockRatio = sysParamService.getRate("EXPIRY_LOCK_RATIO", new BigDecimal("0.5"));
        int warnDays = sysParamService.getInt("MIN_REMAINING_SHELF_DAYS", 30);

        // 位行候选
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, wh)
                .eq(InvStock::getItemCode, item));

        Map<String, List<InvStock>> byBatch = new LinkedHashMap<>();
        for (InvStock r : rows) {
            byBatch.computeIfAbsent(str(r.getBatchNo()), k -> new ArrayList<>()).add(r);
        }

        // 剔除 + 预算
        Map<String, LocalDate> expiryByBatch = new HashMap<>();
        List<Map<String, Object>> excluded = new ArrayList<>();
        Map<String, BigDecimal> budgetByBatch = new HashMap<>();
        Map<String, BigDecimal> availByBatch = new HashMap<>();
        LocalDate today = LocalDate.now();
        // 波次内单据自身预留（按批次汇总，预算回加用）
        Map<String, BigDecimal> waveOwnReserved = new HashMap<>();
        if (!excludeSoIds.isEmpty()) {
            for (Map<String, Object> r : reservationDao.sumActiveOnBatchBySos(wh, item, excludeSoIds)) {
                waveOwnReserved.put(str(r.get("batchNo")), nvl((BigDecimal) r.get("qty")));
            }
        }

        for (Map.Entry<String, List<InvStock>> e : byBatch.entrySet()) {
            String batch = e.getKey();
            InvBatch lb = ledger.get(batch);
            LocalDate expiry = lb == null ? null : lb.getExpiryDate();
            if (expiry != null) {
                expiryByBatch.put(batch, expiry);
            }
            // 效期锁定（台账标记 + 当日实时计算，两者任一命中即剔除）；
            // 有效豁免优先放行（expiry-management 需求⑤：让步放行批次重回推荐池）
            boolean exempt = lb != null && lb.getEvalExemptUntil() != null
                    && !lb.getEvalExemptUntil().isBefore(today);
            boolean locked = !exempt && (lb != null && "1".equals(str(lb.getExpiryLockFlag()))
                    || isExpiredLock(lb, expiry, today, lockRatio));
            BigDecimal avail = BigDecimal.ZERO;
            for (InvStock r : e.getValue()) {
                BigDecimal a = nvl(r.getAvailableQty());
                if (a.signum() > 0) {
                    avail = avail.add(a);
                }
            }
            BigDecimal reserved = nvl(reservationDao.sumActiveOnBatch(wh, item, batch));
            if (!excludeSoIds.isEmpty()) {
                // 波次共享预算：本波次单据自己的预留不占预算（design D3 防双扣）
                reserved = reserved.subtract(nvl(waveOwnReserved.get(batch)));
                if (reserved.signum() < 0) {
                    reserved = BigDecimal.ZERO;
                }
            }
            BigDecimal budget = avail.subtract(reserved);
            if (budget.signum() < 0) {
                budget = BigDecimal.ZERO;
            }
            availByBatch.put(batch, avail);
            budgetByBatch.put(batch, budget);

            if (locked) {
                excluded.add(excluded(batch, "效期锁定（剩余效期低于锁定阈值，禁止正常出库）"));
            } else if (budget.signum() <= 0) {
                excluded.add(excluded(batch, avail.signum() <= 0
                        ? "可用量为 0（已冻结或已耗尽）" : "可用量已被预留占满"));
            }
        }

        // 挑序：inboundDate → 效期 → createDate（同引擎）
        List<String> order = new ArrayList<>(byBatch.keySet());
        order.sort(Comparator
                .comparing((String b) -> inboundMin(byBatch.get(b)))
                .thenComparing(b -> expiryByBatch.getOrDefault(b, LocalDate.of(2999, 12, 31)))
                .thenComparing((String b) -> createMin(byBatch.get(b))));

        BigDecimal remain = qty;
        BigDecimal cumulative = BigDecimal.ZERO;
        List<Map<String, Object>> lines = new ArrayList<>();
        List<String> usedBatches = new ArrayList<>();
        for (String batch : order) {
            BigDecimal take;
            if (excludedBatchSet(excluded).contains(batch)) {
                continue;
            }
            BigDecimal budget = budgetByBatch.get(batch);
            take = budget.min(remain);
            if (take.signum() <= 0) {
                continue;
            }
            InvBatch lb = ledger.get(batch);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("batchNo", batch);
            line.put("inboundDate", String.valueOf(inboundMin(byBatch.get(batch))));
            line.put("expiryDate", lb == null || lb.getExpiryDate() == null
                    ? null : lb.getExpiryDate().toString());
            line.put("available", budget);
            line.put("totalAvailable", availByBatch.get(batch));
            line.put("take", take);
            cumulative = cumulative.add(take);
            line.put("cumulative", cumulative);
            // 效期警告（BR-4.4-21：不阻断，加注）
            if (lb != null && lb.getExpiryDate() != null) {
                long remaining = java.time.temporal.ChronoUnit.DAYS
                        .between(today, lb.getExpiryDate());
                line.put("remainingDays", remaining);
                line.put("expiryWarning", remaining < warnDays);
            }
            if (binLevel) {
                line.put("bins", binDetail(byBatch.get(batch), take));
            }
            lines.add(line);
            usedBatches.add(batch);
            remain = remain.subtract(take);
            if (remain.signum() <= 0) {
                break;
            }
        }

        BigDecimal totalAvailable = BigDecimal.ZERO;
        for (String b : budgetByBatch.keySet()) {
            if (!excludedBatchSet(excluded).contains(b)) {
                totalAvailable = totalAvailable.add(budgetByBatch.get(b));
            }
        }

        boolean satisfied = remain.signum() <= 0;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("satisfied", satisfied);
        out.put("totalAvailable", totalAvailable);
        out.put("gap", satisfied ? BigDecimal.ZERO : remain);
        out.put("lines", lines);
        out.put("excluded", excluded);
        out.put("sortRule", "入库日期升序（FIFO）→ 同日按有效期升序（FEFO）→ 创建时间；"
                + "排除冻结、效期锁定与零可用批次（BR-4.4-19）");
        out.put("splitSuggestion", satisfied ? null
                : "当前可配 " + strip(totalAvailable) + "、缺口 " + strip(remain)
                + "：建议按现有量分批出库（本单出 " + strip(totalAvailable)
                + "），或等待补货到货后合并出库");
        return out;
    }

    @Override
    public int scanExpiryLock() {
        BigDecimal lockRatio = sysParamService.getRate("EXPIRY_LOCK_RATIO", new BigDecimal("0.5"));
        LocalDate today = LocalDate.now();
        List<InvBatch> all = batchDao.selectList(new LambdaQueryWrapper<InvBatch>()
                .isNotNull(InvBatch::getExpiryDate));
        int changed = 0;
        for (InvBatch b : all) {
            String flag = str(b.getExpiryLockFlag());
            // 0) 过期豁免清理：即使 flag 无变化也要清列（回归 AUTO 语义，否则残留污染下次放行判定）
            boolean staleExempt = b.getEvalExemptUntil() != null
                    && b.getEvalExemptUntil().isBefore(today);
            String want;
            // 1) 让步放行豁免优先（expiry-management 需求⑤）：未过期强制不锁，可清既有位
            if (b.getEvalExemptUntil() != null && !staleExempt) {
                want = "0";
            } else if (InvBatch.SRC_MANUAL.equals(str(b.getLockSource()))) {
                // 2) 人工锁只评估不清除（需求③）：解除唯一入口=评估放行；
                //    生产日期缺失无法算线 → 也保持锁（宁可锁着等评估）
                want = "1";
            } else if (b.getProductionDate() == null || b.getExpiryDate() == null) {
                // 3) AUTO 且生产日期缺失 → 标记位不动（只读判定见 isExpiredLock 注释）
                if (!staleExempt) {
                    continue;
                }
                want = flag;   // 仅清过期豁免列，不动标记位
            } else {
                // 4) AUTO 双向自愈（既有行为不变）
                want = isExpiredLock(b, b.getExpiryDate(), today, lockRatio) ? "1" : "0";
            }
            if (want.equals(flag) && !staleExempt) {
                continue;
            }
            // 批量点更新：绕乐观锁（调度单线程幂等重算，@Version 会因并发 verNo 失配静默 no-op）
            batchDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InvBatch>()
                    .eq(InvBatch::getId, b.getId())
                    .set(!want.equals(flag), InvBatch::getExpiryLockFlag, want)
                    .set(staleExempt, InvBatch::getEvalExemptUntil, null)
                    .set(staleExempt, InvBatch::getEvalExemptId, null)
                    .setSql("VER_NO = VER_NO + 1"));
            if (!want.equals(flag)) {
                lockLogDao.insert(com.erp.entity.inv.ExpiryLockLog.of(b, flag, want,
                        com.erp.entity.inv.ExpiryLockLog.SRC_SCAN, "system:scan", null));
                changed++;
            }
        }
        if (changed > 0) {
            log.info("expiry lock scan done: {} batch(es) toggled (ratio={})", changed, lockRatio);
        }
        return changed;
    }

    /** 位行明细（4.6.3 需要仓位；按仓位号升序取足） */
    private List<Map<String, Object>> binDetail(List<InvStock> rows, BigDecimal take) {
        List<Map<String, Object>> bins = new ArrayList<>();
        BigDecimal remain = take;
        List<InvStock> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing((InvStock r) -> str(r.getBinCode())));
        for (InvStock r : sorted) {
            if (remain.signum() <= 0) {
                break;
            }
            BigDecimal a = nvl(r.getAvailableQty());
            if (a.signum() <= 0) {
                continue;
            }
            BigDecimal t = a.min(remain);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("binCode", str(r.getBinCode()));
            b.put("available", a);
            b.put("take", t);
            bins.add(b);
            remain = remain.subtract(t);
        }
        return bins;
    }

    /** 效期锁定实时判定（BR-4.4-20）：剩余天数 < 有效期总天数 × EXPIRY_LOCK_RATIO。
     *  有效期总天数 = 有效期至 − 生产日期；生产日期缺失时无法计算总天数，不锁定（只按标记位）。 */
    private boolean isExpiredLock(InvBatch lb, LocalDate expiry, LocalDate today, BigDecimal ratio) {
        if (lb == null || expiry == null || lb.getProductionDate() == null) {
            return false;
        }
        long total = java.time.temporal.ChronoUnit.DAYS
                .between(lb.getProductionDate(), expiry);
        if (total <= 0) {
            return false;
        }
        long remaining = java.time.temporal.ChronoUnit.DAYS.between(today, expiry);
        BigDecimal threshold = BigDecimal.valueOf(total).multiply(ratio)
                .setScale(0, RoundingMode.DOWN);
        return BigDecimal.valueOf(remaining).compareTo(threshold) < 0;
    }

    private java.util.Set<String> excludedBatchSet(List<Map<String, Object>> excluded) {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (Map<String, Object> e : excluded) {
            s.add(str(e.get("batchNo")));
        }
        return s;
    }

    private Map<String, Object> excluded(String batch, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("batchNo", batch);
        m.put("reason", reason);
        return m;
    }

    private LocalDate inboundMin(List<InvStock> rows) {
        LocalDate min = null;
        for (InvStock r : rows) {
            if (r.getInboundDate() != null && (min == null || r.getInboundDate().isBefore(min))) {
                min = r.getInboundDate();
            }
        }
        return min == null ? LocalDate.of(1970, 1, 1) : min;
    }

    private LocalDate createMin(List<InvStock> rows) {
        LocalDate min = null;
        for (InvStock r : rows) {
            if (r.getCreateDate() != null) {
                LocalDate d = r.getCreateDate().toLocalDate();
                if (min == null || d.isBefore(min)) {
                    min = d;
                }
            }
        }
        return min == null ? LocalDate.of(1970, 1, 1) : min;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }
}
