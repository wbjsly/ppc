package com.erp.service.impl.vmi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.proc.ReplenishConfirmDao;
import com.erp.dao.vmi.VmiAlertDao;
import com.erp.dao.vmi.VmiStockDao;
import com.erp.entity.proc.ReplenishConfirm;
import com.erp.entity.vmi.VmiAgreementLine;
import com.erp.entity.vmi.VmiAlert;
import com.erp.entity.vmi.VmiStock;
import com.erp.service.portal.PortalEventService;
import com.erp.service.proc.AsnService;
import com.erp.service.vmi.VmiAlertService;
import com.erp.service.vmi.VmiAgreementService;
import com.erp.service.vmi.VmiPortalSyncService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * VMI 门户同步实现（spec vmi-portal-sync，design D9/D10）。
 * 水位同步 = 惰性扫描（页面加载触发），批次载体为 VMI.WATER_SYNCED outbox 事件。
 */
@Slf4j
@Service
public class VmiPortalSyncServiceImpl implements VmiPortalSyncService {

    private final VmiStockDao stockDao;
    private final VmiAlertDao alertDao;
    private final ReplenishConfirmDao confirmDao;
    private final VmiAgreementService agreementService;
    private final VmiAlertService alertService;
    private final AsnService asnService;
    private final PortalEventService eventService;

    public VmiPortalSyncServiceImpl(VmiStockDao stockDao,
                                    VmiAlertDao alertDao,
                                    ReplenishConfirmDao confirmDao,
                                    VmiAgreementService agreementService,
                                    VmiAlertService alertService,
                                    AsnService asnService,
                                    PortalEventService eventService) {
        this.stockDao = stockDao;
        this.alertDao = alertDao;
        this.confirmDao = confirmDao;
        this.agreementService = agreementService;
        this.alertService = alertService;
        this.asnService = asnService;
        this.eventService = eventService;
    }

    // ---------- 水位同步 ----------

    @Override
    @Transactional
    public Map<String, Object> scanWater(String supplierId) {
        Set<String> suppliers = new LinkedHashSet<>();
        if (StringUtils.hasText(supplierId)) {
            suppliers.add(supplierId.trim());
        } else {
            for (VmiStock st : stockDao.selectList(
                    new LambdaQueryWrapper<VmiStock>().select(VmiStock::getSupplierId))) {
                if (StringUtils.hasText(st.getSupplierId())) {
                    suppliers.add(st.getSupplierId());
                }
            }
        }
        List<Map<String, Object>> results = new ArrayList<>();
        boolean anyWritten = false;
        LocalDateTime syncTime = LocalDateTime.now();
        for (String sid : suppliers) {
            List<Map<String, Object>> items = new ArrayList<>();
            List<VmiStock> stocks = stockDao.selectList(new LambdaQueryWrapper<VmiStock>()
                    .eq(VmiStock::getSupplierId, sid));
            for (VmiStock st : stocks) {
                VmiAgreementLine line = agreementService.lineFor(sid, st.getItemCode());
                if (line == null) {
                    continue;   // 无生效协议水位 → 不参与同步
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("itemCode", st.getItemCode());
                item.put("itemName", st.getItemName());
                item.put("qty", st.getQty());
                item.put("min", line.getMinQty());
                item.put("max", line.getMaxQty());
                String status = "OK";
                boolean need = false;
                if (line.getMinQty() != null && st.getQty() != null
                        && st.getQty().compareTo(line.getMinQty()) < 0) {
                    status = "LOW";
                    need = true;
                } else if (line.getMaxQty() != null && line.getMaxQty().signum() > 0
                        && st.getQty() != null && st.getQty().compareTo(line.getMaxQty()) > 0) {
                    status = "HIGH";
                }
                item.put("status", status);
                item.put("needReplenish", need);
                items.add(item);
            }
            String hash = md5(canonical(items));
            boolean written = eventService.waterSynced(sid, hash, items);
            anyWritten |= written;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("supplierId", sid);
            r.put("syncTime", syncTime);
            r.put("written", written);
            r.put("snapshotHash", hash);
            r.put("items", items);
            results.add(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("syncTime", syncTime);
        out.put("written", anyWritten);
        out.put("suppliers", results);
        return out;
    }

    // ---------- 补货确认 ----------

    @Override
    @Transactional
    public Map<String, Object> confirmReplenish(String alertId, Map<String, Object> payload, String source) {
        VmiAlert alert = alertDao.selectById(alertId);
        if (alert == null || !VmiAlert.TYPE_REPLENISH.equals(alert.getAlertType())) {
            throw new ServiceException(404, "补货建议不存在");
        }
        if (!VmiAlert.ST_OPEN.equals(alert.getStatus())) {
            throw new ServiceException(422, "该补货建议不可确认（当前 " + alert.getStatus() + "，重复确认幂等拒绝）");
        }
        String supplierId = alert.getSupplierId();
        BigDecimal liveStock = stockSum(supplierId, alert.getItemCode());
        BigDecimal min = alert.getLimitQty() == null ? BigDecimal.ZERO : alert.getLimitQty();
        BigDecimal suggest = min.subtract(liveStock);
        if (suggest.signum() <= 0) {
            throw new ServiceException(422, "寄售库存已回升至最低水位，建议失效：" + alert.getItemCode());
        }
        // 最高水位约束（协议 MAX 存在时）
        BigDecimal max = null;
        VmiAgreementLine line = agreementService.lineFor(supplierId, alert.getItemCode());
        if (line != null && line.getMaxQty() != null && line.getMaxQty().signum() > 0) {
            max = line.getMaxQty();
        }
        BigDecimal confirmQty = payloadQty(payload, "confirmQty");
        if (confirmQty == null) {
            confirmQty = suggest;
        }
        if (confirmQty.signum() <= 0) {
            throw new ServiceException(422, "确认量必须大于 0");
        }
        if (max != null) {
            BigDecimal cap = max.subtract(liveStock);
            if (confirmQty.compareTo(cap) > 0) {
                throw new ServiceException(422, "确认量 " + confirmQty.stripTrailingZeros().toPlainString()
                        + " 将突破协议最高水位 " + max.stripTrailingZeros().toPlainString()
                        + "（当前库存 " + liveStock.stripTrailingZeros().toPlainString() + "）");
            }
        }

        String operator = payloadStr(payload, "operatorName");
        if (!StringUtils.hasText(operator)) {
            operator = SecurityUtils.getCurrentUserId();
        }
        String unit = line == null ? null : line.getUnit();
        String itemName = alert.getItemName() != null ? alert.getItemName()
                : (line == null ? null : line.getItemName());

        // 联动创建 REPLENISH ASN（design D9：PO 可空）
        com.erp.entity.proc.Asn asn = asnService.createReplenish(supplierId, alert.getSupplierName(),
                alert.getItemCode(), itemName, unit, confirmQty);

        ReplenishConfirm c = new ReplenishConfirm();
        c.setAlertId(alertId);
        c.setSupplierId(supplierId);
        c.setItemCode(alert.getItemCode());
        c.setSuggestQty(suggest);
        c.setConfirmQty(confirmQty);
        c.setAsnId(asn.getId());
        c.setSource(source);
        c.setConfirmBy(operator);
        c.setConfirmAt(LocalDateTime.now());
        c.setRemark(payloadStr(payload, "remark"));
        c.setCreateBy(operator);
        try {
            confirmDao.insert(c);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "该补货建议已确认（UK_REPLENISH_ALERT 幂等）");
        }

        alert.setStatus(VmiAlert.ST_CONFIRMED);
        alert.setConfirmBy(operator);
        alert.setConfirmAt(LocalDateTime.now());
        alert.setUpdateBy(operator);
        alertDao.updateById(alert);

        try {
            eventService.replenishConfirmed(alertId, supplierId, alert.getItemCode(),
                    confirmQty, asn.getAsnNo());
        } catch (ServiceException e) {
            // 同键事件已存在（跨轮重复，C-0-06）：确认业务照常生效
            log.warn("补货确认事件跳过 alert={}: {}", alertId, e.getMessage());
        }
        log.info("VMI 补货建议 {} 确认 {}（建议 {}）→ ASN {} 来源 {}",
                alert.getAlertNo(), confirmQty, suggest, asn.getAsnNo(), source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("alertId", alertId);
        out.put("suggestQty", suggest);
        out.put("confirmQty", confirmQty);
        out.put("asnNo", asn.getAsnNo());
        out.put("source", source);
        return out;
    }

    @Override
    public Page<Map<String, Object>> alerts(long current, long size, String alertType,
                                            String status, String supplierId) {
        return alertService.page(current, size, alertType, status, supplierId);
    }

    // ---------- 帮助 ----------

    private BigDecimal stockSum(String supplierId, String itemCode) {
        BigDecimal t = BigDecimal.ZERO;
        for (VmiStock st : stockDao.selectList(new LambdaQueryWrapper<VmiStock>()
                .eq(VmiStock::getSupplierId, supplierId)
                .eq(VmiStock::getItemCode, itemCode))) {
            if (st.getQty() != null) {
                t = t.add(st.getQty());
            }
        }
        return t;
    }

    private static String canonical(List<Map<String, Object>> items) {
        StringBuilder sb = new StringBuilder();
        items.stream()
                .sorted((a, b) -> String.valueOf(a.get("itemCode"))
                        .compareTo(String.valueOf(b.get("itemCode"))))
                .forEach(i -> sb.append(i.get("itemCode")).append('=')
                        .append(i.get("qty")).append(':').append(i.get("status")).append(';'));
        return sb.toString();
    }

    private static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private static String payloadStr(Map<String, Object> payload, String key) {
        if (payload == null || payload.get(key) == null) {
            return null;
        }
        String s = String.valueOf(payload.get(key)).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal payloadQty(Map<String, Object> payload, String key) {
        String s = payloadStr(payload, key);
        if (s == null) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式错误：" + s);
        }
    }
}
