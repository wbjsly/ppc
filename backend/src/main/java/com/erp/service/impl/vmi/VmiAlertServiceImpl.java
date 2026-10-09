package com.erp.service.impl.vmi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.vmi.VmiAgreementDao;
import com.erp.dao.vmi.VmiAgreementLineDao;
import com.erp.dao.vmi.VmiAlertDao;
import com.erp.dao.vmi.VmiStockDao;
import com.erp.entity.vmi.VmiAgreement;
import com.erp.entity.vmi.VmiAgreementLine;
import com.erp.entity.vmi.VmiAlert;
import com.erp.entity.vmi.VmiStock;
import com.erp.service.vmi.VmiAgreementService;
import com.erp.service.vmi.VmiAlertService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** VMI 告警/建议实现（spec vmi-consignment，design D3/D4）。 */
@Slf4j
@Service
public class VmiAlertServiceImpl implements VmiAlertService {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");

    private final VmiAlertDao alertDao;
    private final VmiStockDao stockDao;
    private final VmiAgreementDao agreeDao;
    private final VmiAgreementLineDao agreeLineDao;
    private final VmiAgreementService agreementService;
    /** 补货建议推送事件（spec vmi-portal-sync：VMI.REPLENISH_PUSHED） */
    private final com.erp.service.portal.PortalEventService portalEventService;
    /** 告警独立事务（REQUIRES_NEW）：422 回滚不得吞掉已创建的告警，否则确认放行流程死锁（项目先例：先提交后抛错） */
    private final org.springframework.transaction.support.TransactionTemplate alertTx;

    public VmiAlertServiceImpl(VmiAlertDao alertDao,
                               VmiStockDao stockDao,
                               VmiAgreementDao agreeDao,
                               VmiAgreementLineDao agreeLineDao,
                               VmiAgreementService agreementService,
                               com.erp.service.portal.PortalEventService portalEventService,
                               org.springframework.transaction.PlatformTransactionManager txManager) {
        this.alertDao = alertDao;
        this.stockDao = stockDao;
        this.agreeDao = agreeDao;
        this.agreeLineDao = agreeLineDao;
        this.agreementService = agreementService;
        this.portalEventService = portalEventService;
        this.alertTx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.alertTx.setPropagationBehaviorName("PROPAGATION_REQUIRES_NEW");
    }

    // ---------- 水位（BR-4.2-36） ----------

    @Override
    public void assertWaterLevel(String supplierId, Map<String, BigDecimal> incomingByItem,
                                 boolean confirm) {
        if (incomingByItem == null || incomingByItem.isEmpty()) {
            return;
        }
        List<String> blocked = new ArrayList<>();
        Map<String, VmiAlert> pending = new LinkedHashMap<>();
        for (Map.Entry<String, BigDecimal> e : incomingByItem.entrySet()) {
            String item = e.getKey();
            VmiAgreementLine line = agreementService.lineFor(supplierId, item);
            BigDecimal max = line == null ? null : line.getMaxQty();
            if (max == null || max.signum() <= 0) {
                continue;   // 未约定最高水位 → 不限
            }
            BigDecimal stock = stockSum(supplierId, item);
            BigDecimal incoming = e.getValue() == null ? BigDecimal.ZERO : e.getValue();
            if (stock.add(incoming).compareTo(max) <= 0) {
                continue;
            }
            if (confirm && hasConfirmedWaterAlert(supplierId, item)) {
                continue;   // 采购员已确认放行（一次性额度，过账成功后消费）
            }
            blocked.add(item);
            pending.put(item, buildOpenWaterAlert(supplierId, item, line, stock, max));
        }
        if (!blocked.isEmpty()) {
            // 告警先在独立事务提交，再由调用方事务回滚 422 —— 否则告警被吞、无法确认放行
            alertTx.executeWithoutResult(status -> {
                for (VmiAlert a : pending.values()) {
                    saveOpenWaterAlert(a);
                }
            });
            List<String> nos = new ArrayList<>();
            for (String item : blocked) {
                VmiAlert a = pending.get(item);
                if (a != null) {
                    nos.add(item + "（" + a.getAlertNo() + "）");
                }
            }
            throw new ServiceException(422, "寄售库存超最高水位，暂停收货（BR-4.2-36）："
                    + String.join("、", nos)
                    + "。请采购员在告警中确认放行后携带 confirmWaterLevel=true 重试");
        }
    }

    @Override
    @Transactional
    public void consumeConfirmed(String supplierId, Collection<String> itemCodes) {
        if (itemCodes == null || itemCodes.isEmpty()) {
            return;
        }
        int n = alertDao.update(null, new LambdaUpdateWrapper<VmiAlert>()
                .eq(VmiAlert::getAlertType, VmiAlert.TYPE_WATER_HIGH)
                .eq(VmiAlert::getStatus, VmiAlert.ST_CONFIRMED)
                .eq(VmiAlert::getSupplierId, supplierId)
                .in(VmiAlert::getItemCode, itemCodes)
                .set(VmiAlert::getStatus, VmiAlert.ST_RESOLVED));
        if (n > 0) {
            log.info("VMI 水位放行额度已消费 supplier={} items={} alerts={}", supplierId, itemCodes, n);
        }
    }

    @Override
    @Transactional
    public VmiAlert confirm(String alertId, String opinion) {
        VmiAlert a = alertDao.selectById(alertId);
        if (a == null) {
            throw new ServiceException(404, "告警不存在：" + alertId);
        }
        if (!VmiAlert.TYPE_WATER_HIGH.equals(a.getAlertType())) {
            throw new ServiceException(422, "仅超水位告警需确认放行（补货建议由库存回升自动关闭）");
        }
        if (!VmiAlert.ST_OPEN.equals(a.getStatus())) {
            throw new ServiceException(422, "仅 OPEN 状态可确认，当前 " + a.getStatus());
        }
        a.setStatus(VmiAlert.ST_CONFIRMED);
        a.setConfirmBy(SecurityUtils.getCurrentUserId());
        a.setConfirmAt(LocalDateTime.now());
        a.setConfirmOpinion(opinion);
        a.setUpdateBy(SecurityUtils.getCurrentUserId());
        alertDao.updateById(a);
        log.info("VMI 水位告警 {} confirmed by {}", a.getAlertNo(), a.getConfirmBy());
        return a;
    }

    // ---------- 补货建议（FR-4.2-8-1 低于最低水位，design D4 惰性） ----------

    @Override
    @Transactional
    public void scanReplenish() {
        LocalDate today = LocalDate.now();
        List<VmiAgreement> actives = agreeDao.selectList(new LambdaQueryWrapper<VmiAgreement>()
                .eq(VmiAgreement::getStatus, VmiAgreement.ST_EFFECTIVE));
        for (VmiAgreement a : actives) {
            if ((a.getEffectiveDate() != null && today.isBefore(a.getEffectiveDate()))
                    || (a.getExpireDate() != null && today.isAfter(a.getExpireDate()))) {
                continue;
            }
            List<VmiAgreementLine> lines = agreeLineDao.selectList(
                    new LambdaQueryWrapper<VmiAgreementLine>()
                            .eq(VmiAgreementLine::getAgreeId, a.getId()));
            for (VmiAgreementLine l : lines) {
                if (l.getMinQty() == null || l.getMinQty().signum() <= 0) {
                    continue;
                }
                BigDecimal stock = stockSum(a.getSupplierId(), l.getItemCode());
                VmiAlert open = openReplenish(a.getSupplierId(), l.getItemCode());
                if (stock.compareTo(l.getMinQty()) < 0) {
                    if (open == null) {
                        VmiAlert alert = newAlert(VmiAlert.TYPE_REPLENISH, a, l,
                                stock, l.getMinQty());
                        alertDao.insert(alert);
                        try {
                            portalEventService.replenishPushed(alert.getId(),
                                    a.getSupplierId(), l.getItemCode(), l.getMinQty());
                        } catch (com.erp.common.ServiceException e) {
                            log.warn("补货建议推送事件跳过 {}: {}", alert.getAlertNo(), e.getMessage());
                        }
                        log.info("VMI 补货建议 {} 生成 item={} stock={} min={}",
                                alert.getAlertNo(), l.getItemCode(), stock, l.getMinQty());
                    }
                } else if (open != null) {
                    open.setStatus(VmiAlert.ST_RESOLVED);
                    open.setUpdateBy(SecurityUtils.getCurrentUserId());
                    alertDao.updateById(open);
                    log.info("VMI 补货建议 {} 库存回升关闭 item={} stock={}",
                            open.getAlertNo(), l.getItemCode(), stock);
                }
            }
        }
    }

    // ---------- 查询 ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String alertType,
                                          String status, String supplierId) {
        Page<VmiAlert> p = alertDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<VmiAlert>()
                        .eq(hasText(alertType), VmiAlert::getAlertType, alertType)
                        .eq(hasText(status), VmiAlert::getStatus, status)
                        .eq(hasText(supplierId), VmiAlert::getSupplierId, supplierId)
                        .orderByDesc(VmiAlert::getCreateDate));
        Page<Map<String, Object>> out = new Page<>(p.getCurrent(), p.getSize(), p.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (VmiAlert a : p.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("alertNo", a.getAlertNo());
            row.put("alertType", a.getAlertType());
            row.put("agreeNo", a.getAgreeNo());
            row.put("itemCode", a.getItemCode());
            row.put("itemName", a.getItemName());
            row.put("supplierId", a.getSupplierId());
            row.put("supplierName", a.getSupplierName());
            row.put("currentQty", a.getCurrentQty());
            row.put("limitQty", a.getLimitQty());
            row.put("status", a.getStatus());
            row.put("confirmBy", a.getConfirmBy());
            row.put("confirmAt", a.getConfirmAt());
            row.put("confirmOpinion", a.getConfirmOpinion());
            row.put("createDate", a.getCreateDate());
            rows.add(row);
        }
        out.setRecords(rows);
        return out;
    }

    // ---------- 私有 ----------

    /** 构建 OPEN 超水位告警（已存在 OPEN 则复用更新，去重）—— 不落库，由 alertTx 统一提交 */
    private VmiAlert buildOpenWaterAlert(String supplierId, String item,
                                         VmiAgreementLine line, BigDecimal stock, BigDecimal max) {
        VmiAgreement a = line == null ? null : agreeDao.selectById(line.getAgreeId());
        VmiAlert open = alertDao.selectOne(new LambdaQueryWrapper<VmiAlert>()
                .eq(VmiAlert::getAlertType, VmiAlert.TYPE_WATER_HIGH)
                .eq(VmiAlert::getStatus, VmiAlert.ST_OPEN)
                .eq(VmiAlert::getSupplierId, supplierId)
                .eq(VmiAlert::getItemCode, item)
                .last("LIMIT 1"));
        if (open != null) {
            open.setCurrentQty(stock);
            open.setLimitQty(max);
            return open;
        }
        VmiAlert alert = newAlert(VmiAlert.TYPE_WATER_HIGH, a, line, stock, max);
        if (alert.getSupplierId() == null) {
            alert.setSupplierId(supplierId);
        }
        return alert;
    }

    /** alertTx 内执行：已存在主键则更新，否则插入 */
    private void saveOpenWaterAlert(VmiAlert a) {
        if (a.getId() != null && alertDao.selectById(a.getId()) != null) {
            alertDao.updateById(a);
        } else {
            alertDao.insert(a);
        }
    }

    private VmiAlert newAlert(String type, VmiAgreement a, VmiAgreementLine l,
                              BigDecimal current, BigDecimal limit) {
        VmiAlert alert = new VmiAlert();
        alert.setAlertNo(nextAlertNo());
        alert.setAlertType(type);
        if (a != null) {
            alert.setAgreeId(a.getId());
            alert.setAgreeNo(a.getAgreeNo());
            alert.setSupplierId(a.getSupplierId());
            alert.setSupplierName(a.getSupplierName());
        }
        alert.setItemCode(l.getItemCode());
        alert.setItemName(l.getItemName());
        alert.setCurrentQty(current);
        alert.setLimitQty(limit);
        alert.setStatus(VmiAlert.ST_OPEN);
        alert.setCreateBy(SecurityUtils.getCurrentUserId());
        return alert;
    }

    private boolean hasConfirmedWaterAlert(String supplierId, String item) {
        Long n = alertDao.selectCount(new LambdaQueryWrapper<VmiAlert>()
                .eq(VmiAlert::getAlertType, VmiAlert.TYPE_WATER_HIGH)
                .eq(VmiAlert::getStatus, VmiAlert.ST_CONFIRMED)
                .eq(VmiAlert::getSupplierId, supplierId)
                .eq(VmiAlert::getItemCode, item));
        return n != null && n > 0;
    }

    private VmiAlert openReplenish(String supplierId, String item) {
        return alertDao.selectOne(new LambdaQueryWrapper<VmiAlert>()
                .eq(VmiAlert::getAlertType, VmiAlert.TYPE_REPLENISH)
                .eq(VmiAlert::getStatus, VmiAlert.ST_OPEN)
                .eq(VmiAlert::getSupplierId, supplierId)
                .eq(VmiAlert::getItemCode, item)
                .last("LIMIT 1"));
    }

    private BigDecimal stockSum(String supplierId, String item) {
        List<VmiStock> rows = stockDao.selectList(new LambdaQueryWrapper<VmiStock>()
                .eq(VmiStock::getSupplierId, supplierId)
                .eq(VmiStock::getItemCode, item));
        BigDecimal sum = BigDecimal.ZERO;
        for (VmiStock s : rows) {
            sum = sum.add(s.getQty() == null ? BigDecimal.ZERO : s.getQty());
        }
        return sum;
    }

    private String nextAlertNo() {
        String prefix = "VA" + LocalDateTime.now().format(MONTH) + "-";
        Integer max = alertDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
