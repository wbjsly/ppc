package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ExpiryLockLogDao;
import com.erp.dao.inv.InvBatchDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.entity.inv.ExpiryLockLog;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.inv.InvStock;
import com.erp.service.SysParamService;
import com.erp.service.inv.BatchRecommendService;
import com.erp.service.inv.ExpiryLockService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 效期锁定管理实现（4.10.2，spec expiry-management 需求②③）。
 * 人工锁 = 收紧侧即时生效免审批；解除唯一入口 = 4.10.3 评估放行（全链不变量）。
 */
@Slf4j
@Service
public class ExpiryLockServiceImpl implements ExpiryLockService {

    private final InvBatchDao batchDao;
    private final InvStockDao stockDao;
    private final ExpiryLockLogDao lockLogDao;
    private final BatchRecommendService batchRecommendService;
    private final SysParamService sysParamService;

    public ExpiryLockServiceImpl(InvBatchDao batchDao, InvStockDao stockDao,
                                 ExpiryLockLogDao lockLogDao,
                                 BatchRecommendService batchRecommendService,
                                 SysParamService sysParamService) {
        this.batchDao = batchDao;
        this.stockDao = stockDao;
        this.lockLogDao = lockLogDao;
        this.batchRecommendService = batchRecommendService;
        this.sysParamService = sysParamService;
    }

    @Override
    @Transactional
    public Map<String, Object> manualLock(String batchNo, String itemCode, String reason) {
        requireWarehouse("人工锁定");
        if (isBlank(batchNo) || isBlank(itemCode)) {
            throw new ServiceException(422, "批次与物料必填");
        }
        if (isBlank(reason)) {
            throw new ServiceException(422, "锁定原因必填");
        }
        InvBatch b = requireBatch(batchNo.trim(), itemCode.trim());
        if ("1".equals(str(b.getExpiryLockFlag()))) {
            throw new ServiceException(422, "批次已处于锁定状态（来源 "
                    + str(b.getLockSource()) + "），不可重复锁定");
        }
        // 点更新绕乐观锁 + 行锁防并发（同批次仅此一次置位）
        int rows = batchDao.update(null, new LambdaUpdateWrapper<InvBatch>()
                .eq(InvBatch::getId, b.getId())
                .eq(InvBatch::getExpiryLockFlag, "0")
                .set(InvBatch::getExpiryLockFlag, "1")
                .set(InvBatch::getLockSource, InvBatch.SRC_MANUAL)
                .setSql("VER_NO = VER_NO + 1"));
        if (rows == 0) {
            throw new ServiceException(409, "人工锁定并发冲突，请刷新重试");
        }
        String me = currentUser();
        lockLogDao.insert(ExpiryLockLog.of(b, "0", "1",
                ExpiryLockLog.SRC_MANUAL, me, reason.trim()));
        log.info("manual lock {} {} by {}: {}", b.getBatchNo(), b.getItemCode(), me, reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchNo", b.getBatchNo());
        out.put("itemCode", b.getItemCode());
        out.put("lockSource", InvBatch.SRC_MANUAL);
        out.put("operator", me);
        return out;
    }

    @Override
    public Map<String, Object> ledger(String itemCode, String warehouseCode, String lockSource,
                                      long current, long size) {
        // 仓库维度经库存行连接（批次表无仓库列）
        java.util.Set<String> whBatches = null;
        if (!isBlank(warehouseCode)) {
            whBatches = new java.util.HashSet<>();
            for (InvStock s : stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getWarehouseCode, warehouseCode.trim()))) {
                whBatches.add(str(s.getBatchNo()));
            }
            if (whBatches.isEmpty()) {
                return emptyPage(current, size);
            }
        }
        // 注意：MP 条件三元仍会求值参数 → 必须先 trimOrNull（同 BatchRecommendServiceImpl 范式）
        String item = trimOrNull(itemCode);
        String source = trimOrNull(lockSource);
        LambdaQueryWrapper<InvBatch> qw = new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getExpiryLockFlag, "1")
                .eq(item != null, InvBatch::getItemCode, item)
                .eq(source != null, InvBatch::getLockSource, source)
                .in(whBatches != null, InvBatch::getBatchNo, whBatches)
                .orderByDesc(InvBatch::getExpiryDate);
        Page<InvBatch> p = batchDao.selectPage(new Page<>(Math.max(current, 1),
                Math.max(size, 1)), qw);
        LocalDate today = LocalDate.now();
        BigDecimal ratio = sysParamService.getRate("EXPIRY_LOCK_RATIO", new BigDecimal("0.5"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (InvBatch b : p.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("batchNo", b.getBatchNo());
            m.put("itemCode", b.getItemCode());
            m.put("itemName", b.getItemName());
            m.put("expiryDate", b.getExpiryDate());
            m.put("productionDate", b.getProductionDate());
            m.put("lockSource", isBlank(b.getLockSource()) ? InvBatch.SRC_AUTO : b.getLockSource());
            m.put("evalExemptUntil", b.getEvalExemptUntil());
            m.put("evalExemptId", b.getEvalExemptId());
            if (b.getExpiryDate() != null) {
                m.put("remainingDays", ChronoUnit.DAYS.between(today, b.getExpiryDate()));
            }
            if (b.getProductionDate() != null && b.getExpiryDate() != null) {
                long total = ChronoUnit.DAYS.between(b.getProductionDate(), b.getExpiryDate());
                m.put("lockThresholdDays", total > 0
                        ? BigDecimal.valueOf(total).multiply(ratio)
                                .setScale(0, java.math.RoundingMode.DOWN).longValue()
                        : null);
            }
            out.add(m);
        }
        Map<String, Object> page = new LinkedHashMap<>();
        page.put("records", out);
        page.put("total", p.getTotal());
        return page;
    }

    @Override
    public List<Map<String, Object>> history(String batchNo, String itemCode, long size) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExpiryLockLog l : lockLogDao.selectList(new LambdaQueryWrapper<ExpiryLockLog>()
                .eq(!isBlank(batchNo), ExpiryLockLog::getBatchNo, batchNo == null ? null : batchNo.trim())
                .eq(!isBlank(itemCode), ExpiryLockLog::getItemCode, itemCode == null ? null : itemCode.trim())
                .orderByDesc(ExpiryLockLog::getChangeAt)
                .last("LIMIT " + Math.max(size, 1)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("batchNo", l.getBatchNo());
            m.put("itemCode", l.getItemCode());
            m.put("fromFlag", l.getFromFlag());
            m.put("toFlag", l.getToFlag());
            m.put("source", l.getSource());
            m.put("operator", l.getOperator());
            m.put("reason", l.getReason());
            m.put("changeAt", l.getChangeAt());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> triggerScan() {
        requireWarehouse("手动触发扫描");
        int changed = batchRecommendService.scanExpiryLock();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("changed", changed);
        return out;
    }

    // ---------- helpers ----------

    private InvBatch requireBatch(String batchNo, String itemCode) {
        List<InvBatch> rows = batchDao.selectList(new LambdaQueryWrapper<InvBatch>()
                .eq(InvBatch::getBatchNo, batchNo)
                .eq(InvBatch::getItemCode, itemCode)
                .last("FOR UPDATE"));
        if (rows.isEmpty()) {
            throw new ServiceException(422, "批次台账不存在：" + itemCode + "/" + batchNo);
        }
        return rows.get(0);
    }

    private Map<String, Object> emptyPage(long current, long size) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("records", List.of());
        m.put("total", 0L);
        return m;
    }

    private void requireWarehouse(String action) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r)
                || "ROLE_WAREHOUSE".equalsIgnoreCase(r))) {
            return;
        }
        throw new ServiceException(403, action + "：需要仓库主管或管理员角色");
    }

    private String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    /** 空串归一 null（MP 条件三元仍求值参数，条件筛选必须先归一） */
    private static String trimOrNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
