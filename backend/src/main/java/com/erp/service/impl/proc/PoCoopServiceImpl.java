package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.proc.PoCoopLogDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.entity.proc.PoCoopLog;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.ops.PortalEvents;
import com.erp.service.portal.PortalEventService;
import com.erp.service.proc.PoCoopService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PO 交期协同实现（spec po-collaboration，design D4 惰性判定 / D5 锁确认入口）。
 */
@Slf4j
@Service
public class PoCoopServiceImpl implements PoCoopService {

    private final PoCoopLogDao coopDao;
    private final PurchaseOrderDao poDao;
    private final PurchaseOrderLineDao lineDao;
    private final PortalEventService eventService;

    /** 确认时限（默认 48h，spec po-collaboration） */
    @Value("${app.proc.po-confirm-hours:48}")
    private long confirmHours;

    /** 升级时限（默认 72h） */
    @Value("${app.proc.po-escalate-hours:72}")
    private long escalateHours;

    public PoCoopServiceImpl(PoCoopLogDao coopDao,
                             PurchaseOrderDao poDao,
                             PurchaseOrderLineDao lineDao,
                             PortalEventService eventService) {
        this.coopDao = coopDao;
        this.poDao = poDao;
        this.lineDao = lineDao;
        this.eventService = eventService;
    }

    // ---------- 下达推送 ----------

    @Override
    @Transactional
    public void push(PurchaseOrder po) {
        String versionTag = "v" + (po.getCurrVersion() == null ? 1 : po.getCurrVersion());
        // 同版本重复推送幂等（变更未升版等场景），避免重置已确认状态
        Long sameVersion = coopDao.selectCount(new LambdaQueryWrapper<PoCoopLog>()
                .eq(PoCoopLog::getPoId, po.getId())
                .eq(PoCoopLog::getActionType, PoCoopLog.ACT_PUSHED)
                .like(PoCoopLog::getDetail, versionTag));
        if (sameVersion != null && sameVersion > 0) {
            log.info("PO {} 同版本 {} 推送幂等跳过", po.getPoNo(), versionTag);
            return;
        }
        write(po, PoCoopLog.ACT_PUSHED, PoCoopLog.SRC_SYSTEM, null, versionTag + " 下达推送", null, null);
        po.setConfirmStatus("PUSHED");
        po.setCoopLock(supplierLocked(po.getSupplierId()) ? "1" : "0");
        po.setUpdateBy(SecurityUtils.getCurrentUserId());
        poDao.updateById(po);
        try {
            eventService.poPushed(po);
        } catch (ServiceException e) {
            // 幂等键重复（C-0-06）：同版本事件已在，跳过不阻断审批
            log.warn("PO {} 推送事件跳过: {}", po.getPoNo(), e.getMessage());
        }
        // 新推送后做一次锁定评估（该供应商历史超时单已满足 3 张时立即锁）
        lockIfNeeded(po);
    }

    // ---------- 确认 / 改期 ----------

    @Override
    @Transactional
    public Map<String, Object> confirm(String poId, Map<String, Object> payload, String source) {
        PurchaseOrder po = requireApprovable(poId);
        if (supplierLocked(po.getSupplierId())) {
            throw new ServiceException(422, "该供应商确认入口已锁定（连续 3 张 PO 超时未确认），"
                    + "请联系采购经理在协同异常工单中解锁");
        }
        ensureTimeout(po);
        LocalDate promise = payloadDate(payload, "promiseDate");
        if (promise == null) {
            promise = originalPromiseDate(poId);
        }
        if (promise == null) {
            throw new ServiceException(422, "无法确定承诺交期（PO 行无需求日期），请显式提供 promiseDate");
        }
        String operator = payloadStr(payload, "operatorName");
        if (!StringUtils.hasText(operator)) {
            operator = SecurityUtils.getCurrentUserId();
        }
        // seq 先于写行计算（否则把本行也计入 → 每次 v+1 → 幂等键漂移）
        int seq = confirmSeq(poId);
        write(po, PoCoopLog.ACT_CONFIRM, source, promise,
                "交期确认（" + source + "）", payloadStr(payload, "remark"), operator);
        po.setPromiseDate(promise);
        po.setConfirmStatus("CONFIRMED");
        po.setUpdateBy(operator);
        poDao.updateById(po);
        try {
            eventService.poConfirmed(po.getPoNo(), seq, po.getSupplierId(),
                    promise.toString(), source);
        } catch (ServiceException e) {
            // 同版本事件已在（跨轮/重复确认，C-0-06）：业务照常生效，不回滚
            log.warn("PO {} 确认事件跳过: {}", po.getPoNo(), e.getMessage());
        }
        log.info("PO {} 交期确认 promise={} source={}", po.getPoNo(), promise, source);
        return row(po, "CONFIRMED", promise);
    }

    @Override
    @Transactional
    public Map<String, Object> changeRequest(String poId, Map<String, Object> payload) {
        PurchaseOrder po = requireApprovable(poId);
        if (supplierLocked(po.getSupplierId())) {
            throw new ServiceException(422, "该供应商确认入口已锁定，请先解锁");
        }
        LocalDate promise = payloadDate(payload, "promiseDate");
        if (promise == null) {
            throw new ServiceException(422, "改期申请须提供 promiseDate（新承诺交期）");
        }
        String source = payloadStr(payload, "source");
        if (!StringUtils.hasText(source)) {
            source = PoCoopLog.SRC_PORTAL;
        }
        String operator = payloadStr(payload, "operatorName");
        if (!StringUtils.hasText(operator)) {
            operator = SecurityUtils.getCurrentUserId();
        }
        String qtyNote = payloadStr(payload, "qtyNote");
        String detail = "申请改期至 " + promise
                + (StringUtils.hasText(qtyNote) ? "；数量建议：" + qtyNote : "");
        write(po, "CHANGE_REQUEST", source, promise, detail, payloadStr(payload, "remark"), operator);
        po.setConfirmStatus("CHANGE_PENDING");
        po.setUpdateBy(operator);
        poDao.updateById(po);
        log.info("PO {} 交期变更申请 promise={}（待采购员处理）", po.getPoNo(), promise);
        return row(po, "CHANGE_PENDING", promise);
    }

    @Override
    @Transactional
    public Map<String, Object> acceptChange(String poId, Map<String, Object> payload) {
        PurchaseOrder po = requireApprovable(poId);
        if (!"CHANGE_PENDING".equals(po.getConfirmStatus())) {
            throw new ServiceException(422, "该 PO 无待处理的交期变更申请（当前 "
                    + po.getConfirmStatus() + "）");
        }
        LocalDate promise = payloadDate(payload, "promiseDate");
        if (promise == null) {
            throw new ServiceException(422, "接受改期须提供最终 promiseDate");
        }
        String operator = SecurityUtils.getCurrentUserId();
        int seq = confirmSeq(poId);
        write(po, PoCoopLog.ACT_CHANGE_ACCEPTED, PoCoopLog.SRC_SYSTEM, promise,
                "采购员接受改期", payloadStr(payload, "remark"), operator);
        po.setPromiseDate(promise);
        po.setConfirmStatus("CONFIRMED");
        po.setUpdateBy(operator);
        poDao.updateById(po);
        try {
            eventService.poConfirmed(po.getPoNo(), seq, po.getSupplierId(),
                    promise.toString(), PoCoopLog.SRC_PORTAL);
        } catch (ServiceException e) {
            log.warn("PO {} 改期接受事件跳过: {}", po.getPoNo(), e.getMessage());
        }
        log.info("PO {} 改期接受 promise={}", po.getPoNo(), promise);
        return row(po, "CONFIRMED", promise);
    }

    @Override
    @Transactional
    public Map<String, Object> remind(String poId) {
        PurchaseOrder po = requireApprovable(poId);
        String operator = SecurityUtils.getCurrentUserId();
        write(po, PoCoopLog.ACT_REMIND, PoCoopLog.SRC_SYSTEM, null,
                "手工催办补发", null, operator);
        log.info("PO {} 催办补发 by {}", po.getPoNo(), operator);
        return row(po, po.getConfirmStatus(), po.getPromiseDate());
    }

    @Override
    @Transactional
    public Map<String, Object> unlock(String supplierId, String remark) {
        if (!StringUtils.hasText(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        PoCoopLog lock = latestAction(supplierId, PoCoopLog.ACT_LOCK);
        if (lock == null || !supplierLocked(supplierId)) {
            throw new ServiceException(422, "该供应商未处于锁定状态");
        }
        String operator = SecurityUtils.getCurrentUserId();
        writeById(lock.getPoId(), supplierId, PoCoopLog.ACT_UNLOCK, PoCoopLog.SRC_SYSTEM,
                null, "采购经理解锁：" + (StringUtils.hasText(remark) ? remark : "继续合作"),
                operator);
        // 清 PO 锁定标记（列表展示一致性；判定以流水为准）
        PurchaseOrder upd = new PurchaseOrder();
        upd.setCoopLock("0");
        poDao.update(upd, new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getSupplierId, supplierId)
                .eq(PurchaseOrder::getCoopLock, "1"));
        log.info("PO 协同解锁 supplier={} by {} remark={}", supplierId, operator, remark);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("supplierId", supplierId);
        m.put("locked", false);
        m.put("operator", operator);
        return m;
    }

    // ---------- 查询（惰性超时补写） ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status,
                                          String supplierId, String keyword) {
        LambdaQueryWrapper<PurchaseOrder> qw = new LambdaQueryWrapper<PurchaseOrder>()
                .orderByDesc(PurchaseOrder::getCreateDate);
        if (StringUtils.hasText(status)) {
            qw.eq(PurchaseOrder::getConfirmStatus, status);
        }
        if (StringUtils.hasText(supplierId)) {
            qw.eq(PurchaseOrder::getSupplierId, supplierId.trim());
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(PurchaseOrder::getPoNo, keyword.trim());
        }
        Page<PurchaseOrder> page = poDao.selectPage(new Page<>(current, size), qw);
        return enrich(page);
    }

    @Override
    public Page<Map<String, Object>> portalPage(long current, long size, String keyword,
                                                String supplierId) {
        LambdaQueryWrapper<PurchaseOrder> qw = new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getSupplierId, supplierId)
                .eq(PurchaseOrder::getStatus, "APPROVED")
                .orderByDesc(PurchaseOrder::getCreateDate);
        if (StringUtils.hasText(keyword)) {
            qw.like(PurchaseOrder::getPoNo, keyword.trim());
        }
        Page<PurchaseOrder> page = poDao.selectPage(new Page<>(current, size), qw);
        return enrich(page);
    }

    /** 当前页 PO 惰性补写催办/升级/锁定，并组装协同视图行（design D4/D5） */
    private Page<Map<String, Object>> enrich(Page<PurchaseOrder> page) {
        boolean lockedAny = false;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PurchaseOrder po : page.getRecords()) {
            try {
                ensureTimeout(po);
            } catch (ServiceException e) {
                log.warn("PO {} 超时补写失败: {}", po.getPoNo(), e.getMessage());
            }
            rows.add(viewRow(po));
            if (!lockedAny && supplierLocked(po.getSupplierId())) {
                lockedAny = true;
            }
        }
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(rows);
        return out;
    }

    @Override
    public List<Map<String, Object>> timeline(String poId) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<PoCoopLog> logs = coopDao.selectList(new LambdaQueryWrapper<PoCoopLog>()
                .eq(PoCoopLog::getPoId, poId)
                .orderByAsc(PoCoopLog::getActionAt));
        for (PoCoopLog l : logs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("actionType", l.getActionType());
            m.put("source", l.getSource());
            m.put("operatorName", l.getOperatorName());
            m.put("promiseDate", l.getPromiseDate());
            m.put("detail", l.getDetail());
            m.put("remark", l.getRemark());
            m.put("actionAt", l.getActionAt());
            out.add(m);
        }
        return out;
    }

    @Override
    public boolean supplierLocked(String supplierId) {
        if (!StringUtils.hasText(supplierId)) {
            return false;
        }
        PoCoopLog lock = latestAction(supplierId, PoCoopLog.ACT_LOCK);
        if (lock == null) {
            return false;
        }
        // 锁定态 = 存在 LOCK 且不存在不早于它的 UNLOCK
        // （DATETIME 秒精度下 lock/unlock 常同秒，isAfter 严格比较会误判仍锁定）
        PoCoopLog unlock = latestAction(supplierId, PoCoopLog.ACT_UNLOCK);
        return unlock == null
                || unlock.getActionAt().isBefore(lock.getActionAt());
    }

    // ---------- 内部：惰性超时与锁定 ----------

    /** 超时催办/升级（幂等：同一推送周期内已有动作行则跳过，design D4） */
    private void ensureTimeout(PurchaseOrder po) {
        if (po.getConfirmStatus() == null || "CONFIRMED".equals(po.getConfirmStatus())) {
            return;
        }
        PoCoopLog pushed = latestPoAction(po.getId(), PoCoopLog.ACT_PUSHED);
        if (pushed == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime remindDue = pushed.getActionAt().plusHours(confirmHours);
        LocalDateTime escalateDue = pushed.getActionAt().plusHours(escalateHours);
        boolean hasRemindAfter = existsAfter(po.getId(), PoCoopLog.ACT_REMIND, pushed.getActionAt());
        boolean hasEscalateAfter = existsAfter(po.getId(), PoCoopLog.ACT_ESCALATE, pushed.getActionAt());
        if (!now.isBefore(remindDue) && !hasRemindAfter) {
            write(po, PoCoopLog.ACT_REMIND, PoCoopLog.SRC_SYSTEM, null,
                    "超时自动催办（" + confirmHours + "h）", null, "system");
        }
        if (!now.isBefore(escalateDue) && !hasEscalateAfter) {
            write(po, PoCoopLog.ACT_ESCALATE, PoCoopLog.SRC_SYSTEM, null,
                    "超时自动升级采购经理（" + escalateHours + "h）", null, "system");
            if (!"ESCATED".equals(po.getConfirmStatus())) {
                po.setConfirmStatus("ESCATED");
                po.setUpdateBy("system");
                poDao.updateById(po);
            }
        }
        lockIfNeeded(po);
    }

    /** 连续 3 张超时未确认 → 锁定确认入口（design D5） */
    private void lockIfNeeded(PurchaseOrder po) {
        if (supplierLocked(po.getSupplierId())) {
            return;
        }
        // 该供应商最近有推送的 3 张 PO
        List<PoCoopLog> pushes = coopDao.selectList(new LambdaQueryWrapper<PoCoopLog>()
                .eq(PoCoopLog::getSupplierId, po.getSupplierId())
                .eq(PoCoopLog::getActionType, PoCoopLog.ACT_PUSHED)
                .orderByDesc(PoCoopLog::getActionAt)
                .last("LIMIT 12"));
        Set<String> seen = new LinkedHashSet<>();
        List<PoCoopLog> latestPushPerPo = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime due = now.minusHours(confirmHours);
        for (PoCoopLog p : pushes) {
            if (seen.add(p.getPoId())) {
                latestPushPerPo.add(p);
            }
            if (latestPushPerPo.size() >= 3) {
                break;
            }
        }
        if (latestPushPerPo.size() < 3) {
            return;
        }
        List<String> overduePoNos = new ArrayList<>();
        for (PoCoopLog p : latestPushPerPo) {
            PurchaseOrder o = poDao.selectById(p.getPoId());
            if (o == null) {
                return;
            }
            boolean overdue = !"CONFIRMED".equals(o.getConfirmStatus())
                    && !p.getActionAt().isAfter(due);
            if (!overdue) {
                return; // 三张中有一张未超时/已确认 → 不构成"连续 3 次"
            }
            overduePoNos.add(o.getPoNo());
        }
        writeById(po.getId(), po.getSupplierId(), PoCoopLog.ACT_LOCK, PoCoopLog.SRC_SYSTEM,
                null, "连续 3 张超时未确认：" + String.join("、", overduePoNos), "system");
        po.setCoopLock("1");
        po.setUpdateBy("system");
        poDao.updateById(po);
        log.warn("PO 协同锁定 supplier={} 连续超时 {}", po.getSupplierId(), overduePoNos);
    }

    // ---------- 帮助方法 ----------

    private PurchaseOrder requireApprovable(String poId) {
        PurchaseOrder po = poDao.selectById(poId);
        if (po == null) {
            throw new ServiceException(404, "采购订单不存在");
        }
        if (!"APPROVED".equals(po.getStatus())) {
            throw new ServiceException(422, "订单未下达（当前 " + po.getStatus() + "），不可进行交期协同");
        }
        return po;
    }

    private void write(PurchaseOrder po, String action, String source, LocalDate promise,
                       String detail, String remark, String operator) {
        writeById(po.getId(), po.getSupplierId(), action, source, promise, detail, remark, operator);
    }

    private void writeById(String poId, String supplierId, String action, String source,
                           LocalDate promise, String detail, String operator) {
        writeById(poId, supplierId, action, source, promise, detail, null, operator);
    }

    private void writeById(String poId, String supplierId, String action, String source,
                           LocalDate promise, String detail, String remark, String operator) {
        PoCoopLog l = new PoCoopLog();
        l.setPoId(poId);
        l.setPoNo(poNo(poId));
        l.setSupplierId(supplierId);
        l.setActionType(action);
        l.setSource(source);
        l.setPromiseDate(promise);
        l.setDetail(detail);
        l.setRemark(remark);
        l.setOperatorName(operator);
        l.setActionAt(LocalDateTime.now());
        l.setCreateBy(operator);
        coopDao.insert(l);
    }

    private String poNo(String poId) {
        PurchaseOrder po = poDao.selectById(poId);
        return po == null ? "" : po.getPoNo();
    }

    private PoCoopLog latestPoAction(String poId, String action) {
        return coopDao.selectOne(new LambdaQueryWrapper<PoCoopLog>()
                .eq(PoCoopLog::getPoId, poId)
                .eq(PoCoopLog::getActionType, action)
                .orderByDesc(PoCoopLog::getActionAt)
                .last("LIMIT 1"));
    }

    private PoCoopLog latestAction(String supplierId, String action) {
        return coopDao.selectOne(new LambdaQueryWrapper<PoCoopLog>()
                .eq(PoCoopLog::getSupplierId, supplierId)
                .eq(PoCoopLog::getActionType, action)
                .orderByDesc(PoCoopLog::getActionAt)
                .last("LIMIT 1"));
    }

    private boolean existsAfter(String poId, String action, LocalDateTime after) {
        Long n = coopDao.selectCount(new LambdaQueryWrapper<PoCoopLog>()
                .eq(PoCoopLog::getPoId, poId)
                .eq(PoCoopLog::getActionType, action)
                .gt(PoCoopLog::getActionAt, after));
        return n != null && n > 0;
    }

    private boolean isAfter(PoCoopLog a, PoCoopLog b) {
        return a != null && (b == null || a.getActionAt().isAfter(b.getActionAt()));
    }

    private int confirmSeq(String poId) {
        Long n = coopDao.selectCount(new LambdaQueryWrapper<PoCoopLog>()
                .eq(PoCoopLog::getPoId, poId)
                .in(PoCoopLog::getActionType, PoCoopLog.ACT_CONFIRM, PoCoopLog.ACT_CHANGE_ACCEPTED));
        return (n == null ? 0 : n.intValue()) + 1;
    }

    /** 原交期 = PO 行需求日期最晚者（整单到齐口径） */
    private LocalDate originalPromiseDate(String poId) {
        List<PurchaseOrderLine> lines = lineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, poId));
        LocalDate max = null;
        for (PurchaseOrderLine l : lines) {
            if (l.getReqDate() != null && (max == null || l.getReqDate().isAfter(max))) {
                max = l.getReqDate();
            }
        }
        return max;
    }

    private Map<String, Object> viewRow(PurchaseOrder po) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", po.getId());
        m.put("poNo", po.getPoNo());
        m.put("supplierId", po.getSupplierId());
        m.put("supplierName", po.getSupplierName());
        m.put("poStatus", po.getStatus());
        m.put("confirmStatus", po.getConfirmStatus());
        m.put("promiseDate", po.getPromiseDate());
        m.put("coopLock", po.getCoopLock());
        m.put("currVersion", po.getCurrVersion());
        m.put("totalAmt", po.getTotalAmt());
        m.put("locked", supplierLocked(po.getSupplierId()));
        PoCoopLog pushed = latestPoAction(po.getId(), PoCoopLog.ACT_PUSHED);
        m.put("pushedAt", pushed == null ? null : pushed.getActionAt());
        if (pushed != null && !"CONFIRMED".equals(po.getConfirmStatus())) {
            long hours = ChronoUnit.HOURS.between(pushed.getActionAt(), LocalDateTime.now());
            long overdue = hours - confirmHours;
            m.put("overdueHours", overdue > 0 ? overdue : 0);
            m.put("escalateInHours", Math.max(0, escalateHours - hours));
        } else {
            m.put("overdueHours", 0);
        }
        return m;
    }

    private Map<String, Object> row(PurchaseOrder po, String confirmStatus, LocalDate promise) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", po.getId());
        m.put("poNo", po.getPoNo());
        m.put("confirmStatus", confirmStatus);
        m.put("promiseDate", promise);
        return m;
    }

    private static String payloadStr(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return null;
        }
        String s = String.valueOf(payload.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    private static LocalDate payloadDate(Map<String, Object> payload, String key) {
        String s = payloadStr(payload, key);
        return s == null ? null : LocalDate.parse(s);
    }
}
