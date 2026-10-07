package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinAccrualDao;
import com.erp.dao.fin.FinApInvoiceDao;
import com.erp.dao.fin.FinApInvoiceLineDao;
import com.erp.dao.fin.FinMatchResultDao;
import com.erp.dao.proc.GoodsReceiptDao;
import com.erp.dao.proc.GoodsReceiptLineDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.entity.fin.FinAccrual;
import com.erp.entity.fin.FinApInvoice;
import com.erp.entity.fin.FinApInvoiceLine;
import com.erp.entity.fin.FinMatchResult;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.service.fin.AccrualService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.fin.PrepaymentService;
import com.erp.service.fin.ThreeWayMatchService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三方匹配实现（2.7.2，spec three-way-match，design D7/D8/D9/D10）。
 * 发票行 ↔ PO 行 ↔ 已过账收货行逐行比对；MATCHED 单事务自动冲回暂估 + 转正式应付；
 * EXCEPTION 冻结待采购员确认；跨期（自然月近似）确认后须 ADMIN 手工过账。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThreeWayMatchServiceImpl implements ThreeWayMatchService {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 三单匹配容差（TOLERANCE_DEFAULT，与收货容差同一参数源，design D7） */
    @Value("${app.proc.receipt-tolerance:0.005}")
    private BigDecimal matchTolerance;

    private final FinApInvoiceDao invoiceDao;
    private final FinApInvoiceLineDao invoiceLineDao;
    private final FinMatchResultDao matchDao;
    private final FinAccrualDao accrualDao;
    private final GoodsReceiptDao grDao;
    private final GoodsReceiptLineDao grLineDao;
    private final PurchaseOrderLineDao poLineDao;
    private final AccrualService accrualService;
    private final GlVoucherService voucherService;
    /** 预付冲抵挂点（spec prepayment，design D7：发票 POSTED 后 best-effort 触发） */
    private final PrepaymentService prepaymentService;
    /** VMI 分支（MODIFIED three-way-match，add-consignment-procurement） */
    private final com.erp.dao.proc.PurchaseOrderDao poDao;
    private final com.erp.dao.vmi.VmiSettlementDao settleDao;
    private final com.erp.dao.vmi.VmiSettlementLineDao settleLineDao;
    private final com.erp.dao.vmi.MaterialIssueDao issueDao;
    private final com.erp.service.vmi.VmiSettlementService settlementService;
    private final com.fasterxml.jackson.databind.ObjectMapper jsonMapper;

    // ================================================================
    // 5.1 发票登记
    // ================================================================

    @Override
    @Transactional
    public FinApInvoice createInvoice(Map<String, Object> payload) {
        requireRole("登记发票", "ROLE_ADMIN", "ROLE_PM");
        String invoiceNo = str(payload.get("invoiceNo"));
        String supplierId = str(payload.get("supplierId"));
        String dateStr = str(payload.get("invoiceDate"));
        if (!hasText(invoiceNo)) {
            throw new ServiceException(422, "发票号必填");
        }
        if (!hasText(supplierId)) {
            throw new ServiceException(422, "供应商必填");
        }
        if (!hasText(dateStr)) {
            throw new ServiceException(422, "发票日期必填");
        }
        Long dup = invoiceDao.selectCount(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getSupplierId, supplierId)
                .eq(FinApInvoice::getInvoiceNo, invoiceNo));
        if (dup != null && dup > 0) {
            throw new ServiceException(422, "该供应商已存在发票号：" + invoiceNo);
        }

        FinApInvoice inv = new FinApInvoice();
        inv.setInvoiceNo(invoiceNo);
        inv.setSupplierId(supplierId);
        inv.setSupplierName(str(payload.get("supplierName")));
        try {
            inv.setInvoiceDate(LocalDate.parse(dateStr));
        } catch (RuntimeException e) {
            throw new ServiceException(422, "发票日期格式须为 yyyy-MM-dd");
        }
        inv.setCurrency(hasText(str(payload.get("currency"))) ? str(payload.get("currency")) : "CNY");
        inv.setPoNo(str(payload.get("poNo")));
        inv.setRemark(str(payload.get("remark")));
        inv.setStatus(FinApInvoice.ST_DRAFT);
        inv.setCreateBy(SecurityUtils.getCurrentUserId());

        // 寄售 PO 发票（MODIFIED three-way-match 发票登记）：必关联结算单（随后校验 CONFIRMED）
        String settleId = str(payload.get("settleId"));
        if (isConsignPo(inv.getPoNo()) && !hasText(settleId)) {
            throw new ServiceException(422, "寄售 PO 发票须关联 VMI 结算单（settleId），"
                    + "且结算单须已确认");
        }
        if (hasText(settleId)) {
            inv.setSettleId(settleId.trim());
        }

        List<Map<String, Object>> rawLines = mapList(payload.get("lines"));
        BigDecimal total = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<FinApInvoiceLine> lines = new ArrayList<>();
        int lineNo = 1;
        for (Map<String, Object> raw : rawLines) {
            FinApInvoiceLine l = new FinApInvoiceLine();
            l.setLineNo(lineNo++);
            l.setPoId(str(raw.get("poId")));
            l.setPoNo(str(raw.get("poNo")));
            l.setPoLineId(str(raw.get("poLineId")));
            l.setItemCode(str(raw.get("itemCode")));
            l.setItemName(str(raw.get("itemName")));
            l.setUnit(str(raw.get("unit")));
            l.setQty(dec(raw.get("qty")));
            l.setUnitPrice(dec(raw.get("unitPrice")));
            if (l.getQty() == null || l.getQty().signum() <= 0) {
                throw new ServiceException(422, "发票行数量必须大于 0（第 " + l.getLineNo() + " 行）");
            }
            if (l.getUnitPrice() != null && l.getUnitPrice().signum() > 0) {
                l.setAmount(l.getQty().multiply(l.getUnitPrice()).setScale(2, RoundingMode.HALF_UP));
            } else {
                l.setAmount(dec(raw.get("amount")));
                if (l.getAmount() == null) {
                    throw new ServiceException(422, "发票行缺单价与金额（第 " + l.getLineNo() + " 行）");
                }
            }
            total = total.add(l.getAmount());
            // 有关联 PO 的发票，行必须挂 PO 行（三单比对的锚点）
            if (hasText(inv.getPoNo()) && !hasText(l.getPoLineId())) {
                throw new ServiceException(422, "发票行必须关联 PO 行（第 " + l.getLineNo() + " 行）");
            }
            if (!hasText(inv.getPoNo()) && hasText(l.getPoNo())) {
                inv.setPoNo(l.getPoNo());
            }
            lines.add(l);
        }
        // 金额：传入总额与行合计不一致以行合计为准（后端为准，防篡改）
        BigDecimal declared = dec(payload.get("totalAmount"));
        if (declared != null && declared.signum() > 0 && declared.compareTo(total) != 0) {
            log.info("发票 {} 传入总额 {} 与行合计 {} 不一致，以行合计为准", invoiceNo, declared, total);
        }
        if (total.signum() <= 0) {
            if (declared != null && declared.signum() > 0) {
                total = declared;
            } else {
                throw new ServiceException(422, "发票金额必须大于 0");
            }
        }
        inv.setTotalAmount(total);
        invoiceDao.insert(inv);
        for (FinApInvoiceLine l : lines) {
            l.setInvoiceId(inv.getId());
            l.setCreateBy(SecurityUtils.getCurrentUserId());
            invoiceLineDao.insert(l);
        }
        if (hasText(inv.getSettleId())) {
            // 校验 CONFIRMED + 供应商一致并回写 INVOICED（MODIFIED three-way-match；同事务）
            settlementService.markInvoiced(inv.getSettleId(), inv.getSupplierId());
        }
        log.info("发票 {} 登记 supplier={} amount={} poNo={}", invoiceNo, supplierId, total, inv.getPoNo());
        return inv;
    }

    // ================================================================
    // 5.2 / 5.3 匹配执行
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> match(String invoiceId) {
        requireRole("执行匹配", "ROLE_ADMIN", "ROLE_PM");
        FinApInvoice inv = require(invoiceId);
        if (FinApInvoice.ST_POSTED.equals(inv.getStatus())) {
            throw new ServiceException(422, "发票已过账，不可重复匹配");
        }
        if (FinApInvoice.ST_MATCHED.equals(inv.getStatus())) {
            throw new ServiceException(422, "发票已匹配，不可重复执行");
        }
        if (FinApInvoice.ST_EXCEPTION.equals(inv.getStatus())) {
            throw new ServiceException(422, "存在异常对账单，请走确认流程（不可重复匹配）");
        }
        if (!hasText(inv.getPoNo())) {
            throw new ServiceException(422, "发票未关联 PO，不进入自动匹配");
        }
        List<FinApInvoiceLine> invLines = invoiceLineDao.selectList(
                new LambdaQueryWrapper<FinApInvoiceLine>()
                        .eq(FinApInvoiceLine::getInvoiceId, inv.getId())
                        .orderByAsc(FinApInvoiceLine::getLineNo));
        if (invLines.isEmpty()) {
            throw new ServiceException(422, "发票无明细行，无法三方匹配");
        }

        // 该 PO 的已过账收货行（普通 PO 无收货不可匹配；寄售 PO 以结算单为量额腿，
        // MODIFIED three-way-match 三单匹配 VMI 分支）
        boolean consign = isConsignPo(inv.getPoNo());
        Map<String, BigDecimal> settleQtyByItem = new HashMap<>();
        BigDecimal settleAmt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<FinAccrual> vmiAccruals = null;
        if (consign) {
            if (!hasText(inv.getSettleId())) {
                throw new ServiceException(422, "寄售 PO 发票未关联 VMI 结算单，无法匹配");
            }
            com.erp.entity.vmi.VmiSettlement settle = settleDao.selectById(inv.getSettleId());
            if (settle == null) {
                throw new ServiceException(422, "关联的 VMI 结算单不存在");
            }
            if (!com.erp.entity.vmi.VmiSettlement.ST_CONFIRMED.equals(settle.getStatus())
                    && !com.erp.entity.vmi.VmiSettlement.ST_INVOICED.equals(settle.getStatus())) {
                throw new ServiceException(422, "VMI 结算单状态须为 CONFIRMED/INVOICED 方可匹配，当前 "
                        + settle.getStatus());
            }
            if (!java.util.Objects.equals(settle.getSupplierId(), inv.getSupplierId())) {
                throw new ServiceException(422, "结算单供应商与发票供应商不一致");
            }
            List<com.erp.entity.vmi.VmiSettlementLine> settleLines = settleLineDao.selectList(
                    new LambdaQueryWrapper<com.erp.entity.vmi.VmiSettlementLine>()
                            .eq(com.erp.entity.vmi.VmiSettlementLine::getSettleId, settle.getId()));
            if (settleLines.isEmpty()) {
                throw new ServiceException(422, "VMI 结算单无领用明细行，无法匹配");
            }
            for (com.erp.entity.vmi.VmiSettlementLine sl : settleLines) {
                settleQtyByItem.merge(sl.getItemCode(), nvl(sl.getQty()), BigDecimal::add);
                settleAmt = settleAmt.add(nvl(sl.getAmount()));
            }
            // VMI 暂估不挂 PO（PO_NO=null），按结算单覆盖的领用单《寄售转自有凭证》反查（design D8）
            List<String> issueIds = settleLines.stream()
                    .map(com.erp.entity.vmi.VmiSettlementLine::getIssueId)
                    .filter(ThreeWayMatchServiceImpl::hasText).distinct().toList();
            List<String> transferDocs = new ArrayList<>();
            for (com.erp.entity.vmi.MaterialIssue mi : issueDao.selectBatchIds(issueIds)) {
                if (hasText(mi.getTransferDocNo())) {
                    transferDocs.add(mi.getTransferDocNo());
                }
            }
            vmiAccruals = transferDocs.isEmpty() ? new ArrayList<>()
                    : accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                            .in(FinAccrual::getPostingDocNo, transferDocs)
                            .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN));
        }
        Map<String, BigDecimal> postedQty = new HashMap<>();
        BigDecimal postedAmt = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (consign) {
            // 量额腿：发票行按物料对结算单领用量；金额腿 = 结算金额
            for (FinApInvoiceLine l : invLines) {
                BigDecimal q = settleQtyByItem.get(l.getItemCode());
                if (hasText(l.getPoLineId())) {
                    postedQty.put(l.getPoLineId(), q == null ? BigDecimal.ZERO : q);
                }
            }
            postedAmt = settleAmt;
        } else {
        List<GoodsReceipt> grs = grDao.selectList(new LambdaQueryWrapper<GoodsReceipt>()
                .eq(GoodsReceipt::getPoNo, inv.getPoNo())
                .eq(GoodsReceipt::getStatus, "POSTED"));
        if (grs.isEmpty()) {
            throw new ServiceException(422, "该 PO 尚无入库过账，无法三方匹配");
        }
        List<String> grIds = grs.stream().map(GoodsReceipt::getId).toList();
        List<GoodsReceiptLine> posted = grLineDao.selectList(new LambdaQueryWrapper<GoodsReceiptLine>()
                .in(GoodsReceiptLine::getGrId, grIds)
                .eq(GoodsReceiptLine::getStatus, "POSTED"));
        if (posted.isEmpty()) {
            throw new ServiceException(422, "该 PO 尚无已过账收货行，无法三方匹配");
        }
        for (GoodsReceiptLine gl : posted) {
            if (hasText(gl.getPoLineId())) {
                postedQty.merge(gl.getPoLineId(), nvl(gl.getWithinToleranceQty()),
                        BigDecimal::add);
            }
            postedAmt = postedAmt.add(nvl(gl.getAmount()));
        }
        }

        // 暂估基数：优先 OPEN 暂估合计，无暂估回退已过账金额（design D10）；
        // 寄售 PO 按结算单覆盖的转自有凭证反查（VMI 暂估 PO_NO 为空，design D8）
        List<FinAccrual> accruals = vmiAccruals != null ? vmiAccruals
                : accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                        .eq(FinAccrual::getPoNo, inv.getPoNo())
                        .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN));
        BigDecimal openAccrual = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (FinAccrual a : accruals) {
            openAccrual = openAccrual.add(nvl(a.getAmount()));
        }
        BigDecimal base = openAccrual.signum() > 0 ? openAccrual : postedAmt;

        // PO 行
        List<String> poLineIds = invLines.stream()
                .map(FinApInvoiceLine::getPoLineId)
                .filter(ThreeWayMatchServiceImpl::hasText).distinct().toList();
        Map<String, PurchaseOrderLine> poLineMap = new HashMap<>();
        if (!poLineIds.isEmpty()) {
            for (PurchaseOrderLine pl : poLineDao.selectBatchIds(poLineIds)) {
                poLineMap.put(pl.getId(), pl);
            }
        }

        // 逐行差异率（design D7）
        List<Map<String, Object>> detail = new ArrayList<>();
        BigDecimal maxQtyRate = BigDecimal.ZERO;
        BigDecimal maxPriceRate = BigDecimal.ZERO;
        for (FinApInvoiceLine l : invLines) {
            PurchaseOrderLine pl = poLineMap.get(l.getPoLineId());
            if (pl == null) {
                throw new ServiceException(422, "发票行关联的 PO 行不存在（第 " + l.getLineNo() + " 行）");
            }
            BigDecimal pq = postedQty.getOrDefault(l.getPoLineId(), BigDecimal.ZERO);
            BigDecimal qtyRate;
            if (pq.signum() > 0) {
                qtyRate = nvl(l.getQty()).subtract(pq).abs()
                        .divide(pq, 6, RoundingMode.HALF_UP);
            } else {
                qtyRate = nvl(l.getQty()).signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO;
            }
            BigDecimal poPrice = nvl(pl.getUnitPrice());
            BigDecimal priceRate = poPrice.signum() > 0 && l.getUnitPrice() != null
                    ? l.getUnitPrice().subtract(poPrice).abs()
                    .divide(poPrice, 6, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            maxQtyRate = maxQtyRate.max(qtyRate);
            maxPriceRate = maxPriceRate.max(priceRate);

            Map<String, Object> d = new LinkedHashMap<>();
            d.put("lineNo", l.getLineNo());
            d.put("poLineId", l.getPoLineId());
            d.put("itemCode", l.getItemCode());
            d.put("itemName", l.getItemName());
            d.put("invoiceQty", l.getQty());
            d.put("postedQty", pq);
            d.put("qtyRate", qtyRate);
            d.put("invoiceUnitPrice", l.getUnitPrice());
            d.put("poUnitPrice", pl.getUnitPrice());
            d.put("priceRate", priceRate);
            d.put("lineAmount", l.getAmount());
            d.put("qtyWithinTolerance", qtyRate.compareTo(matchTolerance) <= 0);
            d.put("priceWithinTolerance", priceRate.compareTo(matchTolerance) <= 0);
            detail.add(d);
        }
        BigDecimal amountDiff = inv.getTotalAmount().subtract(base);
        BigDecimal amountRate = base.signum() > 0
                ? amountDiff.abs().divide(base, 6, RoundingMode.HALF_UP)
                : (inv.getTotalAmount().signum() > 0 ? BigDecimal.ONE : BigDecimal.ZERO);
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("invoiceAmount", inv.getTotalAmount());
        head.put("accrualBase", base);
        head.put("openAccrual", openAccrual);
        head.put("postedAmount", postedAmt);
        head.put("amountDiff", amountDiff);
        head.put("amountRate", amountRate);
        head.put("tolerance", matchTolerance);
        detail.add(0, head);

        String exType = null;
        if (maxQtyRate.compareTo(matchTolerance) > 0) {
            exType = FinMatchResult.EX_QTY;
        } else if (maxPriceRate.compareTo(matchTolerance) > 0
                || amountRate.compareTo(matchTolerance) > 0) {
            exType = FinMatchResult.EX_PRICE;
        }

        FinMatchResult mr = newResult(inv);
        mr.setDetail(toJson(detail));
        mr.setDiffQtyRate(maxQtyRate);
        mr.setDiffPriceRate(maxPriceRate);
        mr.setDiffAmount(amountDiff);
        mr.setDiffAmountRate(amountRate);
        mr.setPeriodCross(isPeriodCross(inv.getInvoiceDate()) ? 1 : 0);
        mr.setCreateBy(SecurityUtils.getCurrentUserId());

        if (exType == null) {
            // MATCHED：自动冲回暂估 + 正式应付凭证（单事务）
            BigDecimal aSum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
            for (FinAccrual a : accruals) {
                if (a.getMigrationConfirmed() != null && a.getMigrationConfirmed() == 0) {
                    continue;   // 挂起批次不参与（BR-4.1-29）
                }
                accrualService.reverse(a.getAccrualNo(), inv.getInvoiceNo(),
                        "三方匹配通过自动冲回（" + inv.getInvoiceNo() + "）", FinAccrual.REV_AUTO);
                aSum = aSum.add(nvl(a.getAmount()).subtract(nvl(a.getOffsettedAmount())));
            }
            FinVoucher v = postApVoucher(inv, aSum, false, null);
            mr.setStatus(FinMatchResult.ST_MATCHED);
            mr.setVoucherId(v.getId());
            mr.setAccrualBatchReversed(1);
            matchDao.insert(mr);
            inv.setStatus(FinApInvoice.ST_POSTED);
            inv.setUpdateBy(SecurityUtils.getCurrentUserId());
            invoiceDao.updateById(inv);
            log.info("发票 {} 匹配通过：暂估冲回 {} 凭证 {}", inv.getInvoiceNo(), aSum, v.getVoucherNo());
            triggerPrepaySettle(inv.getPoNo());
        } else {
            mr.setStatus(FinMatchResult.ST_EXCEPTION);
            mr.setExceptionType(exType);
            matchDao.insert(mr);
            inv.setStatus(FinApInvoice.ST_EXCEPTION);
            inv.setUpdateBy(SecurityUtils.getCurrentUserId());
            invoiceDao.updateById(inv);
            log.info("发票 {} 匹配异常 {}（qtyRate={} priceRate={} amountRate={}），冻结自动核销（BR-4.2-31）",
                    inv.getInvoiceNo(), exType, maxQtyRate, maxPriceRate, amountRate);
        }
        return matchDetail(mr.getId());
    }

    // ================================================================
    // 5.4 / 5.5 确认与手工过账
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> confirm(String matchId, String opinion) {
        requireRole("确认价差", "ROLE_ADMIN", "ROLE_PM");
        FinMatchResult mr = requireMatch(matchId);
        if (!FinMatchResult.ST_EXCEPTION.equals(mr.getStatus())) {
            throw new ServiceException(422, "仅异常对账单可确认，当前状态 " + mr.getStatus());
        }
        if (!hasText(opinion)) {
            throw new ServiceException(422, "必须填写价差原因/确认意见");
        }
        FinApInvoice inv = require(mr.getInvoiceId());
        mr.setConfirmOpinion(opinion);
        mr.setConfirmBy(SecurityUtils.getCurrentUserId());
        mr.setConfirmAt(LocalDateTime.now());
        if (mr.getPeriodCross() != null && mr.getPeriodCross() == 1) {
            // 跨期：确认后不自动过账，待 ADMIN 手工过账（00-erp-spec.md:1328）
            mr.setStatus(FinMatchResult.ST_CONFIRMED);
            matchDao.updateById(mr);
            log.info("异常单 {} 跨期确认，待手工过账：{}", mr.getMatchNo(), opinion);
            return matchDetail(mr.getId());
        }
        doPost(mr, inv, false, opinion);
        return matchDetail(mr.getId());
    }

    @Override
    @Transactional
    public Map<String, Object> manualPost(String matchId, String note) {
        requireRole("手工过账", "ROLE_ADMIN");
        FinMatchResult mr = requireMatch(matchId);
        if (mr.getPeriodCross() == null || mr.getPeriodCross() != 1) {
            throw new ServiceException(422, "非跨期异常确认后已自动过账，无需手工过账");
        }
        if (!FinMatchResult.ST_CONFIRMED.equals(mr.getStatus())) {
            throw new ServiceException(422, "须先由采购员确认价差原因（当前状态 " + mr.getStatus() + "）");
        }
        FinApInvoice inv = require(mr.getInvoiceId());
        doPost(mr, inv, true, hasText(note) ? note : mr.getConfirmOpinion());
        return matchDetail(mr.getId());
    }

    /** 过账：冲回暂估 + 正式应付/价差调整凭证 + 发票与匹配单置 POSTED */
    private void doPost(FinMatchResult mr, FinApInvoice inv, boolean crossPeriod, String note) {
        BigDecimal aSum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        if (hasText(inv.getPoNo())) {
            List<FinAccrual> accruals = accrualDao.selectList(new LambdaQueryWrapper<FinAccrual>()
                    .eq(FinAccrual::getPoNo, inv.getPoNo())
                    .eq(FinAccrual::getStatus, FinAccrual.ST_OPEN)
                    .and(w -> w.isNull(FinAccrual::getMigrationConfirmed)
                            .or().eq(FinAccrual::getMigrationConfirmed, 1))
                    .orderByDesc(FinAccrual::getCreateDate));
            for (FinAccrual a : accruals) {
                accrualService.reverse(a.getAccrualNo(), inv.getInvoiceNo(),
                        (crossPeriod ? "跨期价差确认冲回：" : "匹配确认冲回：") + mr.getMatchNo(),
                        FinAccrual.REV_AUTO);
                aSum = aSum.add(nvl(a.getAmount()).subtract(nvl(a.getOffsettedAmount())));
            }
        }
        FinVoucher v = postApVoucher(inv, aSum, crossPeriod, note);
        mr.setStatus(FinMatchResult.ST_POSTED);
        mr.setVoucherId(v.getId());
        mr.setAccrualBatchReversed(1);
        mr.setPostBy(SecurityUtils.getCurrentUserId());
        mr.setPostAt(LocalDateTime.now());
        matchDao.updateById(mr);
        inv.setStatus(FinApInvoice.ST_POSTED);
        inv.setUpdateBy(SecurityUtils.getCurrentUserId());
        invoiceDao.updateById(inv);
        log.info("异常单 {} {}过账 凭证 {}（暂估冲回 {}）", mr.getMatchNo(),
                crossPeriod ? "跨期手工" : "确认", v.getVoucherNo(), aSum);
        triggerPrepaySettle(inv.getPoNo());
    }

    /**
     * 预付冲抵触发（spec prepayment：发票 POSTED 后自动冲抵，design D7）。
     * 事务提交后 best-effort 执行：失败仅告警不回滚过账，可由预付款页手动兜底重试。
     */
    private void triggerPrepaySettle(String poNo) {
        if (!hasText(poNo)) {
            return;
        }
        Runnable task = () -> {
            try {
                int n = prepaymentService.settleForPo(poNo);
                if (n > 0) {
                    log.info("PO {} 发票过账后自动冲抵预付 {} 笔（BR-4.2-52）", poNo, n);
                }
            } catch (RuntimeException e) {
                log.warn("PO {} 预付冲抵失败（不回滚过账，可手动兜底）: {}", poNo, e.getMessage());
            }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            task.run();
                        }
                    });
        } else {
            task.run();
        }
    }

    /**
     * 正式应付凭证（design D7/D9 分录模板）：
     * 借 应付暂估 A + 采购价差 (I−A)（可负→贷方红字） / 贷 应付账款 I
     */
    private FinVoucher postApVoucher(FinApInvoice inv, BigDecimal aSum, boolean crossPeriod, String note) {
        BigDecimal i = nvl(inv.getTotalAmount()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal a = aSum == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : aSum;
        BigDecimal diff = i.subtract(a);
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        if (a.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of(AccrualServiceImpl.ACCT_ACCRUAL, "DR",
                    a, "借 应付暂估（冲回 " + inv.getInvoiceNo() + "）"));
        }
        if (diff.signum() > 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of("1406", "DR", diff,
                    "采购价差（发票 − 暂估）"));
        } else if (diff.signum() < 0) {
            lines.add(GlVoucherService.FinVoucherLineSpec.of("1406", "CR", diff.abs(),
                    "采购价差转回（暂估大于发票）"));
        }
        lines.add(GlVoucherService.FinVoucherLineSpec.of(AccrualServiceImpl.ACCT_AP, "CR", i,
                "贷 应付账款（" + inv.getInvoiceNo() + "）"));
        String summary = (crossPeriod
                ? "跨期价差调整：" + (hasText(note) ? note : "")
                : "三方匹配转应付：") + inv.getInvoiceNo();
        return voucherService.create(
                crossPeriod ? FinVoucher.TYPE_PRICE_ADJ : FinVoucher.TYPE_AP,
                LocalDate.now(), summary, "MATCH", inv.getInvoiceNo(), inv.getSupplierId(), lines);
    }

    // ================================================================
    // 查询与重跑
    // ================================================================

    @Override
    public Page<Map<String, Object>> invoicePage(long current, long size, String keyword, String status,
                                                 String supplierId, String dateFrom, String dateTo) {
        LambdaQueryWrapper<FinApInvoice> qw = new LambdaQueryWrapper<FinApInvoice>()
                .like(hasText(keyword), FinApInvoice::getInvoiceNo, keyword)
                .eq(hasText(status), FinApInvoice::getStatus, status)
                .eq(hasText(supplierId), FinApInvoice::getSupplierId, supplierId)
                .ge(hasText(dateFrom), FinApInvoice::getInvoiceDate, dateFrom)
                .le(hasText(dateTo), FinApInvoice::getInvoiceDate, dateTo)
                .orderByDesc(FinApInvoice::getCreateDate);
        Page<FinApInvoice> raw = invoiceDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinApInvoice inv : raw.getRecords()) {
            Map<String, Object> m = invRow(inv);
            FinMatchResult mr = latestMatch(inv.getId());
            if (mr != null) {
                m.put("matchNo", mr.getMatchNo());
                m.put("matchStatus", mr.getStatus());
                m.put("diffAmountRate", mr.getDiffAmountRate());
                m.put("periodCross", mr.getPeriodCross());
                m.put("exceptionType", mr.getExceptionType());
            }
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> invoiceDetail(String id) {
        FinApInvoice inv = require(id);
        Map<String, Object> m = invRow(inv);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (FinApInvoiceLine l : invoiceLineDao.selectList(new LambdaQueryWrapper<FinApInvoiceLine>()
                .eq(FinApInvoiceLine::getInvoiceId, inv.getId())
                .orderByAsc(FinApInvoiceLine::getLineNo))) {
            Map<String, Object> lm = new LinkedHashMap<>();
            lm.put("lineNo", l.getLineNo());
            lm.put("poNo", l.getPoNo());
            lm.put("poLineId", l.getPoLineId());
            lm.put("itemCode", l.getItemCode());
            lm.put("itemName", l.getItemName());
            lm.put("unit", l.getUnit());
            lm.put("qty", l.getQty());
            lm.put("unitPrice", l.getUnitPrice());
            lm.put("amount", l.getAmount());
            lines.add(lm);
        }
        m.put("lines", lines);
        if (hasText(inv.getSettleId())) {
            // 寄售发票展开结算单与领用明细（批次/数量/领用时点协议价，7.3 对账视图）
            m.put("settlement", settleDao.selectById(inv.getSettleId()));
            m.put("settleLines", settleLineDao.selectList(
                    new LambdaQueryWrapper<com.erp.entity.vmi.VmiSettlementLine>()
                            .eq(com.erp.entity.vmi.VmiSettlementLine::getSettleId, inv.getSettleId())
                            .orderByAsc(com.erp.entity.vmi.VmiSettlementLine::getLineNo)));
        }
        FinMatchResult mr = latestMatch(inv.getId());
        m.put("match", mr == null ? null : matchDetail(mr.getId()));
        return m;
    }

    @Override
    public Page<Map<String, Object>> matchPage(long current, long size, String status,
                                               String supplierId, String invoiceNo, String poNo) {
        LambdaQueryWrapper<FinMatchResult> qw = new LambdaQueryWrapper<FinMatchResult>()
                .eq(hasText(status), FinMatchResult::getStatus, status)
                .eq(hasText(supplierId), FinMatchResult::getSupplierId, supplierId)
                .like(hasText(invoiceNo), FinMatchResult::getInvoiceNo, invoiceNo)
                .like(hasText(poNo), FinMatchResult::getPoNo, poNo)
                .orderByDesc(FinMatchResult::getCreateDate);
        Page<FinMatchResult> raw = matchDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinMatchResult mr : raw.getRecords()) {
            rows.add(matchRow(mr));
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> matchDetail(String matchId) {
        FinMatchResult mr = requireMatch(matchId);
        Map<String, Object> m = matchRow(mr);
        m.put("detailJson", mr.getDetail());
        m.put("detail", parseJson(mr.getDetail()));
        if (hasText(mr.getVoucherId())) {
            m.put("voucher", voucherService.detail(mr.getVoucherId()));
        }
        FinApInvoice inv = invoiceDao.selectById(mr.getInvoiceId());
        if (inv != null) {
            m.put("invoiceDate", inv.getInvoiceDate());
            m.put("invoiceStatus", inv.getStatus());
        }
        return m;
    }

    @Override
    public int rerunBySupplier(String supplierId) {
        if (!hasText(supplierId)) {
            return 0;
        }
        List<FinApInvoice> list = invoiceDao.selectList(new LambdaQueryWrapper<FinApInvoice>()
                .eq(FinApInvoice::getSupplierId, supplierId)
                .eq(FinApInvoice::getStatus, FinApInvoice.ST_DRAFT)
                .isNotNull(FinApInvoice::getPoNo));
        int n = 0;
        for (FinApInvoice inv : list) {
            try {
                match(inv.getId());
                n++;
            } catch (ServiceException e) {
                log.info("供应商 {} 发票 {} 重跑匹配跳过：{}", supplierId, inv.getInvoiceNo(), e.getMessage());
            }
        }
        if (n > 0) {
            log.info("供应商 {} 重跑三方匹配完成 {} 张", supplierId, n);
        }
        return n;
    }

    // ------------------------------------------------------------------

    /** 是否寄售 PO（CONSIGN，MODIFIED three-way-match 分流判据） */
    private boolean isConsignPo(String poNo) {
        if (!hasText(poNo)) {
            return false;
        }
        com.erp.entity.proc.PurchaseOrder po = poDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.proc.PurchaseOrder>()
                        .eq(com.erp.entity.proc.PurchaseOrder::getPoNo, poNo)
                        .last("LIMIT 1"));
        return po != null && "CONSIGN".equals(po.getPoType());
    }

    private FinMatchResult newResult(FinApInvoice inv) {
        FinMatchResult mr = new FinMatchResult();
        mr.setMatchNo(nextMatchNo());
        mr.setInvoiceId(inv.getId());
        mr.setInvoiceNo(inv.getInvoiceNo());
        mr.setPoNo(inv.getPoNo());
        mr.setSupplierId(inv.getSupplierId());
        mr.setSupplierName(inv.getSupplierName());
        return mr;
    }

    private Map<String, Object> matchRow(FinMatchResult mr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", mr.getId());
        m.put("matchNo", mr.getMatchNo());
        m.put("invoiceId", mr.getInvoiceId());
        m.put("invoiceNo", mr.getInvoiceNo());
        m.put("poNo", mr.getPoNo());
        m.put("supplierId", mr.getSupplierId());
        m.put("supplierName", mr.getSupplierName());
        m.put("status", mr.getStatus());
        m.put("diffQtyRate", mr.getDiffQtyRate());
        m.put("diffPriceRate", mr.getDiffPriceRate());
        m.put("diffAmount", mr.getDiffAmount());
        m.put("diffAmountRate", mr.getDiffAmountRate());
        m.put("exceptionType", mr.getExceptionType());
        m.put("periodCross", mr.getPeriodCross());
        m.put("confirmOpinion", mr.getConfirmOpinion());
        m.put("confirmBy", mr.getConfirmBy());
        m.put("confirmAt", mr.getConfirmAt());
        m.put("postBy", mr.getPostBy());
        m.put("postAt", mr.getPostAt());
        m.put("voucherId", mr.getVoucherId());
        m.put("createDate", mr.getCreateDate());
        return m;
    }

    private Map<String, Object> invRow(FinApInvoice inv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", inv.getId());
        m.put("invoiceNo", inv.getInvoiceNo());
        m.put("supplierId", inv.getSupplierId());
        m.put("supplierName", inv.getSupplierName());
        m.put("invoiceDate", inv.getInvoiceDate());
        m.put("currency", inv.getCurrency());
        m.put("totalAmount", inv.getTotalAmount());
        m.put("poNo", inv.getPoNo());
        m.put("status", inv.getStatus());
        m.put("remark", inv.getRemark());
        m.put("createDate", inv.getCreateDate());
        return m;
    }

    private FinMatchResult latestMatch(String invoiceId) {
        return matchDao.selectOne(new LambdaQueryWrapper<FinMatchResult>()
                .eq(FinMatchResult::getInvoiceId, invoiceId)
                .orderByDesc(FinMatchResult::getCreateDate)
                .last("LIMIT 1"));
    }

    private FinApInvoice require(String id) {
        FinApInvoice inv = invoiceDao.selectById(id);
        if (inv == null) {
            throw new ServiceException(404, "发票不存在");
        }
        return inv;
    }

    private FinMatchResult requireMatch(String id) {
        FinMatchResult mr = matchDao.selectById(id);
        if (mr == null) {
            throw new ServiceException(404, "匹配单不存在");
        }
        return mr;
    }

    /** 跨期判定：发票日期所属自然月早于当前自然月（8.1 未建，design D8 近似口径） */
    private boolean isPeriodCross(LocalDate invoiceDate) {
        if (invoiceDate == null) {
            return false;
        }
        String invoicePeriod = invoiceDate.format(MONTH_FMT);
        return invoicePeriod.compareTo(LocalDate.now().format(MONTH_FMT)) < 0;
    }

    /** 匹配单号：MT + yyyyMMdd + 3 位流水 */
    private String nextMatchNo() {
        String prefix = "MT" + LocalDate.now().format(DAY_FMT);
        Integer max = matchDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private String toJson(Object o) {
        try {
            return jsonMapper.writeValueAsString(o);
        } catch (Exception e) {
            log.warn("差异明细序列化失败：{}", e.getMessage());
            return null;
        }
    }

    private Object parseJson(String json) {
        if (!hasText(json)) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(r -> userRoles.add(r.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || "".equals(String.valueOf(o))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "数值格式错误：" + o);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object o) {
        if (o instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    out.add((Map<String, Object>) m);
                }
            }
            return out;
        }
        return new ArrayList<>();
    }
}
