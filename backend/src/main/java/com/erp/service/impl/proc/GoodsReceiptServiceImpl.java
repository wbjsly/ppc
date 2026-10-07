package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.proc.PurchaseOrderVersionDao;
import com.erp.dao.proc.ReceiptAdjustmentDao;
import com.erp.dao.proc.ReceiptDifferenceDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.proc.PurchaseOrderVersion;
import com.erp.entity.proc.ReceiptAdjustment;
import com.erp.entity.proc.ReceiptDifference;
import com.erp.ops.OutboxPublisher;
import com.erp.service.proc.GoodsReceiptService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 收货管理实现（2.4 全组，change add-goods-receipt，design D1~D7）。
 * 登记即容差分流与差异生成；过账单事务五步（回写/行完成/库存/凭证/暂估桩）；
 * 调整单 P-1 轻量链只加数量不碰价格（BR-4.2-49，不进变更链）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsReceiptServiceImpl implements GoodsReceiptService {

    private static final String GR_CREATED = "CREATED";
    private static final String GR_POSTED = "POSTED";
    private static final String GR_CANCELLED = "CANCELLED";
    private static final String PO_APPROVED = "APPROVED";
    private static final String PO_CLOSED = "CLOSED";
    private static final String TOL_OK_OVER = "OK_OVER";
    private static final String TOL_OVER = "OVER";
    private static final String TOL_SHORT = "SHORT";
    private static final String TOL_FREE = "FREE";

    /** 收货容差（TOLERANCE_DEFAULT，C-4.2-04，默认 0.5%） */
    @Value("${app.proc.receipt-tolerance:0.005}")
    private BigDecimal receiptTolerance;

    @Value("${app.proc.qc-hours-a:24}")
    private int qcHoursA;

    @Value("${app.proc.qc-hours-b:48}")
    private int qcHoursB;

    @Value("${app.proc.qc-hours-c:72}")
    private int qcHoursC;

    private final GoodsReceiptDao grDao;
    private final GoodsReceiptLineDao lineDao;
    private final ReceiptDifferenceDao diffDao;
    private final ReceiptAdjustmentDao adjDao;
    private final InvStockDao stockDao;
    private final PurchaseOrderDao poDao;
    private final PurchaseOrderLineDao poLineDao;
    private final PurchaseOrderVersionDao versionDao;
    private final MdmSupplierDao supplierDao;
    private final MdmItemDao itemDao;
    private final OutboxPublisher outbox;
    private final com.erp.service.qms.InspectionLotService lotService;
    private final com.erp.dao.qms.ConcessionDao concessionDao;
    /** 应付暂估生成（BR-4.2-30，add-accrual-three-way-match D3） */
    private final com.erp.service.fin.AccrualService accrualService;
    /** ASN 衔接（spec asn-collaboration）：过账前置拦截 + 核销 */
    private final com.erp.service.proc.AsnService asnService;
    private final com.erp.dao.proc.AsnDao asnDao;
    /** 寄售库存分流与水位（spec vmi-consignment / MODIFIED receipt-posting ①④⑥） */
    private final com.erp.dao.vmi.VmiStockDao vmiStockDao;
    private final com.erp.dao.vmi.VmiAgreementDao vmiAgreeDao;
    private final com.erp.service.vmi.VmiAgreementService vmiAgreementService;
    private final com.erp.service.vmi.VmiAlertService vmiAlertService;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    // ================================================================
    // 2.4.1 登记
    // ================================================================

    @Override
    public Map<String, Object> poCandidates() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PurchaseOrder po : poDao.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getStatus, PO_APPROVED)
                .orderByDesc(PurchaseOrder::getCreateDate)
                .last("LIMIT 200"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", po.getId());
            m.put("poNo", po.getPoNo());
            m.put("supplierId", po.getSupplierId());
            m.put("supplierName", po.getSupplierName());
            m.put("totalAmt", po.getTotalAmt());
            m.put("createDate", po.getCreateDate());
            out.add(m);
        }
        return Map.of("records", out);
    }

    @Override
    public Map<String, Object> poLines(String poId) {
        requirePo(poId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (PurchaseOrderLine l : poLineList(poId)) {
            out.add(poLineRow(l));
        }
        return Map.of("lines", out);
    }

    @Override
    @Transactional
    public Map<String, Object> createByPo(Map<String, Object> payload) {
        String poId = str(payload.get("poId"));
        if (!hasText(poId)) {
            throw new ServiceException(422, "poId 必填");
        }
        PurchaseOrder po = requirePo(poId);
        if (PO_CLOSED.equals(po.getStatus())) {
            throw new ServiceException(422, "PO 已关闭，请先按 BR-4.2-49 发起收货调整单");
        }
        if (!PO_APPROVED.equals(po.getStatus())) {
            throw new ServiceException(422, "仅已批准（已下达）的 PO 可收货，当前 " + po.getStatus());
        }
        requireSupplierActive(po.getSupplierId());

        List<Map<String, Object>> lines = mapList(payload.get("lines"));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "至少录入一行实收数量");
        }
        GoodsReceipt gr = newHead(payload, "PO");
        gr.setPoId(po.getId());
        gr.setPoNo(po.getPoNo());
        gr.setSupplierId(po.getSupplierId());
        gr.setSupplierName(po.getSupplierName());
        // 从 ASN 带出登记（spec asn-collaboration 预生成收货预约）：关联校验属同 PO/同供应商
        String asnId = str(payload.get("asnId"));
        if (hasText(asnId)) {
            com.erp.entity.proc.Asn linked = asnDao.selectById(asnId);
            if (linked == null) {
                throw new ServiceException(422, "关联 ASN 不存在：" + asnId);
            }
            if (!po.getId().equals(linked.getPoId()) || !po.getSupplierId().equals(linked.getSupplierId())) {
                throw new ServiceException(422, "ASN 与该 PO/供应商不匹配：" + linked.getAsnNo());
            }
            if ("CLOSED".equals(linked.getStatus())) {
                throw new ServiceException(422, "ASN 已核销关闭，不可再收货：" + linked.getAsnNo());
            }
            gr.setAsnId(linked.getId());
        }
        gr.setGrNo(nextGrNo());
        gr.setStatus(GR_CREATED);
        gr.setCreateBy(SecurityUtils.getCurrentUserId());
        grDao.insert(gr);   // 先插头（后续校验失败由事务回滚）

        List<GoodsReceiptLine> builtLines = new ArrayList<>();
        List<ReceiptDifference> diffs = new ArrayList<>();
        List<GoodsReceiptLine> diffOwners = new ArrayList<>();
        for (Map<String, Object> row : lines) {
            String poLineId = str(row.get("poLineId"));
            BigDecimal r = decimal(row.get("receivedQty"));
            PurchaseOrderLine pl = poLineId == null ? null : poLineDao.selectById(poLineId);
            if (pl == null || !po.getId().equals(pl.getPoId())) {
                throw new ServiceException(422, "PO 行不存在或不属于该 PO：" + poLineId);
            }
            if ("COMPLETED".equals(pl.getLineStatus())) {
                throw new ServiceException(422, "PO 行已收满（" + pl.getItemCode() + "），不可再收");
            }
            if (r == null || r.signum() <= 0) {
                throw new ServiceException(422, "实收数量必须大于 0（" + pl.getItemCode() + "）");
            }
            BigDecimal open = openOf(pl);
            if (open.signum() <= 0) {
                throw new ServiceException(422, "该行已无未清量（" + pl.getItemCode() + "）");
            }
            GoodsReceiptLine gl = new GoodsReceiptLine();
            gl.setPoLineId(pl.getId());
            gl.setItemCode(pl.getItemCode());
            gl.setItemName(pl.getItemName());
            gl.setUnit(pl.getUnit());
            gl.setOrderedQty(pl.getQty());
            gl.setOpenQty(open);
            gl.setReceivedQty(r);
            gl.setUnitPrice(pl.getUnitPrice());
            gl.setStatus("PENDING");
            ReceiptDifference d = applyTolerance(gl, open, r, gr, pl);
            builtLines.add(gl);
            if (d != null) {
                diffs.add(d);
                diffOwners.add(gl);   // 差异挂行：与 diffs 同索引
            }
        }
        // 寄售收货水位预检（BR-4.2-36，design D3 登记提前拦截）：超水位 422 并落 OPEN 告警，
        // 采购员确认后携带 confirmWaterLevel=true 重试登记；过账时权威复核再校验一次
        if ("CONSIGN".equals(po.getPoType())) {
            Map<String, BigDecimal> incoming = new LinkedHashMap<>();
            for (GoodsReceiptLine l : builtLines) {
                BigDecimal w = nvl(l.getWithinToleranceQty());
                if (w.signum() > 0) {
                    incoming.merge(l.getItemCode(), w, BigDecimal::add);
                }
            }
            vmiAlertService.assertWaterLevel(po.getSupplierId(), incoming,
                    Boolean.TRUE.equals(payload.get("confirmWaterLevel")));
        }
        return insertGr(gr, builtLines, diffs, diffOwners);
    }

    /**
     * 无 PO 登记（FREE，免容差扩展场景）。
     * <p>偏差 D1（add-goods-receipt）—— 规格流程四为"按 PO 约定送达"语义（L926），
     * 无 PO 收货（样件/借件/退货入库）为本系统扩展场景：免容差、不回写 PO、单据永久标记 FREE。</p>
     */
    @Override
    @Transactional
    public Map<String, Object> createFree(Map<String, Object> payload) {
        String supplierId = str(payload.get("supplierId"));
        if (!hasText(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        requireSupplierActive(supplierId);
        MdmSupplier sup = supplierDao.selectById(supplierId);

        List<Map<String, Object>> lines = mapList(payload.get("lines"));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "至少录入一行实收数量");
        }
        GoodsReceipt gr = newHead(payload, "FREE");
        gr.setSupplierId(supplierId);
        gr.setSupplierName(sup == null ? supplierId : sup.getSupplierName());

        List<GoodsReceiptLine> builtLines = new ArrayList<>();
        for (Map<String, Object> row : lines) {
            String itemCode = str(row.get("itemCode"));
            BigDecimal r = decimal(row.get("receivedQty"));
            if (!hasText(itemCode)) {
                throw new ServiceException(422, "物料编码必填");
            }
            if (r == null || r.signum() <= 0) {
                throw new ServiceException(422, "实收数量必须大于 0（" + itemCode + "）");
            }
            GoodsReceiptLine gl = new GoodsReceiptLine();
            gl.setItemCode(itemCode);
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, itemCode).last("LIMIT 1"));
            gl.setItemName(item == null ? itemCode : item.getItemName());
            gl.setUnit(item == null ? null : item.getBaseUnit());
            gl.setOrderedQty(null);
            gl.setOpenQty(null);
            gl.setReceivedQty(r);
            gl.setToleranceResult(TOL_FREE);   // 免容差（spec goods-receipt FREE 入口）
            gl.setWithinToleranceQty(r);
            gl.setFrozenQty(BigDecimal.ZERO);
            gl.setStatus("PENDING");
            builtLines.add(gl);
        }
        return insertGr(gr, builtLines, List.of(), List.of());
    }

    /** 容差三规则（C-4.2-04，design D2）——实收 vs 未清量；返回差异单（无差异返回 null） */
    private ReceiptDifference applyTolerance(GoodsReceiptLine gl, BigDecimal open, BigDecimal r,
                                             GoodsReceipt gr, PurchaseOrderLine pl) {
        BigDecimal upper = open.multiply(BigDecimal.ONE.add(receiptTolerance))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal lower = open.multiply(BigDecimal.ONE.subtract(receiptTolerance))
                .setScale(4, RoundingMode.HALF_UP);
        if (r.compareTo(upper) > 0) {
            // 超交：容差上沿内正常，超出部分冻结 + OVER 差异单（BR-4.2-21）
            gl.setToleranceResult(TOL_OVER);
            gl.setWithinToleranceQty(upper);
            gl.setFrozenQty(r.subtract(upper));
            return newDiff(gr, pl, "OVER", open, r, r.subtract(upper));
        }
        if (r.compareTo(lower) < 0) {
            // 短交：如实核销，SHORT 差异单，行保持未清（BR-4.2-22）。
            // 偏差 D3（add-goods-receipt）—— BR-4.2-22 的"向供应商绩效记一次短交"挂起：
            // 绩效模块（2.5 后）未建，差异单留存 SHORT 数据供后续接入
            gl.setToleranceResult(TOL_SHORT);
            gl.setWithinToleranceQty(r);
            gl.setFrozenQty(BigDecimal.ZERO);
            return newDiff(gr, pl, "SHORT", open, r, open.subtract(r));
        }
        // 容差内自动核销（BR-4.2-20），无差异单
        gl.setToleranceResult(TOL_OK_OVER);
        gl.setWithinToleranceQty(r);
        gl.setFrozenQty(BigDecimal.ZERO);
        return null;
    }

    private ReceiptDifference newDiff(GoodsReceipt gr, PurchaseOrderLine pl,
                                      String type, BigDecimal open, BigDecimal r, BigDecimal diffQty) {
        ReceiptDifference d = new ReceiptDifference();
        d.setDiffNo(nextDiffNo());
        d.setGrId(gr.getId());
        d.setPoId(pl.getPoId());
        d.setPoLineId(pl.getId());
        d.setItemCode(pl.getItemCode());
        d.setItemName(pl.getItemName());
        d.setDiffType(type);
        d.setOrderedQty(open);
        d.setReceivedQty(r);
        d.setDiffQty(diffQty);
        d.setTolerancePct(receiptTolerance);
        d.setStatus("PENDING");
        d.setCreateBy(SecurityUtils.getCurrentUserId());
        return d;
    }

    private Map<String, Object> insertGr(GoodsReceipt gr, List<GoodsReceiptLine> lines,
                                         List<ReceiptDifference> diffs,
                                         List<GoodsReceiptLine> diffOwners) {
        if (gr.getId() == null) {
            if (gr.getGrNo() == null) {
                gr.setGrNo(nextGrNo());
            }
            gr.setStatus(GR_CREATED);
            gr.setCreateBy(SecurityUtils.getCurrentUserId());
            grDao.insert(gr);
        }
        int no = 1;
        for (GoodsReceiptLine gl : lines) {
            gl.setGrId(gr.getId());
            gl.setLineNo(no++);
            gl.setCreateBy(SecurityUtils.getCurrentUserId());
            lineDao.insert(gl);
        }
        for (int i = 0; i < diffs.size(); i++) {
            ReceiptDifference d = diffs.get(i);
            d.setGrId(gr.getId());
            d.setGrLineId(diffOwners.get(i).getId());
            diffDao.insert(d);
        }
        // 检验批同事务生成（spec goods-receipt：登记提交即生成，1 GR 行 = 1 批；
        // 免检 → SKIPPED、无标准 → BLOCKED 占位，GR 行 QC_STATUS 同步回写）
        var lots = lotService.generateForGr(gr, lines);
        log.info("GR {} created source={} lines={} diffs={} lots={}", gr.getGrNo(), gr.getSourceType(),
                lines.size(), diffs.size(), lots.size());
        Map<String, Object> detail = detail(gr.getId());
        detail.put("inspectionLots", lots.stream()
                .map(l -> Map.of("id", l.getId(), "lotNo", l.getLotNo(), "status", l.getStatus(),
                        "qcStatus", "SKIPPED".equals(l.getStatus()) ? "SKIPPED" : "PENDING",
                        "itemCode", l.getItemCode() == null ? "" : l.getItemCode()))
                .toList());
        return detail;
    }

    // ================================================================
    // 列表 / 详情 / 作废
    // ================================================================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status,
                                          String sourceType, String keyword) {
        LambdaQueryWrapper<GoodsReceipt> qw = new LambdaQueryWrapper<GoodsReceipt>()
                .eq(hasText(status), GoodsReceipt::getStatus, status)
                .eq(hasText(sourceType), GoodsReceipt::getSourceType, sourceType)
                .and(hasText(keyword), w -> w.like(GoodsReceipt::getGrNo, keyword)
                        .or().like(GoodsReceipt::getPoNo, keyword)
                        .or().like(GoodsReceipt::getSupplierName, keyword))
                .orderByDesc(GoodsReceipt::getCreateDate);
        Page<GoodsReceipt> raw = grDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        for (GoodsReceipt gr : raw.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", gr.getId());
            row.put("grNo", gr.getGrNo());
            row.put("sourceType", gr.getSourceType());
            row.put("poNo", gr.getPoNo());
            row.put("supplierName", gr.getSupplierName());
            row.put("status", gr.getStatus());
            row.put("arrivalDate", gr.getArrivalDate());
            row.put("batchNo", gr.getBatchNo());
            row.put("postingDocNo", gr.getPostingDocNo());
            row.put("createDate", gr.getCreateDate());
            records.add(row);
        }
        out.setRecords(records);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        GoodsReceipt gr = requireGr(id);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (GoodsReceiptLine l : lineList(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("lineNo", l.getLineNo());
            m.put("itemCode", l.getItemCode());
            m.put("itemName", l.getItemName());
            m.put("unit", l.getUnit());
            m.put("orderedQty", l.getOrderedQty());
            m.put("openQty", l.getOpenQty());
            m.put("receivedQty", l.getReceivedQty());
            m.put("toleranceResult", l.getToleranceResult());
            m.put("withinToleranceQty", l.getWithinToleranceQty());
            m.put("frozenQty", l.getFrozenQty());
            m.put("status", l.getStatus());
            m.put("unitPrice", l.getUnitPrice());
            m.put("amount", l.getAmount());
            lines.add(m);
        }
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (ReceiptDifference d : diffDao.selectList(new LambdaQueryWrapper<ReceiptDifference>()
                .eq(ReceiptDifference::getGrId, id)
                .orderByAsc(ReceiptDifference::getCreateDate))) {
            diffs.add(diffRow(d));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gr", gr);
        result.put("lines", lines);
        result.put("differences", diffs);
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> cancel(String id, String reason) {
        GoodsReceipt gr = requireGr(id);
        if (!GR_CREATED.equals(gr.getStatus())) {
            throw new ServiceException(422, "仅待过账的收货单可作废，当前 " + gr.getStatus());
        }
        if (!hasText(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "作废原因必填（不少于 2 字）");
        }
        gr.setStatus(GR_CANCELLED);
        gr.setRemark(reason.trim());
        gr.setUpdateBy(SecurityUtils.getCurrentUserId());
        grDao.updateById(gr);
        // 关联 PENDING 差异单一并关闭（作废无货可处置）
        for (ReceiptDifference d : diffDao.selectList(new LambdaQueryWrapper<ReceiptDifference>()
                .eq(ReceiptDifference::getGrId, id)
                .eq(ReceiptDifference::getStatus, "PENDING"))) {
            d.setStatus("CLOSED");
            d.setCloseReason("收货单作废：" + reason.trim());
            d.setUpdateBy(SecurityUtils.getCurrentUserId());
            diffDao.updateById(d);
        }
        log.info("GR {} cancelled: {}", gr.getGrNo(), reason);
        return detail(id);
    }

    // ================================================================
    // 2.4.3 差异处置
    // ================================================================

    @Override
    public Page<Map<String, Object>> differencePage(long current, long size,
                                                    String status, String diffType, String keyword) {
        LambdaQueryWrapper<ReceiptDifference> qw = new LambdaQueryWrapper<ReceiptDifference>()
                .eq(hasText(status), ReceiptDifference::getStatus, status)
                .eq(hasText(diffType), ReceiptDifference::getDiffType, diffType)
                .and(hasText(keyword), w -> w.like(ReceiptDifference::getDiffNo, keyword)
                        .or().like(ReceiptDifference::getItemCode, keyword))
                .orderByDesc(ReceiptDifference::getCreateDate);
        Page<ReceiptDifference> raw = diffDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        raw.getRecords().forEach(d -> records.add(diffRow(d)));
        out.setRecords(records);
        return out;
    }

    private Map<String, Object> diffRow(ReceiptDifference d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("diffNo", d.getDiffNo());
        m.put("grId", d.getGrId());
        m.put("grLineId", d.getGrLineId());
        m.put("poLineId", d.getPoLineId());
        m.put("poId", d.getPoId());
        m.put("itemCode", d.getItemCode());
        m.put("itemName", d.getItemName());
        m.put("diffType", d.getDiffType());
        m.put("orderedQty", d.getOrderedQty());
        m.put("receivedQty", d.getReceivedQty());
        m.put("diffQty", d.getDiffQty());
        m.put("status", d.getStatus());
        m.put("disposeBy", d.getDisposeByName());
        m.put("disposeDate", d.getDisposeDate());
        m.put("disposeNote", d.getDisposeNote());
        m.put("closeReason", d.getCloseReason());
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> disposeDifference(String diffId, String action, String note) {
        ReceiptDifference d = requireDiff(diffId);
        if (!"PENDING".equals(d.getStatus())) {
            throw new ServiceException(422, "差异单已处置，当前状态 " + d.getStatus());
        }
        if (!hasText(action)) {
            throw new ServiceException(422, "action 必填（START_ADJUST / REJECT / CLOSE）");
        }
        String userId = SecurityUtils.getCurrentUserId();
        switch (action) {
            case "START_ADJUST" -> {
                if (!"OVER".equals(d.getDiffType())) {
                    throw new ServiceException(422, "仅超交差异可发起收货调整单");
                }
                Map<String, Object> adj = createAdjustment(Map.of(
                        "diffId", d.getId(), "addQty", d.getDiffQty()));
                d.setDisposeBy(userId);
                d.setDisposeByName(userId);
                d.setDisposeDate(LocalDateTime.now());
                d.setDisposeNote(note);
                diffDao.updateById(d);
                return Map.of("difference", d, "adjustmentId", adj.get("id"),
                        "adjustmentNo", adj.get("adjNo"));
            }
            case "REJECT" -> {
                if (!hasText(note) || note.trim().length() < 2) {
                    throw new ServiceException(422, "拒收原因必填（不少于 2 字）");
                }
                d.setStatus("RETURNED");
                markLineRejected(d.getGrLineId(), note.trim());
            }
            case "CLOSE" -> {
                if (!"SHORT".equals(d.getDiffType())) {
                    throw new ServiceException(422, "仅短交差异支持手动关闭（超交须走调整单或拒收）");
                }
                if (!hasText(note) || note.trim().length() < 2) {
                    throw new ServiceException(422, "关闭原因必填（不少于 2 字）");
                }
                d.setStatus("CLOSED");
                d.setCloseReason(note.trim());
            }
            default -> throw new ServiceException(422, "未知处置动作：" + action);
        }
        d.setDisposeBy(userId);
        d.setDisposeByName(userId);
        d.setDisposeDate(LocalDateTime.now());
        d.setDisposeNote(note);
        d.setUpdateBy(userId);
        diffDao.updateById(d);
        log.info("GR difference {} -> {} by {}", d.getDiffNo(), d.getStatus(), userId);
        return Map.of("difference", d);
    }

    private void markLineRejected(String grLineId, String reason) {
        GoodsReceiptLine gl = lineDao.selectById(grLineId);
        if (gl != null) {
            gl.setStatus("REJECTED");
            gl.setUpdateBy(SecurityUtils.getCurrentUserId());
            lineDao.updateById(gl);
            log.info("GR line {} REJECTED: {}", gl.getId(), reason);
        }
    }

    // ================================================================
    // 3. 收货调整单（BR-4.2-49，P-1 轻量链）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> createAdjustment(Map<String, Object> payload) {
        String diffId = str(payload.get("diffId"));
        String poLineId;
        BigDecimal addQty;
        ReceiptDifference diff = null;
        if (hasText(diffId)) {
            diff = requireDiff(diffId);
            if (!"OVER".equals(diff.getDiffType())) {
                throw new ServiceException(422, "仅超交差异可发起调整单");
            }
            if (!"PENDING".equals(diff.getStatus())) {
                throw new ServiceException(422, "差异单状态不可发起调整（" + diff.getStatus() + "）");
            }
            poLineId = diff.getPoLineId();
            addQty = diff.getDiffQty();
        } else {
            poLineId = str(payload.get("poLineId"));   // 迟到货物（L1057）手工发起
            addQty = decimal(payload.get("addQty"));
        }
        if (!hasText(poLineId) || addQty == null || addQty.signum() <= 0) {
            throw new ServiceException(422, "poLineId 与正数 addQty 必填");
        }
        PurchaseOrderLine pl = poLineDao.selectById(poLineId);
        if (pl == null) {
            throw new ServiceException(404, "PO 行不存在：" + poLineId);
        }
        // 同一差异单仅允许一张未终态调整单
        if (diffId != null && adjDao.selectCount(new LambdaQueryWrapper<ReceiptAdjustment>()
                .eq(ReceiptAdjustment::getDiffId, diffId)
                .in(ReceiptAdjustment::getStatus, "PENDING_APPROVE", "APPROVED")) > 0) {
            throw new ServiceException(422, "该差异单已有进行中的调整单");
        }
        PurchaseOrder po = requirePo(pl.getPoId());
        requirePurchaserChangeFree(po.getId());

        ReceiptAdjustment adj = new ReceiptAdjustment();
        adj.setAdjNo(nextAdjNo());
        adj.setDiffId(diffId);
        adj.setPoId(po.getId());
        adj.setPoNo(po.getPoNo());
        adj.setPoLineId(pl.getId());
        adj.setItemCode(pl.getItemCode());
        adj.setAddQty(addQty);
        adj.setOldQty(pl.getQty());
        adj.setNewQty(pl.getQty().add(addQty));
        adj.setStatus("PENDING_APPROVE");   // 发起即提交（design D4）
        adj.setInitiator(SecurityUtils.getCurrentUserId());
        adj.setInitiatorName(SecurityUtils.getCurrentUserId());
        adj.setCreateBy(SecurityUtils.getCurrentUserId());
        adjDao.insert(adj);
        log.info("GR adjustment {} initiated for PO {} line {} +{}", adj.getAdjNo(),
                po.getPoNo(), pl.getItemCode(), addQty);
        return Map.of("id", adj.getId(), "adjNo", adj.getAdjNo(), "status", adj.getStatus());
    }

    @Override
    public Page<Map<String, Object>> adjustmentPage(long current, long size, String status) {
        Page<ReceiptAdjustment> raw = adjDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ReceiptAdjustment>()
                        .eq(hasText(status), ReceiptAdjustment::getStatus, status)
                        .orderByDesc(ReceiptAdjustment::getCreateDate));
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        for (ReceiptAdjustment a : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getId());
            m.put("adjNo", a.getAdjNo());
            m.put("poNo", a.getPoNo());
            m.put("poId", a.getPoId());
            m.put("poLineId", a.getPoLineId());
            m.put("itemCode", a.getItemCode());
            m.put("addQty", a.getAddQty());
            m.put("oldQty", a.getOldQty());
            m.put("newQty", a.getNewQty());
            m.put("status", a.getStatus());
            m.put("initiator", a.getInitiator());
            m.put("approver", a.getApprover());
            m.put("approveNote", a.getApproveNote());
            m.put("newGrId", a.getNewGrId());
            m.put("createDate", a.getCreateDate());
            records.add(m);
        }
        out.setRecords(records);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> approveAdjustment(String adjId, boolean approved, String note) {
        ReceiptAdjustment adj = adjDao.selectById(adjId);
        if (adj == null) {
            throw new ServiceException(404, "调整单不存在");
        }
        if (!"PENDING_APPROVE".equals(adj.getStatus())) {
            throw new ServiceException(422, "仅待审批的调整单可审批，当前 " + adj.getStatus());
        }
        String userId = SecurityUtils.getCurrentUserId();
        if (!approved) {
            if (!hasText(note) || note.trim().length() < 2) {
                throw new ServiceException(422, "驳回意见必填（不少于 2 字）");
            }
            adj.setStatus("REJECTED");
            adj.setApprover(userId);
            adj.setApproverName(userId);
            adj.setApproveNote(note.trim());
            adj.setUpdateBy(userId);
            adjDao.updateById(adj);
            return Map.of("id", adj.getId(), "status", adj.getStatus());
        }
        if (!hasText(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "审批意见必填（不少于 2 字）");
        }
        adj.setStatus("APPROVED");
        adj.setApprover(userId);
        adj.setApproverName(userId);
        adj.setApproveNote(note.trim());
        adj.setUpdateBy(userId);
        adjDao.updateById(adj);
        return executeAdjustment(adj);   // 通过即同事务执行（design D4）
    }

    /** 执行：PO 行仅数量增加（价格不动）、CLOSED 重开、版本快照 + 事件、生成关联原 PO 新收货单 */
    private Map<String, Object> executeAdjustment(ReceiptAdjustment adj) {
        PurchaseOrder po = requirePo(adj.getPoId());
        PurchaseOrderLine pl = poLineDao.selectById(adj.getPoLineId());
        if (pl == null) {
            throw new ServiceException(404, "PO 行不存在");
        }
        requirePurchaserChangeFree(po.getId());
        // 乐观锁：行 verNo 条件更新（并发安全，spec receipt-posting）
        BigDecimal newQty = pl.getQty().add(adj.getAddQty());
        BigDecimal newAmount = newQty.multiply(nvl(pl.getUnitPrice()));
        int rows = poLineDao.update(null, new LambdaUpdateWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getId, pl.getId())
                .eq(PurchaseOrderLine::getVerNo, pl.getVerNo())
                .set(PurchaseOrderLine::getQty, newQty)
                .set(PurchaseOrderLine::getAmount, newAmount));
        if (rows == 0) {
            throw new ServiceException(409, "PO 行已被并发修改，请重试调整单审批");
        }
        // 头金额同步（Σ行）
        BigDecimal newTotal = BigDecimal.ZERO;
        for (PurchaseOrderLine l : poLineList(po.getId())) {
            newTotal = newTotal.add(nvl(l.getAmount()));
        }
        po.setTotalAmt(newTotal);
        // CLOSED 重开（BR-4.2-49 迟到货物）
        boolean reopened = false;
        if (PO_CLOSED.equals(po.getStatus())) {
            po.setStatus(PO_APPROVED);
            reopened = true;
        }
        // 版本快照（RECEIPT_ADJUST，不进变更链不重跑价控）——
        // 遵循既有协议（change/rollback 同款）：取 currVersion 插入后 +1，
        // 否则 UK_PO_VER (PO_ID, VERSION_NO) 唯一约束会拒绝重复调整
        try {
            Map<String, Object> snap = new LinkedHashMap<>();
            snap.put("poLineId", pl.getId());
            snap.put("itemCode", pl.getItemCode());
            snap.put("oldQty", pl.getQty());
            snap.put("newQty", newQty);
            snap.put("addQty", adj.getAddQty());
            snap.put("unitPrice", pl.getUnitPrice());
            snap.put("priceUnchanged", true);
            PurchaseOrderVersion v = new PurchaseOrderVersion();
            v.setPoId(po.getId());
            v.setVersionNo(po.getCurrVersion() == null ? 1 : po.getCurrVersion());
            v.setChgType("RECEIPT_ADJUST");
            v.setChgReason("收货调整单 " + adj.getAdjNo() + "（仅数量 +" + adj.getAddQty() + "）");
            v.setSnapshotJson(jsonMapper.writeValueAsString(snap));
            versionDao.insert(v);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(500, "调整单版本快照序列化失败：" + e.getMessage());
        }
        po.setCurrVersion((po.getCurrVersion() == null ? 1 : po.getCurrVersion()) + 1);
        po.setUpdateBy(SecurityUtils.getCurrentUserId());
        poDao.updateById(po);
        outbox.publish("PROC.PO.RECEIPT_ADJUSTED",
                po.getPoNo() + ":ADJ:" + adj.getAdjNo(),
                po.getVerNo() == null ? 1 : po.getVerNo(),
                null, "收货调整 +" + adj.getAddQty() + "（价格不变）" + (reopened ? "，CLOSED 重开" : ""));

        // 新收货单关联原 PO：冻结量转正常待检量
        BigDecimal receiveQty = adj.getAddQty();
        String diffLineId = null;
        if (hasText(adj.getDiffId())) {
            ReceiptDifference d = diffDao.selectById(adj.getDiffId());
            if (d != null) {
                receiveQty = d.getDiffQty();
                diffLineId = d.getGrLineId();
                d.setStatus("ADJUSTED");
                d.setUpdateBy(SecurityUtils.getCurrentUserId());
                diffDao.updateById(d);
            }
        }
        GoodsReceipt gr = new GoodsReceipt();
        gr.setGrNo(nextGrNo());
        gr.setSourceType("PO");
        gr.setPoId(po.getId());
        gr.setPoNo(po.getPoNo());
        gr.setSupplierId(po.getSupplierId());
        gr.setSupplierName(po.getSupplierName());
        gr.setStatus(GR_CREATED);
        gr.setArrivalDate(LocalDate.now());
        gr.setRemark("收货调整单 " + adj.getAdjNo() + " 生成（BR-4.2-49）");
        gr.setCreateBy(SecurityUtils.getCurrentUserId());
        grDao.insert(gr);

        GoodsReceiptLine gl = new GoodsReceiptLine();
        gl.setGrId(gr.getId());
        gl.setLineNo(1);
        gl.setPoLineId(pl.getId());
        gl.setItemCode(pl.getItemCode());
        gl.setItemName(pl.getItemName());
        gl.setUnit(pl.getUnit());
        gl.setOrderedQty(newQty);
        gl.setOpenQty(adj.getAddQty());
        gl.setReceivedQty(receiveQty);
        gl.setToleranceResult(TOL_OK_OVER);   // 数量已追加，冻结量转常规待检
        gl.setWithinToleranceQty(receiveQty);
        gl.setFrozenQty(BigDecimal.ZERO);
        gl.setStatus("PENDING");
        gl.setUnitPrice(pl.getUnitPrice());
        gl.setCreateBy(SecurityUtils.getCurrentUserId());
        lineDao.insert(gl);

        adj.setStatus("EXECUTED");
        adj.setExecuteDate(LocalDateTime.now());
        adj.setNewGrId(gr.getId());
        adj.setUpdateBy(SecurityUtils.getCurrentUserId());
        adjDao.updateById(adj);
        log.info("GR adjustment {} executed: PO {} line qty {} -> {}, new GR {}",
                adj.getAdjNo(), po.getPoNo(), pl.getQty(), newQty, gr.getGrNo());
        return Map.of("id", adj.getId(), "status", "EXECUTED",
                "newGrId", gr.getId(), "newGrNo", gr.getGrNo(),
                "poStatus", po.getStatus(), "diffLineId", diffLineId == null ? "" : diffLineId);
    }

    /** PO 变更互斥：存在 PENDING 变更版本时禁止调整执行（design D4） */
    private void requirePurchaserChangeFree(String poId) {
        long pending = versionDao.selectCount(new LambdaQueryWrapper<PurchaseOrderVersion>()
                .eq(PurchaseOrderVersion::getPoId, poId)
                .eq(PurchaseOrderVersion::getApprovalStatus, "PENDING"));
        if (pending > 0) {
            throw new ServiceException(422, "PO 存在进行中的变更审批，与收货调整单互斥");
        }
    }

    // ================================================================
    // 2.4.2 待检
    // ================================================================

    /**
     * 2.4.2 待检看板（5.5 改造）：数据源由 erp_inv_stock.QC_QTY 大于 0 切换为**检验批**，
     * 起算点 = GR 登记提交时间（偏差 D1）；放行动作归检验批「确认合格」，人工兜底放行已移除
     * （qc-hold-area REMOVED，spec qc-hold-area）。本区段同时移除 qcRelease 实现。
     */
    @Override
    public Page<Map<String, Object>> qcPage(long current, long size) {
        return lotService.board(current, size, null);
    }

    // ================================================================
    // 2.4.4 过账
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> posting(String grId) {
        return posting(grId, null);
    }

    @Override
    @Transactional
    public Map<String, Object> posting(String grId, Map<String, Object> body) {
        GoodsReceipt gr = requireGr(grId);
        if (!GR_CREATED.equals(gr.getStatus())) {
            throw new ServiceException(422, "仅待过账的收货单可过账，当前 " + gr.getStatus());
        }
        // BR-4.2-28：供应商冻结判定（FREE 也判）
        requireSupplierActive(gr.getSupplierId());
        // BR-4.2-28：PO 状态判定
        PurchaseOrder po = null;
        if ("PO".equals(gr.getSourceType())) {
            po = requirePo(gr.getPoId());
            if (PO_CLOSED.equals(po.getStatus())) {
                throw new ServiceException(422, "PO 已关闭，过账被阻断（BR-4.2-28）：请先按 BR-4.2-49 发起收货调整单");
            }
            if (!PO_APPROVED.equals(po.getStatus())) {
                throw new ServiceException(422, "PO 状态不可过账（BR-4.2-28）：" + po.getStatus());
            }
        }
        // 寄售收货最高水位权威复核（BR-4.2-36，MODIFIED receipt-posting ①）：
        // 超水位 422 并确保 OPEN 告警落库；confirmWaterLevel=true 且已有采购员确认 → 放行
        boolean consign = po != null && "CONSIGN".equals(po.getPoType());
        Map<String, BigDecimal> incoming = new LinkedHashMap<>();
        if (consign) {
            for (GoodsReceiptLine gl : lineList(grId)) {
                if (!"PENDING".equals(gl.getStatus())) {
                    continue;
                }
                BigDecimal w = nvl(gl.getWithinToleranceQty());
                if (w.signum() <= 0) {
                    continue;
                }
                incoming.merge(gl.getItemCode(), w, BigDecimal::add);
            }
            boolean confirm = body != null && Boolean.TRUE.equals(body.get("confirmWaterLevel"));
            vmiAlertService.assertWaterLevel(gr.getSupplierId(), incoming, confirm);
        }
        // ASN 超收拦截（C-4.9-07，spec asn-collaboration）：超收未放行/超出可收量 → 422（早期，纯校验）
        if (hasText(gr.getAsnId())) {
            List<GoodsReceiptLine> pending = new ArrayList<>();
            for (GoodsReceiptLine gl : lineList(grId)) {
                if ("PENDING".equals(gl.getStatus())) {
                    pending.add(gl);
                }
            }
            asnService.assertReceiptWithin(gr, pending);
        }
        // 检验合格前置校验（C-4.12-06 前移，spec receipt-posting ADDED）：
        // 全部待过账行的检验批须为 RELEASED / SKIPPED（免检）/ CONCESSION（让步批准），否则 422 逐行列出
        List<String> notReleased = new ArrayList<>();
        for (GoodsReceiptLine gl : lineList(grId)) {
            if (!"PENDING".equals(gl.getStatus())) {
                continue;
            }
            if (nvl(gl.getWithinToleranceQty()).signum() <= 0) {
                continue;
            }
            String qc = gl.getQcStatus();
            if (!"RELEASED".equals(qc) && !"SKIPPED".equals(qc) && !"CONCESSION".equals(qc)) {
                notReleased.add(gl.getItemCode());
            }
        }
        if (!notReleased.isEmpty()) {
            throw new ServiceException(422, "检验记录缺失或不完整，请先完成检验（C-4.12-06）：未放行行 "
                    + String.join("、", notReleased));
        }
        String userId = SecurityUtils.getCurrentUserId();
        int postedLines = 0;
        List<GoodsReceiptLine> postedLineEntities = new ArrayList<>();
        for (GoodsReceiptLine gl : lineList(grId)) {
            if (!"PENDING".equals(gl.getStatus())) {
                continue;   // REJECTED 行不入待检不过账
            }
            BigDecimal within = nvl(gl.getWithinToleranceQty());
            if (within.signum() <= 0) {
                continue;
            }
            if (gl.getPoLineId() != null) {
                // PO 行回写：条件增量（并发/超未清 422，spec receipt-posting）
                int rows = poLineDao.update(null, new LambdaUpdateWrapper<PurchaseOrderLine>()
                        .eq(PurchaseOrderLine::getId, gl.getPoLineId())
                        .apply("RECEIVED_QTY + {0} <= QTY + QTY * {1}", within, receiptTolerance)
                        .setSql("RECEIVED_QTY = RECEIVED_QTY + " + within.toPlainString()));
                if (rows == 0) {
                    throw new ServiceException(422, "PO 行未清量不足（并发收货或超未清），"
                            + "请刷新后重试或走收货调整单：" + gl.getItemCode());
                }
                PurchaseOrderLine pl = poLineDao.selectById(gl.getPoLineId());
                if (pl != null && nvl(pl.getReceivedQty()).compareTo(nvl(pl.getQty())) >= 0) {
                    pl.setLineStatus("COMPLETED");
                    pl.setUpdateBy(userId);
                    poLineDao.updateById(pl);
                    closeShortDiffs(pl.getId());
                }
            }
            // 库存 upsert 分流（MODIFIED receipt-posting ④）：寄售 PO 入独立寄售库存
            // （物权=供应商，BR-4.2-01），普通 PO 入自有库存（进待检锁定，FR-4.2-4-2）
            if (consign) {
                upsertVmiStock(gr, gl, within);
            } else {
                upsertStock(gr, gl, within);
            }
            // 行金额快照（FREE 单价空则金额空）
            if (gl.getUnitPrice() != null) {
                gl.setAmount(within.multiply(gl.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
            }
            gl.setStatus("POSTED");
            gl.setUpdateBy(userId);
            lineDao.updateById(gl);
            postedLineEntities.add(gl);
            postedLines++;
        }
        if (postedLines == 0) {
            throw new ServiceException(422, "无可过账行（全部已过账或被拒收）");
        }
        gr.setStatus(GR_POSTED);
        gr.setPostingDocNo(docNoOf(gr.getGrNo()));
        gr.setPostingDate(LocalDateTime.now());
        gr.setUpdateBy(userId);
        grDao.updateById(gr);
        if (consign) {
            // 寄售 PO 跳过暂估（BR-4.2-01 物权属供应商，不确认应付；暂估于领用物权转移时经
            // createFromVmiTransfer 生成，MODIFIED receipt-posting ⑥）；消费水位放行额度（一次性）
            log.info("GR {} posted lines={} doc={} 寄售分流：入寄售库存、不生成暂估",
                    gr.getGrNo(), postedLines, gr.getPostingDocNo());
            vmiAlertService.consumeConfirmed(gr.getSupplierId(), incoming.keySet());
        } else {
            // ⑥ 应付暂估（BR-4.2-30 落地，change add-accrual-three-way-match design D3）：
            // 同事务生成暂估单 + 借存货/贷应付暂估凭证；FREE 无单价行不计价（全无单价则跳过），
            // 生成失败整体回滚（收货单保持 CREATED）。三方匹配（PO-收货单-发票）见 2.7.2。
            accrualService.createFromGr(gr, postedLineEntities);
        // ASN 核销（spec asn-collaboration）：回写收货量、全收 CLOSED、承诺-到货偏差留痕
        asnService.settle(gr, postedLineEntities);
            log.info("GR {} posted lines={} doc={} 暂估已生成", gr.getGrNo(), postedLines,
                    gr.getPostingDocNo());
        }
        return detail(grId);
    }

    /**
     * 库存 upsert（spec receipt-posting MODIFIED，B 口径）：
     * 合格放行/免检量直接入 AVAILABLE_QTY（QC_QTY 不变）；让步接收量全额入 QC_QTY 锁定并写限制快照
     * （CONCESSION_FLAG / CONCESSION_LIMIT，BR-4.12-28 由核销放行逐次校验，D8）。
     */
    private void upsertStock(GoodsReceipt gr, GoodsReceiptLine gl, BigDecimal within) {
        String batch = hasText(gr.getBatchNo()) ? gr.getBatchNo() : "";
        boolean concession = "CONCESSION".equals(gl.getQcStatus());
        String limitJson = concession ? concessionLimitOf(gl.getId()) : null;
        InvStock s = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, InvStock.DEFAULT_WH)
                .eq(InvStock::getItemCode, gl.getItemCode())
                .eq(InvStock::getBatchNo, batch)
                .last("LIMIT 1"));
        if (s == null) {
            s = new InvStock();
            s.setWarehouseCode(InvStock.DEFAULT_WH);
            s.setItemCode(gl.getItemCode());
            s.setItemName(gl.getItemName());
            s.setBatchNo(batch);
            s.setQty(within);
            s.setQcQty(concession ? within : BigDecimal.ZERO);
            s.setAvailableQty(concession ? BigDecimal.ZERO : within);
            if (concession) {
                s.setConcessionFlag("1");
                s.setConcessionLimit(limitJson);
            }
            s.setCreateBy(SecurityUtils.getCurrentUserId());
            stockDao.insert(s);
        } else {
            var upd = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, s.getId())
                    .eq(InvStock::getVerNo, s.getVerNo())
                    .setSql("QTY = QTY + " + within.toPlainString()));
            if (concession) {
                stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                        .eq(InvStock::getId, s.getId())
                        .setSql("QC_QTY = QC_QTY + " + within.toPlainString())
                        .set(InvStock::getConcessionFlag, "1")
                        .set(InvStock::getConcessionLimit, limitJson));
            } else {
                stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                        .eq(InvStock::getId, s.getId())
                        .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + within.toPlainString()));
            }
            if (upd == 0) {
                throw new ServiceException(409, "库存并发更新冲突，请重试：" + gl.getItemCode());
            }
        }
    }

    /**
     * 寄售库存 upsert（MODIFIED receipt-posting ④：物权=供应商，独立分账，BR-4.2-01）。
     * 唯一键 物料+批次+供应商；INBOUND_DATE 取首批（补货不刷新，FIFO 依据）；
     * 寄售库存不区分 QC 锁定——不合格已在检验环节拒收不入账（spec vmi-consignment 收货口径）。
     */
    private void upsertVmiStock(GoodsReceipt gr, GoodsReceiptLine gl, BigDecimal within) {
        String batch = hasText(gr.getBatchNo()) ? gr.getBatchNo() : "";
        com.erp.entity.vmi.VmiStock s = vmiStockDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.vmi.VmiStock>()
                        .eq(com.erp.entity.vmi.VmiStock::getItemCode, gl.getItemCode())
                        .eq(com.erp.entity.vmi.VmiStock::getBatchNo, batch)
                        .eq(com.erp.entity.vmi.VmiStock::getSupplierId, gr.getSupplierId())
                        .last("LIMIT 1"));
        if (s == null) {
            s = new com.erp.entity.vmi.VmiStock();
            s.setItemCode(gl.getItemCode());
            s.setItemName(gl.getItemName());
            s.setBatchNo(batch);
            s.setSupplierId(gr.getSupplierId());
            s.setSupplierName(gr.getSupplierName());
            s.setQty(within);
            s.setIssuedQty(BigDecimal.ZERO);
            s.setInboundDate(java.time.LocalDate.now());
            s.setAgreeNo(agreeNoOf(gr.getSupplierId(), gl.getItemCode()));
            s.setCreateBy(SecurityUtils.getCurrentUserId());
            vmiStockDao.insert(s);
        } else {
            int upd = vmiStockDao.update(null, new LambdaUpdateWrapper<com.erp.entity.vmi.VmiStock>()
                    .eq(com.erp.entity.vmi.VmiStock::getId, s.getId())
                    .eq(com.erp.entity.vmi.VmiStock::getVerNo, s.getVerNo())
                    .setSql("QTY = QTY + " + within.toPlainString()));
            if (upd == 0) {
                throw new ServiceException(409, "寄售库存并发更新冲突，请重试：" + gl.getItemCode());
            }
        }
    }

    /** 最近生效 VMI 协议号（寄售库存溯源，可空） */
    private String agreeNoOf(String supplierId, String itemCode) {
        var line = vmiAgreementService.lineFor(supplierId, itemCode);
        if (line == null) {
            return null;
        }
        var a = vmiAgreeDao.selectById(line.getAgreeId());
        return a == null ? null : a.getAgreeNo();
    }

    /** 让步限制快照 JSON（有效期 / 上限 / 使用范围，来源已批准让步接收单） */
    private String concessionLimitOf(String grLineId) {
        var list = concessionDao.selectList(new LambdaQueryWrapper<com.erp.entity.qms.Concession>()
                .eq(com.erp.entity.qms.Concession::getGrLineId, grLineId)
                .eq(com.erp.entity.qms.Concession::getStatus, "APPROVED")
                .orderByDesc(com.erp.entity.qms.Concession::getCreateDate)
                .last("LIMIT 1"));
        if (list.isEmpty()) {
            return "{\"source\":\"CONCESSION\"}";
        }
        var c = list.get(0);
        try {
            return jsonMapper.writeValueAsString(Map.of(
                    "concessionNo", c.getConcessionNo() == null ? "" : c.getConcessionNo(),
                    "until", c.getLimitUntil() == null ? "" : c.getLimitUntil().toString(),
                    "limitQty", c.getLimitQty() == null ? "0" : c.getLimitQty().toPlainString(),
                    "scope", c.getLimitScope() == null ? "" : c.getLimitScope()));
        } catch (Exception e) {
            return "{\"source\":\"CONCESSION\"}";
        }
    }

    private void closeShortDiffs(String poLineId) {
        for (ReceiptDifference d : diffDao.selectList(new LambdaQueryWrapper<ReceiptDifference>()
                .eq(ReceiptDifference::getPoLineId, poLineId)
                .eq(ReceiptDifference::getDiffType, "SHORT")
                .eq(ReceiptDifference::getStatus, "PENDING"))) {
            d.setStatus("CLOSED");
            d.setCloseReason("后续收货补齐（行已收满）");
            d.setUpdateBy(SecurityUtils.getCurrentUserId());
            diffDao.updateById(d);
            log.info("GR difference {} auto CLOSED (shortage filled)", d.getDiffNo());
        }
    }

    /** 入库凭证号：IV + 流水（由 GR_NO 派生，唯一且免二次取号） */
    private String docNoOf(String grNo) {
        return grNo.replaceFirst("^GR", "IV");
    }

    // ================================================================

    @Override
    public List<Map<String, Object>> riskGradeOptions() {
        return List.of(
                Map.of("code", "A", "name", "高风险（24h）"),
                Map.of("code", "B", "name", "中风险（48h）"),
                Map.of("code", "C", "name", "低风险（72h）"));
    }

    // ================================================================
    // 私有
    // ================================================================

    private GoodsReceipt newHead(Map<String, Object> payload, String sourceType) {
        GoodsReceipt gr = new GoodsReceipt();
        gr.setSourceType(sourceType);
        String arrival = str(payload.get("arrivalDate"));
        if (!hasText(arrival)) {
            throw new ServiceException(422, "到货日期必填");
        }
        try {
            gr.setArrivalDate(LocalDate.parse(arrival));
        } catch (RuntimeException e) {
            throw new ServiceException(422, "到货日期格式须为 yyyy-MM-dd");
        }
        gr.setDeliveryNote(str(payload.get("deliveryNote")));
        gr.setBatchNo(str(payload.get("batchNo")));
        gr.setTransportInfo(str(payload.get("transportInfo")));
        gr.setPackageCondition(str(payload.get("packageCondition")));
        gr.setRemark(str(payload.get("remark")));
        return gr;
    }

    private Map<String, Object> poLineRow(PurchaseOrderLine l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("lineNo", l.getLineNo());
        m.put("itemCode", l.getItemCode());
        m.put("itemName", l.getItemName());
        m.put("unit", l.getUnit());
        m.put("qty", l.getQty());
        m.put("unitPrice", l.getUnitPrice());
        m.put("receivedQty", l.getReceivedQty());
        m.put("openQty", openOf(l));
        m.put("lineStatus", l.getLineStatus());
        m.put("reqDate", l.getReqDate());
        return m;
    }

    private BigDecimal openOf(PurchaseOrderLine l) {
        BigDecimal open = nvl(l.getQty()).subtract(nvl(l.getReceivedQty()));
        return open.signum() < 0 ? BigDecimal.ZERO : open;
    }

    private List<PurchaseOrderLine> poLineList(String poId) {
        return poLineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, poId)
                .orderByAsc(PurchaseOrderLine::getLineNo));
    }

    private List<GoodsReceiptLine> lineList(String grId) {
        return lineDao.selectList(new LambdaQueryWrapper<GoodsReceiptLine>()
                .eq(GoodsReceiptLine::getGrId, grId)
                .orderByAsc(GoodsReceiptLine::getLineNo));
    }

    private PurchaseOrder requirePo(String id) {
        PurchaseOrder po = poDao.selectById(id);
        if (po == null) {
            throw new ServiceException(404, "PO 不存在");
        }
        return po;
    }

    private GoodsReceipt requireGr(String id) {
        GoodsReceipt gr = grDao.selectById(id);
        if (gr == null) {
            throw new ServiceException(404, "收货单不存在");
        }
        return gr;
    }

    private ReceiptDifference requireDiff(String id) {
        ReceiptDifference d = diffDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "差异单不存在");
        }
        return d;
    }

    private void requireSupplierActive(String supplierId) {
        MdmSupplier s = supplierDao.selectById(supplierId);
        if (s == null) {
            throw new ServiceException(404, "供应商不存在");
        }
        if ("FROZEN".equals(s.getStatus()) || "DISABLED".equals(s.getStatus())) {
            throw new ServiceException(422, "供应商状态为 " + s.getStatus() + "，禁止收货与过账（BR-4.2-28）");
        }
    }

    // ---------- 编号 ----------

    private String nextGrNo() {
        String prefix = "GR" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        Integer max = grDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String nextDiffNo() {
        String prefix = "GDF-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = diffDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private String nextAdjNo() {
        String prefix = "GRADJ-" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = adjDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    // ---------- 工具 ----------

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal decimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal b) {
            return b;
        }
        try {
            return new BigDecimal(String.valueOf(o));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        if (o instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object x : list) {
                out.add((Map<String, Object>) x);
            }
            return out;
        }
        return new ArrayList<>();
    }
}
