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
 * + 应付冲减 TODO-NOTIFY 桩（D4）+ NCR 处置推进 + 30 天跟踪起算。
 */
@Slf4j
@Service
public class ReturnServiceImpl implements ReturnService {

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

    public ReturnServiceImpl(ReturnOrderDao returnDao,
                             ReturnOrderLineDao lineReturnDao,
                             GoodsReceiptDao grDao,
                             GoodsReceiptLineDao grLineDao,
                             PurchaseOrderLineDao poLineDao,
                             InvStockDao stockDao,
                             NcrDao ncrDao,
                             ApprovalEngine approvalEngine,
                             @Lazy NcrService ncrService,
                             com.erp.service.qms.CopqService copqService) {
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
        String itemCode = str(body.get("itemCode"));
        String batchNo = str(body.get("batchNo")) == null ? "" : str(body.get("batchNo"));
        if (!hasText(itemCode)) {
            throw new ServiceException(422, "物料编码必填");
        }
        BigDecimal qty;
        try {
            qty = new BigDecimal(String.valueOf(body.get("qty")));
        } catch (Exception e) {
            throw new ServiceException(422, "退货数量必须为数字");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "退货数量必须大于 0");
        }
        String reason = str(body.get("returnReason"));
        if (!hasText(reason)) {
            throw new ServiceException(422, "退货原因必填");
        }

        // 已入库判定：存在库存行 → 必须关联原入库单 + 原入库单价（缺失 422）
        InvStock stock = stockOf(itemCode, batchNo);
        boolean stocked = stock != null && stock.getQty() != null && stock.getQty().signum() > 0;
        String originDocNo = str(body.get("originDocNo"));
        BigDecimal unitPrice = null;
        try {
            String up = str(body.get("unitPrice"));
            unitPrice = hasText(up) ? new BigDecimal(up) : null;
        } catch (Exception e) {
            throw new ServiceException(422, "原入库单价必须为数字");
        }
        List<String> missing = new ArrayList<>();
        if (stocked) {
            if (!hasText(originDocNo)) {
                missing.add("原入库单号（已入库退货必填，BR-4.2-33）");
            }
            if (unitPrice == null) {
                missing.add("原入库单价（已入库退货必填）");
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "手工退货要素缺失：" + String.join("、", missing));
        }

        ReturnOrder r = new ReturnOrder();
        r.setReturnNo(nextReturnNo());
        r.setSourceType("MANUAL");
        r.setPoId(str(body.get("poId")));
        r.setPoNo(str(body.get("poNo")));
        r.setGrId(str(body.get("grId")));
        r.setOriginDocNo(originDocNo);
        r.setSupplierId(str(body.get("supplierId")));
        r.setSupplierName(str(body.get("supplierName")));
        r.setTotalQty(qty);
        r.setStatus("DRAFT");
        r.setPerfFlag("0"); // 非质量手工退货不扣分（tasks 8.5）
        r.setReturnReason(reason);
        r.setCreateBy(SecurityUtils.getCurrentUserId());
        returnDao.insert(r);

        ReturnOrderLine l = new ReturnOrderLine();
        l.setReturnId(r.getId());
        l.setLineNo(1);
        l.setItemCode(itemCode);
        l.setItemName(str(body.get("itemName")));
        l.setBatchNo(batchNo);
        l.setQty(qty);
        l.setUnitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice);
        l.setAmount(qty.multiply(l.getUnitPrice()).setScale(2, java.math.RoundingMode.HALF_UP));
        l.setCreateBy(SecurityUtils.getCurrentUserId());
        lineReturnDao.insert(l);
        r.setTotalAmt(l.getAmount());
        returnDao.updateById(r);
        return r;
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
            InvStock s = stockOf(l.getItemCode(), l.getBatchNo() == null ? "" : l.getBatchNo());
            if (s != null && s.getQty() != null && s.getQty().signum() > 0) {
                // 库存扣减：QC 锁定量优先，再扣 AVAILABLE，不足 422（tasks 8.3）
                BigDecimal need = l.getQty();
                BigDecimal qc = nvl(s.getQcQty());
                BigDecimal av = nvl(s.getAvailableQty());
                BigDecimal fromQc = need.min(qc);
                BigDecimal fromAv = need.subtract(fromQc).min(av);
                BigDecimal left = need.subtract(fromQc).subtract(fromAv);
                if (left.signum() > 0) {
                    throw new ServiceException(422, "库存不足：" + l.getItemCode() + " 批次 "
                            + (l.getBatchNo() == null ? "—" : l.getBatchNo())
                            + " 可退 " + qc.add(av).stripTrailingZeros().toPlainString()
                            + "，本次 " + need.stripTrailingZeros().toPlainString());
                }
                int rows = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                        .eq(InvStock::getId, s.getId())
                        .eq(InvStock::getVerNo, s.getVerNo())
                        .setSql("QTY = QTY - " + need.toPlainString())
                        .setSql("QC_QTY = QC_QTY - " + fromQc.toPlainString())
                        .setSql("AVAILABLE_QTY = AVAILABLE_QTY - " + fromAv.toPlainString()));
                if (rows == 0) {
                    throw new ServiceException(409, "库存扣减并发冲突，请重试：" + l.getItemCode());
                }
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

        // 应付冲减（2.7 财务未建 → TODO-NOTIFY 桩，D4）
        log.info("[TODO-NOTIFY] return {} red {} 应付冲减与供应商扣款（2.7/8.3 桩）",
                r.getReturnNo(), r.getRedDocNo());

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

    private InvStock stockOf(String itemCode, String batchNo) {
        return stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchNo == null ? "" : batchNo)
                .last("LIMIT 1"));
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
