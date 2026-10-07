package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.proc.AsnDao;
import com.erp.dao.proc.AsnLineDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.entity.proc.Asn;
import com.erp.entity.proc.AsnLine;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.portal.PortalEventService;
import com.erp.service.proc.AsnService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ASN 实现（spec asn-collaboration，design D6 超收双签 / D7 防超发 / D8 核销）。
 */
@Slf4j
@Service
public class AsnServiceImpl implements AsnService {

    /** 审批业务类型（超收审批，design D6） */
    public static final String BIZ_ASN_OVER = "AsnOverTolerance";

    private final AsnDao asnDao;
    private final AsnLineDao lineDao;
    private final PurchaseOrderDao poDao;
    private final PurchaseOrderLineDao poLineDao;
    private final ApprovalEngine approvalEngine;
    private final PortalEventService eventService;
    /** REPLENISH ASN 核销后回写补货建议 RESOLVED（spec vmi-portal-sync 复用既有消费机制） */
    private final com.erp.dao.proc.ReplenishConfirmDao replenishConfirmDao;
    private final com.erp.dao.vmi.VmiAlertDao vmiAlertDao;

    /** 收货容差（TOLERANCE_DEFAULT，与收货/匹配同源） */
    @Value("${app.proc.receipt-tolerance:0.005}")
    private BigDecimal tolerance;

    public AsnServiceImpl(AsnDao asnDao,
                          AsnLineDao lineDao,
                          PurchaseOrderDao poDao,
                          PurchaseOrderLineDao poLineDao,
                          ApprovalEngine approvalEngine,
                          PortalEventService eventService,
                          com.erp.dao.proc.ReplenishConfirmDao replenishConfirmDao,
                          com.erp.dao.vmi.VmiAlertDao vmiAlertDao) {
        this.asnDao = asnDao;
        this.lineDao = lineDao;
        this.poDao = poDao;
        this.poLineDao = poLineDao;
        this.approvalEngine = approvalEngine;
        this.eventService = eventService;
        this.replenishConfirmDao = replenishConfirmDao;
        this.vmiAlertDao = vmiAlertDao;
    }

    // ---------- 创建 ----------

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String poId = str(payload.get("poId"));
        if (!StringUtils.hasText(poId)) {
            throw new ServiceException(422, "poId 必填");
        }
        PurchaseOrder po = poDao.selectById(poId);
        if (po == null) {
            throw new ServiceException(404, "采购订单不存在");
        }
        if (!"APPROVED".equals(po.getStatus())) {
            throw new ServiceException(422, "订单未下达，不可发货：" + po.getStatus());
        }
        if (!"CONFIRMED".equals(po.getConfirmStatus())) {
            throw new ServiceException(422, "交期未确认（当前 " + po.getConfirmStatus()
                    + "），供应商须先在门户确认交期（S-4.9-02）");
        }
        List<Map<String, Object>> rows = mapList(payload.get("lines"));
        if (rows.isEmpty()) {
            throw new ServiceException(422, "至少一行发货明细");
        }

        Asn asn = new Asn();
        asn.setAsnNo(nextAsnNo());
        asn.setPoId(po.getId());
        asn.setPoNo(po.getPoNo());
        asn.setSource(Asn.SRC_PO);
        asn.setSupplierId(po.getSupplierId());
        asn.setSupplierName(po.getSupplierName());
        asn.setStatus(Asn.ST_CONFIRMED);
        asn.setOverToleranceQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
        asn.setOverStatus(Asn.OVER_NONE);
        asn.setExpectArrival(payloadDate(payload, "expectArrival"));
        asn.setLogisticsNo(str(payload.get("logisticsNo")));
        asn.setRemark(str(payload.get("remark")));
        asn.setCreateBy(SecurityUtils.getCurrentUserId());

        List<AsnLine> lines = new ArrayList<>();
        BigDecimal overTotal = BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        int lineNo = 0;
        for (Map<String, Object> row : rows) {
            lineNo++;
            PurchaseOrderLine pl = resolvePoLine(po.getId(), row);
            BigDecimal qty = decimal(row.get("qty"));
            if (qty == null || qty.signum() <= 0) {
                throw new ServiceException(422, "发货数量必须大于 0（" + pl.getItemCode() + "）");
            }
            // 防超发（design D7）：未清 = PO 行量 − 已收 − 在途 ASN 占用
            BigDecimal remaining = openQty(pl);
            if (remaining.signum() <= 0) {
                throw new ServiceException(422, "该行剩余可发数量为 0（" + pl.getItemCode()
                        + "），不可再创建 ASN");
            }
            BigDecimal withinLimit = remaining.multiply(BigDecimal.ONE.add(tolerance))
                    .setScale(3, RoundingMode.HALF_UP);
            BigDecimal over = qty.compareTo(withinLimit) > 0 ? qty.subtract(withinLimit) : BigDecimal.ZERO;
            over = over.setScale(3, RoundingMode.HALF_UP);

            AsnLine l = new AsnLine();
            l.setAsnId(asn.getId() == null ? null : asn.getId()); // id 由 insert 时 ASSIGN_UUID 生成，稍后回填
            l.setPoLineId(pl.getId());
            l.setLineNo(lineNo);
            l.setItemCode(pl.getItemCode());
            l.setItemName(pl.getItemName());
            l.setUnit(pl.getUnit());
            l.setQty(qty);
            l.setOverQty(over);
            l.setReceivedQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
            l.setBatchNo(str(row.get("batchNo")));
            l.setLineStatus(AsnLine.ST_OPEN);
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            lines.add(l);
            overTotal = overTotal.add(over);
        }

        asn.setOverToleranceQty(overTotal);
        asn.setOverStatus(overTotal.signum() > 0 ? Asn.OVER_PENDING : Asn.OVER_NONE);
        asnDao.insert(asn);
        for (AsnLine l : lines) {
            l.setAsnId(asn.getId());
            lineDao.insert(l);
        }
        if (overTotal.signum() > 0) {
            submitOverApproval(asn);
        }
        try {
            eventService.asnCreated(asn);
        } catch (ServiceException e) {
            // 同号事件已存在（跨轮删单后号回收，C-0-06）：ASN 创建照常生效
            log.warn("ASN {} 创建事件跳过: {}", asn.getAsnNo(), e.getMessage());
        }
        log.info("ASN {} created po={} qty={} over={} ({})",
                asn.getAsnNo(), po.getPoNo(), sumQty(lines), overTotal, asn.getOverStatus());
        Map<String, Object> out = detail(asn.getId());
        return out;
    }

    @Override
    @Transactional
    public Asn createReplenish(String supplierId, String supplierName, String itemCode,
                               String itemName, String unit, BigDecimal qty) {
        Asn asn = new Asn();
        asn.setAsnNo(nextAsnNo());
        asn.setSource(Asn.SRC_REPLENISH);
        asn.setSupplierId(supplierId);
        asn.setSupplierName(supplierName);
        asn.setStatus(Asn.ST_CONFIRMED);
        asn.setOverToleranceQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
        asn.setOverStatus(Asn.OVER_NONE);
        asn.setCreateBy(SecurityUtils.getCurrentUserId());
        asn.setRemark("补货建议确认联动创建（REPLENISH）");
        asnDao.insert(asn);

        AsnLine l = new AsnLine();
        l.setAsnId(asn.getId());
        l.setLineNo(1);
        l.setItemCode(itemCode);
        l.setItemName(itemName);
        l.setUnit(unit);
        l.setQty(qty);
        l.setOverQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
        l.setReceivedQty(BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP));
        l.setLineStatus(AsnLine.ST_OPEN);
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        lineDao.insert(l);

        try {
            eventService.asnCreated(asn);
        } catch (ServiceException e) {
            log.warn("ASN {} 创建事件跳过: {}", asn.getAsnNo(), e.getMessage());
        }
        log.info("ASN {} created (REPLENISH) supplier={} item={} qty={}",
                asn.getAsnNo(), supplierId, itemCode, qty);
        return asn;
    }

    // ---------- 查询 ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status,
                                          String supplierId, String keyword) {
        LambdaQueryWrapper<Asn> qw = new LambdaQueryWrapper<Asn>()
                .orderByDesc(Asn::getCreateDate);
        if (StringUtils.hasText(status)) {
            qw.eq(Asn::getStatus, status);
        }
        if (StringUtils.hasText(supplierId)) {
            qw.eq(Asn::getSupplierId, supplierId.trim());
        }
        if (StringUtils.hasText(keyword)) {
            qw.like(Asn::getAsnNo, keyword.trim());
        }
        Page<Asn> page = asnDao.selectPage(new Page<>(current, size), qw);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Asn a : page.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("asnNo", a.getAsnNo());
            m.put("poNo", a.getPoNo());
            m.put("source", a.getSource());
            m.put("supplierId", a.getSupplierId());
            m.put("supplierName", a.getSupplierName());
            m.put("status", a.getStatus());
            m.put("overStatus", a.getOverStatus());
            m.put("overToleranceQty", a.getOverToleranceQty());
            m.put("expectArrival", a.getExpectArrival());
            m.put("logisticsNo", a.getLogisticsNo());
            m.put("arrivalDeviationHours", a.getArrivalDeviationHours());
            m.put("createDate", a.getCreateDate());
            rows.add(m);
        }
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        Asn a = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("asnNo", a.getAsnNo());
        m.put("poId", a.getPoId());
        m.put("poNo", a.getPoNo());
        m.put("source", a.getSource());
        m.put("supplierId", a.getSupplierId());
        m.put("supplierName", a.getSupplierName());
        m.put("status", a.getStatus());
        m.put("overStatus", a.getOverStatus());
        m.put("overToleranceQty", a.getOverToleranceQty());
        m.put("approvalId", a.getApprovalId());
        m.put("expectArrival", a.getExpectArrival());
        m.put("logisticsNo", a.getLogisticsNo());
        m.put("arrivalDeviationHours", a.getArrivalDeviationHours());
        m.put("remark", a.getRemark());
        List<Map<String, Object>> lines = new ArrayList<>();
        for (AsnLine l : lineDao.selectList(new LambdaQueryWrapper<AsnLine>()
                .eq(AsnLine::getAsnId, id).orderByAsc(AsnLine::getLineNo))) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("id", l.getId());
            lm.put("lineNo", l.getLineNo());
            lm.put("itemCode", l.getItemCode());
            lm.put("itemName", l.getItemName());
            lm.put("unit", l.getUnit());
            lm.put("qty", l.getQty());
            lm.put("overQty", l.getOverQty());
            lm.put("receivedQty", l.getReceivedQty());
            lm.put("batchNo", l.getBatchNo());
            lm.put("lineStatus", l.getLineStatus());
            lines.add(lm);
        }
        m.put("lines", lines);
        return m;
    }

    // ---------- 收货衔接 ----------

    @Override
    public void assertReceiptWithin(GoodsReceipt gr, List<GoodsReceiptLine> pendingLines) {
        if (!StringUtils.hasText(gr.getAsnId())) {
            return;
        }
        Asn a = asnDao.selectById(gr.getAsnId());
        if (a == null) {
            throw new ServiceException(422, "收货单关联的 ASN 不存在");
        }
        if (Asn.ST_CLOSED.equals(a.getStatus())) {
            throw new ServiceException(422, "ASN 已核销关闭，不可再收货：" + a.getAsnNo());
        }
        List<AsnLine> asnLines = lineDao.selectList(new LambdaQueryWrapper<AsnLine>()
                .eq(AsnLine::getAsnId, a.getId()));
        Map<String, BigDecimal> incoming = new LinkedHashMap<>();
        for (GoodsReceiptLine gl : pendingLines) {
            BigDecimal r = gl.getReceivedQty();
            if (r != null && r.signum() > 0) {
                incoming.merge(gl.getItemCode(), r, BigDecimal::add);
            }
        }
        boolean overPending = Asn.OVER_PENDING.equals(a.getOverStatus());
        for (AsnLine al : asnLines) {
            BigDecimal inc = incoming.get(al.getItemCode());
            if (inc == null) {
                continue;
            }
            // 超收未放行 → 可收上限 = 容差内部分；放行后 = 全量（design D6）
            BigDecimal limit = (overPending || Asn.OVER_REJECTED.equals(a.getOverStatus()))
                    ? al.getQty().subtract(nvl(al.getOverQty()))
                    : al.getQty();
            BigDecimal available = limit.subtract(nvl(al.getReceivedQty()));
            if (inc.compareTo(available) > 0) {
                if (overPending) {
                    throw new ServiceException(422, "超收部分待审批（ASN " + a.getAsnNo() + " "
                            + al.getItemCode() + " 可收 " + available.stripTrailingZeros().toPlainString()
                            + "，本次 " + inc.stripTrailingZeros().toPlainString() + "）");
                }
                throw new ServiceException(422, "超出 ASN 可收数量（" + al.getItemCode()
                        + " 可收 " + available.stripTrailingZeros().toPlainString() + "）");
            }
        }
    }

    @Override
    @Transactional
    public void settle(GoodsReceipt gr, List<GoodsReceiptLine> postedLines) {
        if (!StringUtils.hasText(gr.getAsnId())) {
            return;
        }
        Asn a = asnDao.selectById(gr.getAsnId());
        if (a == null) {
            return;
        }
        List<AsnLine> asnLines = lineDao.selectList(new LambdaQueryWrapper<AsnLine>()
                .eq(AsnLine::getAsnId, a.getId()));
        Map<String, BigDecimal> received = new LinkedHashMap<>();
        for (GoodsReceiptLine gl : postedLines) {
            BigDecimal r = gl.getReceivedQty() != null ? gl.getReceivedQty()
                    : gl.getWithinToleranceQty();
            if (r != null && r.signum() > 0) {
                received.merge(gl.getItemCode(), r, BigDecimal::add);
            }
        }
        boolean allClosed = true;
        for (AsnLine al : asnLines) {
            BigDecimal add = received.get(al.getItemCode());
            if (add != null && add.signum() > 0) {
                al.setReceivedQty(nvl(al.getReceivedQty()).add(add));
                if (al.getReceivedQty().compareTo(nvl(al.getQty())) >= 0) {
                    al.setLineStatus(AsnLine.ST_CLOSED);
                }
                lineDao.updateById(al);
            }
            if (!AsnLine.ST_CLOSED.equals(al.getLineStatus())) {
                allClosed = false;
            }
        }
        // 承诺交期 vs 实际到货偏差（BR-4.9-01，日粒度近似：日期差 × 24h）
        if (StringUtils.hasText(a.getPoId()) && gr.getArrivalDate() != null) {
            PurchaseOrder po = poDao.selectById(a.getPoId());
            if (po != null && po.getPromiseDate() != null) {
                long hours = ChronoUnit.HOURS.between(
                        po.getPromiseDate().atStartOfDay(), gr.getArrivalDate().atStartOfDay());
                a.setArrivalDeviationHours((int) hours);
            }
        }
        if (allClosed) {
            a.setStatus(Asn.ST_CLOSED);
            // REPLENISH 来源：核销完成 → 补货建议告警 RESOLVED（复用既有消费机制）
            if (Asn.SRC_REPLENISH.equals(a.getSource())) {
                com.erp.entity.proc.ReplenishConfirm rc = replenishConfirmDao.selectOne(
                        new LambdaQueryWrapper<com.erp.entity.proc.ReplenishConfirm>()
                                .eq(com.erp.entity.proc.ReplenishConfirm::getAsnId, a.getId())
                                .last("LIMIT 1"));
                if (rc != null) {
                    com.erp.entity.vmi.VmiAlert al = vmiAlertDao.selectById(rc.getAlertId());
                    if (al != null && com.erp.entity.vmi.VmiAlert.ST_CONFIRMED.equals(al.getStatus())) {
                        al.setStatus(com.erp.entity.vmi.VmiAlert.ST_RESOLVED);
                        vmiAlertDao.updateById(al);
                    }
                }
            }
        }
        asnDao.updateById(a);
        log.info("ASN {} 核销 received={} status={}", a.getAsnNo(), received, a.getStatus());
    }

    // ---------- 帮助 ----------

    private void submitOverApproval(Asn asn) {
        var inst = approvalEngine.submit(BIZ_ASN_OVER, asn.getId(),
                "ASN 超收审批：" + asn.getAsnNo() + " / " + (asn.getSupplierName() == null ? "" : asn.getSupplierName())
                        + " / 超收 " + asn.getOverToleranceQty().stripTrailingZeros().toPlainString(),
                "ROLE_PM",
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PM", "采购员确认"),
                        ApprovalNodeSpec.sign("ROLE_WAREHOUSE", "仓库确认"))));
        asn.setApprovalId(inst.getId());
        asnDao.updateById(asn);
    }

    private PurchaseOrderLine resolvePoLine(String poId, Map<String, Object> row) {
        String poLineId = str(row.get("poLineId"));
        if (StringUtils.hasText(poLineId)) {
            PurchaseOrderLine pl = poLineDao.selectById(poLineId);
            if (pl == null || !poId.equals(pl.getPoId())) {
                throw new ServiceException(422, "PO 行不存在或不属于该 PO：" + poLineId);
            }
            return pl;
        }
        String itemCode = str(row.get("itemCode"));
        if (!StringUtils.hasText(itemCode)) {
            throw new ServiceException(422, "行须提供 poLineId 或 itemCode");
        }
        List<PurchaseOrderLine> cands = poLineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, poId)
                .eq(PurchaseOrderLine::getItemCode, itemCode));
        if (cands.isEmpty()) {
            throw new ServiceException(422, "PO 无该物料行：" + itemCode);
        }
        return cands.get(0);
    }

    /** 未清 = 行量 − 已收 − 在途 ASN（DRAFT/CONFIRMED）占用（design D7） */
    private BigDecimal openQty(PurchaseOrderLine pl) {
        BigDecimal ordered = nvl(pl.getQty());
        BigDecimal received = nvl(pl.getReceivedQty());
        BigDecimal reserved = BigDecimal.ZERO;
        List<AsnLine> asnLines = lineDao.selectList(new LambdaQueryWrapper<AsnLine>()
                .eq(AsnLine::getPoLineId, pl.getId()));
        for (AsnLine al : asnLines) {
            Asn a = asnDao.selectById(al.getAsnId());
            if (a != null && (Asn.ST_DRAFT.equals(a.getStatus()) || Asn.ST_CONFIRMED.equals(a.getStatus()))) {
                reserved = reserved.add(nvl(al.getQty()).subtract(nvl(al.getReceivedQty())));
            }
        }
        return ordered.subtract(received).subtract(reserved);
    }

    private String nextAsnNo() {
        String prefix = "ASN" + LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        Integer max = asnDao.selectMaxSeq(prefix);
        int seq = (max == null ? 0 : max) + 1;
        return prefix + String.format("%06d", seq);
    }

    private Asn require(String id) {
        Asn a = asnDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "ASN 不存在");
        }
        return a;
    }

    private static BigDecimal sumQty(List<AsnLine> lines) {
        BigDecimal t = BigDecimal.ZERO;
        for (AsnLine l : lines) {
            t = t.add(nvl(l.getQty()));
        }
        return t;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static BigDecimal decimal(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式错误：" + o);
        }
    }

    private static java.time.LocalDate payloadDate(Map<String, Object> payload, String key) {
        String s = str(payload.get(key));
        return s == null ? null : java.time.LocalDate.parse(s);
    }

    private static List<Map<String, Object>> mapList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object x : (List<?>) o) {
                if (x instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) x;
                    out.add(m);
                }
            }
        }
        return out;
    }
}
