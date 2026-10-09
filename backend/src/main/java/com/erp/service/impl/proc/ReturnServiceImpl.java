package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.proc.ReturnOrderDao;
import com.erp.dao.proc.ReturnOrderLineDao;
import com.erp.dao.qms.NcrDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.proc.ReturnOrder;
import com.erp.entity.proc.ReturnOrderLine;
import com.erp.entity.qms.Ncr;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.proc.ReturnService;
import com.erp.service.qms.NcrService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 退货单实现（spec quality-return，tasks 8.1~8.5，2.6.1）。
 * NCR 自动带出（BR-4.2-32 未过账例外口：仅关联 PO/收货单按 PO 计价）；
 * 出库单事务：库存扣减（QC 锁定优先，不足 422）/未入库置行 REJECTED + 红字凭证 RV
 * + 应付冲减（ap-accrual 真实冲减，D4 已接）+ NCR 处置推进 + 30 天跟踪起算。
 */
@Slf4j
@Service
public class ReturnServiceImpl implements ReturnService {

    /** 结构化退货原因枚举（spec other-return） */
    private static final java.util.Set<String> REASON_TYPES =
            java.util.Set.of("WRONG_ITEM", "OVER_SHIP", "QUALITY_FOUND", "OTHER");


    private final ReturnOrderDao returnDao;
    private final ReturnOrderLineDao lineReturnDao;
    private final GoodsReceiptDao grDao;
    private final GoodsReceiptLineDao grLineDao;
    private final PurchaseOrderLineDao poLineDao;
    private final InvStockDao stockDao;
    private final NcrDao ncrDao;
    private final ApprovalEngine approvalEngine;
    private final NcrService ncrService;
    private final com.erp.service.qms.CopqService copqService;
    /** 应付冲减（BR-4.2-34 落地，add-accrual-three-way-match D5） */
    private final com.erp.service.fin.AccrualService accrualService;
    private final com.erp.service.inv.StockPostingEngine stockPostingEngine;

    public ReturnServiceImpl(ReturnOrderDao returnDao,
                             ReturnOrderLineDao lineReturnDao,
                             GoodsReceiptDao grDao,
                             GoodsReceiptLineDao grLineDao,
                             PurchaseOrderLineDao poLineDao,
                             InvStockDao stockDao,
                             NcrDao ncrDao,
                             ApprovalEngine approvalEngine,
                             @Lazy NcrService ncrService,
                             com.erp.service.qms.CopqService copqService,
                             com.erp.service.fin.AccrualService accrualService,
                             com.erp.service.inv.StockPostingEngine stockPostingEngine) {
        this.returnDao = returnDao;
        this.lineReturnDao = lineReturnDao;
        this.grDao = grDao;
        this.grLineDao = grLineDao;
        this.poLineDao = poLineDao;
        this.stockDao = stockDao;
        this.ncrDao = ncrDao;
        this.approvalEngine = approvalEngine;
        this.ncrService = ncrService;
        this.copqService = copqService;
        this.accrualService = accrualService;
        this.stockPostingEngine = stockPostingEngine;
    }

    // ================= 8.1 NCR 自动带出 =================

    @Override
    @Transactional
    public ReturnOrder createFromNcr(Ncr ncr) {
        if (ncr == null) {
            throw new ServiceException(422, "NCR 缺失");
        }
        // 幂等：同 NCR 已有未作废退货单
        ReturnOrder existed = returnDao.selectOne(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getNcrId, ncr.getId())
                .notIn(ReturnOrder::getStatus, "CANCELLED")
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }
        if (!"RETURN".equals(ncr.getDisposition()) && !"RETURNING".equals(ncr.getStatus())) {
            throw new ServiceException(422, "NCR 处置须为退货：" + ncr.getStatus() + "/" + ncr.getDisposition());
        }

        ReturnOrder r = new ReturnOrder();
        r.setReturnNo(nextReturnNo());
        r.setSourceType("NCR");
        r.setNcrId(ncr.getId());
        r.setNcrNo(ncr.getNcrNo());
        r.setPoId(ncr.getPoId());
        r.setPoNo(ncr.getPoNo());
        r.setGrId(ncr.getGrId());
        if (hasText(ncr.getGrId())) {
            GoodsReceipt gr = grDao.selectById(ncr.getGrId());
            if (gr != null) {
                r.setGrNo(gr.getGrNo());
                if (!hasText(r.getPoId())) {
                    r.setPoId(gr.getPoId());
                }
                if (!hasText(r.getPoNo())) {
                    r.setPoNo(gr.getPoNo());
                }
            }
        }
        r.setSupplierId(ncr.getSupplierId());
        r.setSupplierName(ncr.getSupplierName());
        r.setTotalQty(ncr.getQty());
        r.setStatus("DRAFT");
        r.setPerfFlag("1"); // 质量退货打标（D4：绩效扣分标记，绩效模块未建留痕）
        r.setReturnReason("NCR 不合格退货：" + ncr.getNcrNo()
                + (hasText(ncr.getDefectItem()) ? "（" + ncr.getDefectItem() + "）" : ""));
        r.setCreateBy(SecurityUtils.getCurrentUserId());
        returnDao.insert(r);

        // 行：计价按 PO 单价（BR-4.2-32）
        ReturnOrderLine l = new ReturnOrderLine();
        l.setReturnId(r.getId());
        l.setLineNo(1);
        l.setGrLineId(ncr.getGrLineId());
        l.setItemCode(ncr.getItemCode());
        l.setItemName(ncr.getItemName());
        l.setBatchNo(ncr.getBatchNo());
        l.setQty(ncr.getQty());
        BigDecimal price = poUnitPriceOf(ncr.getGrLineId());
        l.setUnitPrice(price);
        l.setAmount(ncr.getQty() == null ? BigDecimal.ZERO
                : ncr.getQty().multiply(price).setScale(2, java.math.RoundingMode.HALF_UP));
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        lineReturnDao.insert(l);
        r.setTotalAmt(l.getAmount());
        returnDao.updateById(r);
        log.info("return {} auto-created from NCR {} amount={}", r.getReturnNo(), ncr.getNcrNo(), l.getAmount());
        return r;
    }

    // ================= 手工发起 =================

    @Override
    @Transactional
    public ReturnOrder createManual(Map<String, Object> body) {
        // ---------- 1) 结构化退货原因（spec other-return：四枚举，非法 422） ----------
        String reasonType = str(body.get("reasonType"));
        if (!hasText(reasonType) || !REASON_TYPES.contains(reasonType)) {
            throw new ServiceException(422, "退货原因类型必填，仅支持：供应商发错货（WRONG_ITEM）/"
                    + "多发货（OVER_SHIP）/到货后发现质量问题（QUALITY_FOUND）/其他（OTHER）");
        }
        String itemCode = str(body.get("itemCode"));
        if (!hasText(itemCode)) {
            throw new ServiceException(422, "物料编码必填");
        }
        String poId = str(body.get("poId"));
        String batchNo = str(body.get("batchNo")) == null ? "" : str(body.get("batchNo"));
        BigDecimal qty;
        try {
            qty = new BigDecimal(String.valueOf(body.get("qty")));
        } catch (Exception e) {
            throw new ServiceException(422, "退货数量必须为数字");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "退货数量必须大于 0");
        }

        // ---------- 2) 后端反查（D3）：原入库单价 / 入库凭证 / 关联单据，前端传值一律忽略 ----------
        if (!hasText(poId)) {
            throw new ServiceException(422, "原 PO 关联必填（BR-4.2-32）");
        }
        GoodsReceipt gr = grDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getPoId, poId)
                .eq(GoodsReceipt::getStatus, "POSTED")
                .orderByDesc(GoodsReceipt::getCreateDate)
                .last("LIMIT 1"));
        if (gr == null) {
            throw new ServiceException(422, "该 PO 无已入库过账记录，非质量退货仅支持已入库物料（BR-4.2-32）");
        }
        GoodsReceiptLine grLine = grLineDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<GoodsReceiptLine>()
                .eq(GoodsReceiptLine::getGrId, gr.getId())
                .eq(GoodsReceiptLine::getItemCode, itemCode)
                .last("LIMIT 1"));
        if (grLine == null) {
            throw new ServiceException(422, "该 PO 的入库记录中无此物料，无法关联原入库单（BR-4.2-32）");
        }
        String originDocNo = gr.getPostingDocNo();
        if (!hasText(originDocNo)) {
            throw new ServiceException(422, "原入库凭证缺失，无法建立关联（BR-4.2-32）");
        }
        BigDecimal unitPrice = grLine.getUnitPrice();
        if (unitPrice == null || unitPrice.signum() <= 0) {
            throw new ServiceException(422, "原入库单价缺失或非正数，无法自动计价");
        }

        // ---------- 3) 可退库存校验（qty ≤ 可退量） ----------
        BigDecimal returnable = batchQty(itemCode, batchNo);
        if (returnable.signum() <= 0) {
            throw new ServiceException(422, "该物料批次无可退货库存（非质量退货仅支持已入库物料）");
        }
        if (qty.compareTo(returnable) > 0) {
            throw new ServiceException(422, "退货数量超出可退库存量（可退 " + returnable.stripTrailingZeros().toPlainString() + "）");
        }

        // ---------- 4) 落库：金额 = qty × 反查单价（系统自动计算） ----------
        ReturnOrder r = new ReturnOrder();
        r.setReturnNo(nextReturnNo());
        r.setSourceType("MANUAL");
        r.setPoId(poId);
        r.setPoNo(gr.getPoNo());
        r.setGrId(gr.getId());
        r.setGrNo(gr.getGrNo());
        r.setOriginDocNo(originDocNo);
        r.setSupplierId(gr.getSupplierId());
        r.setSupplierName(gr.getSupplierName());
        r.setTotalQty(qty);
        r.setStatus("DRAFT");
        r.setPerfFlag("0"); // 非质量退货不扣分（BR-4.2-34：仅质量退货扣分）
        r.setReasonType(reasonType);
        r.setReturnReason(str(body.get("note"))); // 补充说明（可选）
        r.setCreateBy(SecurityUtils.getCurrentUserId());
        returnDao.insert(r);

        ReturnOrderLine l = new ReturnOrderLine();
        l.setReturnId(r.getId());
        l.setLineNo(1);
        l.setItemCode(itemCode);
        l.setItemName(str(body.get("itemName")));
        l.setBatchNo(batchNo);
        l.setQty(qty);
        l.setUnitPrice(unitPrice);
        l.setAmount(qty.multiply(unitPrice).setScale(2, java.math.RoundingMode.HALF_UP));
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        lineReturnDao.insert(l);
        r.setTotalAmt(l.getAmount());
        returnDao.updateById(r);
        return r;
    }

    /** PO 可退入库带出（spec other-return D2）：聚合 POSTED 收货行 + 库存可退量 + 原入库单价 */
    @Override
    public List<Map<String, Object>> poReturnables(String poId) {
        if (!hasText(poId)) {
            throw new ServiceException(422, "原 PO 必填");
        }
        List<GoodsReceipt> grs = grDao.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getPoId, poId)
                .eq(GoodsReceipt::getStatus, "POSTED")
                .orderByDesc(GoodsReceipt::getCreateDate));
        if (grs.isEmpty()) {
            throw new ServiceException(422, "该 PO 无已入库过账记录，无可退入库（BR-4.2-32）");
        }
        List<String> grIds = grs.stream().map(GoodsReceipt::getId).toList();
        List<GoodsReceiptLine> lines = grLineDao.selectList(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<GoodsReceiptLine>()
                .in(GoodsReceiptLine::getGrId, grIds)
                .eq(GoodsReceiptLine::getStatus, "POSTED"));
        Map<String, GoodsReceipt> grById = new java.util.HashMap<>();
        grs.forEach(g -> grById.put(g.getId(), g));
        // 同 item+batch 取最近一张入库凭证（默认），聚合可退量
        Map<String, Map<String, Object>> agg = new java.util.LinkedHashMap<>();
        for (GoodsReceiptLine line : lines) {
            GoodsReceipt gr = grById.get(line.getGrId());
            if (gr == null) {
                continue;
            }
            String batch = gr.getBatchNo() == null ? "" : gr.getBatchNo();
            String key = line.getItemCode() + "|" + batch;
            BigDecimal returnable = batchQty(line.getItemCode(), batch);
            Map<String, Object> row = agg.get(key);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("itemCode", line.getItemCode());
                row.put("itemName", line.getItemName());
                row.put("batchNo", batch);
                row.put("grId", gr.getId());
                row.put("grNo", gr.getGrNo());
                row.put("originDocNo", gr.getPostingDocNo()); // 入库凭证号（最近一张）
                row.put("unitPrice", line.getUnitPrice());    // 原入库单价（只读）
                row.put("returnableQty", returnable);         // 可退库存量
                row.put("poId", poId);
                row.put("poNo", gr.getPoNo());
                row.put("supplierId", gr.getSupplierId());
                row.put("supplierName", gr.getSupplierName());
                row.put("docList", new java.util.ArrayList<String>());
                agg.put(key, row);
            }
            @SuppressWarnings("unchecked")
            java.util.List<String> docs = (java.util.List<String>) row.get("docList");
            if (hasText(gr.getPostingDocNo()) && !docs.contains(gr.getPostingDocNo())) {
                docs.add(gr.getPostingDocNo());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(agg.values());
        if (out.isEmpty()) {
            throw new ServiceException(422, "该 PO 无可退的已入库收货行（BR-4.2-32）");
        }
        return out;
    }

    // ================= 8.6 红字凭证台账（2.6.3，spec red-receipt-voucher） =================

    @Override
    public Page<Map<String, Object>> redVouchers(long current, long size, String redDocNo,
                                                 String returnNo, String supplierId,
                                                 String sourceType, String dateFrom, String dateTo) {
        LambdaQueryWrapper<ReturnOrder> qw = new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getStatus, "OUT_DONE")
                .isNotNull(ReturnOrder::getRedDocNo)
                .like(hasText(redDocNo), ReturnOrder::getRedDocNo, redDocNo)
                .like(hasText(returnNo), ReturnOrder::getReturnNo, returnNo)
                .eq(hasText(supplierId), ReturnOrder::getSupplierId, supplierId)
                .eq(hasText(sourceType), ReturnOrder::getSourceType, sourceType)
                .ge(hasText(dateFrom), ReturnOrder::getOutDate, dateFrom + " 00:00:00")
                .le(hasText(dateTo), ReturnOrder::getOutDate, dateTo + " 23:59:59")
                .orderByDesc(ReturnOrder::getOutDate);
        Page<ReturnOrder> raw = returnDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReturnOrder r : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("redDocNo", r.getRedDocNo());           // 红字入库凭证号（RV）
            m.put("returnNo", r.getReturnNo());
            m.put("sourceType", r.getSourceType());
            m.put("reasonType", r.getReasonType());
            m.put("supplierId", r.getSupplierId());
            m.put("supplierName", r.getSupplierName());
            m.put("poNo", r.getPoNo());                   // 关联链
            m.put("grNo", r.getGrNo());
            m.put("originDocNo", r.getOriginDocNo());
            m.put("ncrNo", r.getNcrNo());
            m.put("totalQty", r.getTotalQty());
            m.put("totalAmt", r.getTotalAmt());
            m.put("outDate", r.getOutDate());
            m.put("outBy", r.getOutBy());
            m.put("returnReason", r.getReturnReason());
            m.put("lines", lineReturnDao.selectList(new LambdaQueryWrapper<ReturnOrderLine>()
                    .eq(ReturnOrderLine::getReturnId, r.getId())
                    .orderByAsc(ReturnOrderLine::getLineNo)));
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    // ================= 8.2 审批（底座单签采购经理） =================

    @Override
    @Transactional
    public ReturnOrder submit(String id) {
        ReturnOrder r = require(id);
        if ("PENDING_APPROVE".equals(r.getStatus())) {
            throw new ServiceException(422, "审批中，不可重复提交");
        }
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus())) {
            throw new ServiceException(422, "当前状态不可提交：" + r.getStatus());
        }
        List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                ApprovalNodeSpec.sign("ROLE_PM", "采购经理审批")));
        var inst = approvalEngine.submit("Return", r.getId(),
                "退货审批：" + r.getReturnNo() + " / " + r.getSupplierName(),
                "ROLE_PM", chain);
        r.setStatus("PENDING_APPROVE");
        r.setApprovalId(inst.getId());
        if (returnDao.updateById(r) == 0) {
            throw new ServiceException(409, "退货单状态更新冲突");
        }
        return r;
    }

    @Override
    @Transactional
    public ReturnOrder cancel(String id, String reason) {
        ReturnOrder r = require(id);
        if ("OUT_DONE".equals(r.getStatus())) {
            throw new ServiceException(422, "已出库只读归档，不可作废");
        }
        if (!"DRAFT".equals(r.getStatus()) && !"REJECTED".equals(r.getStatus())) {
            throw new ServiceException(422, "仅草稿/被驳回可作废：" + r.getStatus());
        }
        if (!hasText(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "作废原因必填（至少 2 字）");
        }
        r.setStatus("CANCELLED");
        r.setRejectReason(reason);
        returnDao.updateById(r);
        return r;
    }

    // ================= 8.3 退货出库单事务 =================

    @Override
    @Transactional
    public ReturnOrder posting(String id) {
        ReturnOrder r = require(id);
        if (!"APPROVED".equals(r.getStatus())) {
            throw new ServiceException(422, "未审批不可出库（当前 " + r.getStatus() + "）");
        }
        List<ReturnOrderLine> lines = lineReturnDao.selectList(new LambdaQueryWrapper<ReturnOrderLine>()
                .eq(ReturnOrderLine::getReturnId, r.getId())
                .orderByAsc(ReturnOrderLine::getLineNo));
        if (lines.isEmpty()) {
            throw new ServiceException(422, "退货单无行");
        }
        String userId = SecurityUtils.getCurrentUserId();
        boolean stockDeducted = false;
        for (ReturnOrderLine l : lines) {
            // 先按 GR 行状态分流：未过账行的量从未入库 → 行置 REJECTED（不碰同批次其他来源库存）
            GoodsReceiptLine gl = hasText(l.getGrLineId()) ? grLineDao.selectById(l.getGrLineId()) : null;
            if (gl != null && "PENDING".equals(gl.getStatus())) {
                gl.setStatus("REJECTED");
                grLineDao.updateById(gl);
                continue;
            }
            List<InvStock> batchRows = batchRows(l.getItemCode(),
                    l.getBatchNo() == null ? "" : l.getBatchNo());
            BigDecimal batchQtyTotal = BigDecimal.ZERO;
            BigDecimal qc = BigDecimal.ZERO;
            BigDecimal av = BigDecimal.ZERO;
            for (InvStock br : batchRows) {
                batchQtyTotal = batchQtyTotal.add(nvl(br.getQty()));
                qc = qc.add(nvl(br.getQcQty()));
                av = av.add(nvl(br.getAvailableQty()));
            }
            if (!batchRows.isEmpty() && batchQtyTotal.signum() > 0) {
                // 库存扣减预检：批次级合计（位行粒度下同批次可多行，A1 适配）；
                // 实扣经通用引擎 qcFirst 跨位行拆分（quality/other-return MODIFIED）
                BigDecimal need = l.getQty();
                BigDecimal fromQc = need.min(qc);
                BigDecimal fromAv = need.subtract(fromQc).min(av);
                BigDecimal left = need.subtract(fromQc).subtract(fromAv);
                if (left.signum() > 0) {
                    throw new ServiceException(422, "库存不足：" + l.getItemCode() + " 批次 "
                            + (l.getBatchNo() == null ? "—" : l.getBatchNo())
                            + " 可退 " + qc.add(av).stripTrailingZeros().toPlainString()
                            + "，本次 " + need.stripTrailingZeros().toPlainString());
                }
                // 扣减执行经通用引擎（add-stock-posting-engine，quality/other-return MODIFIED：
                // qcFirst 核销锁定量优先 + 双列/恒等 + 流水；上方预检保留「可退」文案）
                InvStock s = batchRows.get(0);
                com.erp.service.inv.StockPostingEngine.Line el =
                        new com.erp.service.inv.StockPostingEngine.Line();
                el.warehouseCode = hasText(s.getWarehouseCode())
                        ? s.getWarehouseCode() : InvStock.DEFAULT_WH;
                el.itemCode = l.getItemCode();
                el.itemName = l.getItemName();
                el.batchNo = l.getBatchNo() == null ? "" : l.getBatchNo();
                el.qty = need;
                el.qcFirst = true;
                el.batchSpecified = true;
                String typeCode = "MANUAL".equals(r.getSourceType())
                        ? "OTHER_RETURN_OUT" : "QUALITY_RETURN_OUT";
                stockPostingEngine.post(com.erp.service.inv.StockPostingEngine.Request.of(
                        typeCode, "RETURN", r.getReturnNo(),
                        java.util.List.of(el)));
                stockDeducted = true;
            } else if (gl != null) {
                // 已过账但无库存可扣（他方已领用/盘点出清）→ 阻断
                throw new ServiceException(422, "库存不足：" + l.getItemCode()
                        + " 批次无剩余可退量");
            } else {
                throw new ServiceException(422, "无库存且无关联收货行，无法出库：" + l.getItemCode());
            }
        }

        // 红字入库凭证（RV 号）
        r.setRedDocNo(nextRedDocNo());
        r.setOutBy(userId);
        r.setOutDate(LocalDateTime.now());
        r.setStatus("OUT_DONE");
        r.setTrackDueDate(LocalDate.now().plusDays(30));
        r.setTrackStatus("OPEN");
        if (returnDao.updateById(r) == 0) {
            throw new ServiceException(409, "退货单状态更新冲突");
        }

        // 应付冲减（BR-4.2-34 落地，change add-accrual-three-way-match D5）：
        // 同供应商同 PO 的 OPEN 暂估倒序累加冲减；挂起批次跳过；不足部分记应付借项凭证。
        // 失败随出库事务整体回滚（不产生半冲减）。
        BigDecimal offsetted = accrualService.offset(r.getSupplierId(), r.getPoNo(),
                r.getTotalAmt(), r.getReturnNo());
        log.info("return {} red {} 应付冲减 {} / 退货金额 {}",
                r.getReturnNo(), r.getRedDocNo(), offsetted, r.getTotalAmt());

        // COPQ 归集（tasks 9.6）：退货金额记内部失败成本（来源 RETURN，幂等按 sourceId）
        if (r.getTotalAmt() != null && r.getTotalAmt().signum() > 0) {
            try {
                copqService.record(java.util.Map.of(
                        "category", "INTERNAL_FAILURE",
                        "amount", r.getTotalAmt(),
                        "sourceType", "RETURN",
                        "sourceId", r.getId(),
                        "sourceNo", r.getReturnNo(),
                        "ncrId", r.getNcrId() == null ? "" : r.getNcrId(),
                        "itemCode", lines.get(0).getItemCode() == null ? "" : lines.get(0).getItemCode(),
                        "batchNo", lines.get(0).getBatchNo() == null ? "" : lines.get(0).getBatchNo(),
                        "supplierId", r.getSupplierId() == null ? "" : r.getSupplierId(),
                        "supplierName", r.getSupplierName() == null ? "" : r.getSupplierName()));
            } catch (Exception ex) {
                log.warn("COPQ record failed for return {}: {}", r.getReturnNo(), ex.getMessage());
            }
        }

        // NCR 处置推进（RETURNING → DISPOSED，含处置凭证日志）；已提前确认的幂等跳过
        if ("NCR".equals(r.getSourceType()) && hasText(r.getNcrId())) {
            Ncr ncr = ncrDao.selectById(r.getNcrId());
            if (ncr != null && "RETURNING".equals(ncr.getStatus())) {
                ncrService.confirmDisposed(ncr.getId(), "退货出库完成，红字凭证 " + r.getRedDocNo());
            }
        }
        log.info("return {} posted: red={}, stockDeducted={}", r.getReturnNo(), r.getRedDocNo(), stockDeducted);
        return r;
    }

    // ================= 8.4 跟踪 =================

    @Override
    @Transactional
    public ReturnOrder closeTrack(String id, String remark) {
        ReturnOrder r = require(id);
        if (!"OUT_DONE".equals(r.getStatus())) {
            throw new ServiceException(422, "出库后才有跟踪任务：" + r.getStatus());
        }
        if (!"OPEN".equals(r.getTrackStatus()) && !"OVERDUE".equals(r.getTrackStatus())) {
            throw new ServiceException(422, "跟踪任务已闭环或不存在：" + r.getTrackStatus());
        }
        r.setTrackStatus("CLOSED");
        r.setTrackClosedDate(LocalDateTime.now());
        if (hasText(remark)) {
            r.setRemark(remark);
        }
        returnDao.updateById(r);
        return r;
    }

    @Override
    public int sweepTrackOverdue() {
        List<ReturnOrder> due = returnDao.selectList(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getTrackStatus, "OPEN")
                .lt(ReturnOrder::getTrackDueDate, LocalDate.now())
                .last("LIMIT 200"));
        for (ReturnOrder r : due) {
            r.setTrackStatus("OVERDUE");
            returnDao.updateById(r);
            log.warn("return {} 补发/退款跟踪逾期（{} 到期），请督办供应商",
                    r.getReturnNo(), r.getTrackDueDate());
        }
        return due.size();
    }

    // ================= 查询 =================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String status,
                                          String sourceType) {
        LambdaQueryWrapper<ReturnOrder> qw = new LambdaQueryWrapper<ReturnOrder>()
                .eq(hasText(status), ReturnOrder::getStatus, status)
                .eq(hasText(sourceType), ReturnOrder::getSourceType, sourceType)
                .and(hasText(keyword), w -> w.like(ReturnOrder::getReturnNo, keyword)
                        .or().like(ReturnOrder::getSupplierName, keyword)
                        .or().like(ReturnOrder::getNcrNo, keyword)
                        .or().like(ReturnOrder::getPoNo, keyword))
                .orderByDesc(ReturnOrder::getCreateDate);
        Page<ReturnOrder> raw = returnDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (ReturnOrder r : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("returnNo", r.getReturnNo());
            m.put("sourceType", r.getSourceType());
            m.put("ncrNo", r.getNcrNo());
            m.put("poNo", r.getPoNo());
            m.put("grNo", r.getGrNo());
            m.put("originDocNo", r.getOriginDocNo());
            m.put("supplierName", r.getSupplierName());
            m.put("totalQty", r.getTotalQty());
            m.put("totalAmt", r.getTotalAmt());
            m.put("status", r.getStatus());
            m.put("redDocNo", r.getRedDocNo());
            m.put("trackStatus", r.getTrackStatus());
            m.put("trackDueDate", r.getTrackDueDate());
            m.put("trackOverdue", "OPEN".equals(r.getTrackStatus()) && r.getTrackDueDate() != null
                    && r.getTrackDueDate().isBefore(today));
            m.put("perfFlag", r.getPerfFlag());
            m.put("createDate", r.getCreateDate());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        ReturnOrder r = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("return", r);
        out.put("lines", lineReturnDao.selectList(new LambdaQueryWrapper<ReturnOrderLine>()
                .eq(ReturnOrderLine::getReturnId, r.getId())
                .orderByAsc(ReturnOrderLine::getLineNo)));
        if (hasText(r.getApprovalId())) {
            out.put("approval", approvalEngine.getInstance(r.getApprovalId()));
        }
        if (hasText(r.getNcrId())) {
            out.put("ncr", ncrDao.selectById(r.getNcrId()));
        }
        return out;
    }

    @Override
    public List<String> nosByNcr(String ncrId) {
        List<ReturnOrder> list = returnDao.selectList(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getNcrId, ncrId)
                .notIn(ReturnOrder::getStatus, "CANCELLED")
                .orderByDesc(ReturnOrder::getCreateDate));
        List<String> nos = new ArrayList<>();
        for (ReturnOrder r : list) {
            nos.add(r.getReturnNo());
        }
        return nos;
    }

    // ================= 内部 =================

    /** PO 单价（BR-4.2-32：质检退货按 PO 单价；FREE 行无价为 0） */
    private BigDecimal poUnitPriceOf(String grLineId) {
        if (!hasText(grLineId)) {
            return BigDecimal.ZERO;
        }
        GoodsReceiptLine gl = grLineDao.selectById(grLineId);
        if (gl == null || !hasText(gl.getPoLineId())) {
            return BigDecimal.ZERO;
        }
        PurchaseOrderLine pl = poLineDao.selectById(gl.getPoLineId());
        return pl == null || pl.getUnitPrice() == null ? BigDecimal.ZERO : pl.getUnitPrice();
    }

    /** 批次全部位行（inbound → bin 序；位行粒度下同批次可多行，A1 适配） */
    private List<InvStock> batchRows(String itemCode, String batchNo) {
        return stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, InvStock.DEFAULT_WH)
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo == null ? "" : batchNo)
                .orderByAsc(InvStock::getInboundDate)
                .orderByAsc(InvStock::getBinCode));
    }

    /** 批次在手合计（可退量口径 = 跨位行合计，A1 适配） */
    private BigDecimal batchQty(String itemCode, String batchNo) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvStock r : batchRows(itemCode, batchNo)) {
            total = total.add(nvl(r.getQty()));
        }
        return total;
    }

    private ReturnOrder require(String id) {
        ReturnOrder r = returnDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "退货单不存在：" + id);
        }
        return r;
    }

    private String nextReturnNo() {
        String prefix = "RT" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = returnDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String nextRedDocNo() {
        String prefix = "RV" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = returnDao.selectMaxRedSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
