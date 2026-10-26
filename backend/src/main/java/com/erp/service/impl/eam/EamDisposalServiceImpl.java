package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamAssetCategoryDao;
import com.erp.dao.eam.EamDisposalLineDao;
import com.erp.dao.eam.EamDisposalOrderDao;
import com.erp.dao.eam.EamDisposalRecycleDao;
import com.erp.dao.eam.EamTransferOrderDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.fin.FinAccountDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamAssetCategory;
import com.erp.entity.eam.EamDisposalLine;
import com.erp.entity.eam.EamDisposalOrder;
import com.erp.entity.eam.EamDisposalRecycle;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.entity.fin.FinAccount;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.eam.EamDisposalService;
import com.erp.service.eam.EamSafetyLockService;
import com.erp.service.fin.GlVoucherService;
import com.erp.service.proc.GoodsReceiptService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资产处置服务实现（add-asset-disposal，design D2 两段式凭证 / D3 分级 / D4 回收入库 / D6 前置断言）。
 */
@Slf4j
@Service
public class EamDisposalServiceImpl implements EamDisposalService {

    private static final String ACCT_FA = "1601";
    private static final String ACCT_ACCUM = "1602";
    private static final String ACCT_CLEARING = "1606";
    private static final String ACCT_AR = "1122";
    private static final String ACCT_BANK = "1002";
    private static final String ACCT_MATERIAL = "1403";
    private static final String ACCT_GAINLOSS = "6115";
    private static final String RECYCLE_SUPPLIER = "sup-eam-recycle";

    private final EamDisposalOrderDao orderDao;
    private final EamDisposalLineDao lineDao;
    private final EamDisposalRecycleDao recycleDao;
    private final EamAssetCardDao cardDao;
    private final EamAssetCategoryDao categoryDao;
    private final EamWorkOrderDao workOrderDao;
    private final EamTransferOrderDao transferOrderDao;
    private final FinAccountDao accountDao;
    private final com.erp.dao.system.ApprovalTaskDao approvalTaskDao;
    private final com.erp.dao.proc.GoodsReceiptLineDao grLineDao;
    private final com.erp.service.inv.BinAssignmentService binAssignmentService;
    private final GlVoucherService voucherService;
    private final GoodsReceiptService goodsReceiptService;
    private final ApprovalEngine approvalEngine;
    private final SysParamService sysParamService;
    private final EamSafetyLockService safetyLockService;

    public EamDisposalServiceImpl(EamDisposalOrderDao orderDao,
                                  EamDisposalLineDao lineDao,
                                  EamDisposalRecycleDao recycleDao,
                                  EamAssetCardDao cardDao,
                                  EamAssetCategoryDao categoryDao,
                                  EamWorkOrderDao workOrderDao,
                                  EamTransferOrderDao transferOrderDao,
                                  FinAccountDao accountDao,
                                  com.erp.dao.system.ApprovalTaskDao approvalTaskDao,
                                  com.erp.dao.proc.GoodsReceiptLineDao grLineDao,
                                  com.erp.service.inv.BinAssignmentService binAssignmentService,
                                  GlVoucherService voucherService,
                                  GoodsReceiptService goodsReceiptService,
                                  ApprovalEngine approvalEngine,
                                  SysParamService sysParamService,
                                  EamSafetyLockService safetyLockService) {
        this.orderDao = orderDao;
        this.lineDao = lineDao;
        this.recycleDao = recycleDao;
        this.cardDao = cardDao;
        this.categoryDao = categoryDao;
        this.workOrderDao = workOrderDao;
        this.transferOrderDao = transferOrderDao;
        this.accountDao = accountDao;
        this.approvalTaskDao = approvalTaskDao;
        this.grLineDao = grLineDao;
        this.binAssignmentService = binAssignmentService;
        this.voucherService = voucherService;
        this.goodsReceiptService = goodsReceiptService;
        this.approvalEngine = approvalEngine;
        this.sysParamService = sysParamService;
        this.safetyLockService = safetyLockService;
    }

    // ================= 创建 / 编辑 =================

    @Override
    @Transactional
    public Map<String, Object> create(EamDisposalOrder order, List<EamDisposalLine> lines,
                                      List<EamDisposalRecycle> recycles) {
        String type = norm(order.getDisposalType());
        if (!List.of("SCRAP", "SALE").contains(type)) {
            throw new ServiceException(422, "处置方式取值域：SCRAP 报废 / SALE 出售");
        }
        if (!hasText(order.getReasonCode()) && !hasText(order.getReasonNote())) {
            throw new ServiceException(422, "处置原因必填");
        }
        List<EamDisposalLine> reqLines = lines == null ? List.of() : lines;
        if (reqLines.isEmpty()) {
            throw new ServiceException(422, "至少选择一张处置卡片");
        }
        List<EamAssetCard> cards = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (EamDisposalLine l : reqLines) {
            if (!seen.add(l.getCardId())) {
                throw new ServiceException(422, "同卡不可重复加入处置单：" + l.getCardId());
            }
            EamAssetCard card = cardDao.selectById(l.getCardId());
            if (card == null || !"0".equals(card.getDelFlag())) {
                blockers.add("卡片不存在：" + l.getCardId());
                continue;
            }
            // 同卡进行中处置单（重复发起阻断）
            EamDisposalOrder inFlight = orderDao.selectInFlightByCard(card.getId());
            if (inFlight != null) {
                blockers.add("卡片 " + card.getCardNo() + " 已有进行中的处置单 " + inFlight.getDspNo());
            }
            // D6 前置断言聚合（BR-21 式逐项列示）
            if (!"IN_USE".equals(card.getStatus())) {
                blockers.add("卡片 " + card.getCardNo() + " 状态非在用（" + card.getStatus() + "），不可处置");
            }
            if ("1".equals(card.getCountFreezeFlag())) {
                blockers.add("卡片 " + card.getCardNo() + " 处于盘点冻结状态（标签异常/账实冲突未闭环，BR-4.13-30），不可处置");
            }
            try {
                safetyLockService.assertNoActiveLock(card.getId());
            } catch (ServiceException ex) {
                blockers.add("卡片 " + card.getCardNo() + "：" + ex.getMessage());
            }
            List<EamWorkOrder> openWos = workOrderDao.selectList(new LambdaQueryWrapper<EamWorkOrder>()
                    .eq(EamWorkOrder::getCardId, card.getId())
                    .notIn(EamWorkOrder::getStatus, "CLOSED", "CANCELLED"));
            for (EamWorkOrder wo : openWos) {
                blockers.add("卡片 " + card.getCardNo() + " 存在未关闭工单：" + wo.getWoNo());
            }
            var openTrf = transferOrderDao.selectInFlightByCard(card.getId(), null);
            if (openTrf != null) {
                blockers.add("卡片 " + card.getCardNo() + " 存在未关闭调拨单：" + openTrf.getTrfNo());
            }
            // 保险理赔前置（FR-4.13-6-1）：保险域未建，桩位恒通过（design D6 偏差记录）
            cards.add(card);
        }
        if (!blockers.isEmpty()) {
            throw new ServiceException(422, "处置前置校验未通过（逐项列示）：" + String.join("；", blockers));
        }

        // 快照行 + 汇总
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalAccum = BigDecimal.ZERO;
        BigDecimal totalNbv = BigDecimal.ZERO;
        BigDecimal totalProceeds = BigDecimal.ZERO;
        List<EamDisposalLine> builtLines = new ArrayList<>();
        BigDecimal tolerance = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.5"));
        for (int i = 0; i < reqLines.size(); i++) {
            EamAssetCard card = cards.get(i);
            BigDecimal cost = nz(card.getAssetCost());
            BigDecimal accum = nz(card.getAccumDepr()).add(nz(card.getImpairment()));
            BigDecimal nbv = cost.subtract(accum).max(BigDecimal.ZERO);
            BigDecimal residualValue = card.getResidualValue() == null
                    ? cost.multiply(nz(card.getResidualRate())).setScale(2, RoundingMode.HALF_UP)
                    : card.getResidualValue();
            boolean fully = "FULLY".equals(card.getDeprStatus())
                    || accum.abs().subtract(cost.subtract(residualValue)).abs().compareTo(tolerance) <= 0;
            EamDisposalLine line = reqLines.get(i);
            EamDisposalLine built = new EamDisposalLine();
            built.setOrderId(null);
            built.setCardId(card.getId());
            built.setCardNo(card.getCardNo());
            built.setAssetName(card.getAssetName());
            built.setCategoryCode(card.getCategoryCode());
            built.setDeptName(card.getDeptName());
            built.setCostCenterCode(card.getCostCenterCode());
            built.setOrigCost(cost);
            built.setAccumDepr(accum);
            built.setNbv(nbv);
            built.setFullyDeprFlag(fully ? "1" : "0");
            built.setLineProceeds(line == null ? null : line.getLineProceeds());
            built.setCreateBy(SecurityUtils.getCurrentUserId());
            builtLines.add(built);
            totalCost = totalCost.add(cost);
            totalAccum = totalAccum.add(accum);
            totalNbv = totalNbv.add(nbv);
            totalProceeds = totalProceeds.add(nz(built.getLineProceeds()));
        }

        List<EamDisposalRecycle> builtRecycles = new ArrayList<>();
        if (recycles != null) {
            for (EamDisposalRecycle r : recycles) {
                if (r == null || !hasText(r.getItemName())) {
                    throw new ServiceException(422, "回收明细品名必填");
                }
                if (!hasText(r.getItemCode())) {
                    throw new ServiceException(422, "回收明细须指定物料编码（入库载体）：" + r.getItemName());
                }
                EamDisposalRecycle rb = new EamDisposalRecycle();
                rb.setItemName(r.getItemName());
                rb.setItemCode(r.getItemCode());
                rb.setQty(r.getQty() == null ? BigDecimal.ONE : r.getQty());
                rb.setEstPrice(r.getEstPrice() == null ? BigDecimal.ZERO : r.getEstPrice());
                rb.setGrStatus("PENDING");
                rb.setCreateBy(SecurityUtils.getCurrentUserId());
                builtRecycles.add(rb);
            }
        }
        BigDecimal totalRecycle = builtRecycles.stream()
                .map(r -> r.getEstPrice().multiply(r.getQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);

        EamDisposalOrder saved = new EamDisposalOrder();
        saved.setDspNo(nextDspNo());
        saved.setDisposalType(type);
        saved.setReasonCode(order.getReasonCode());
        saved.setReasonNote(order.getReasonNote());
        saved.setAppStatus("DRAFT");
        saved.setEstResidual(order.getEstResidual());
        saved.setEvalReportNo(order.getEvalReportNo());
        saved.setReviewNote(order.getReviewNote());
        saved.setTotalCost(totalCost.setScale(2, RoundingMode.HALF_UP));
        saved.setTotalAccum(totalAccum.setScale(2, RoundingMode.HALF_UP));
        saved.setTotalNbv(totalNbv.setScale(2, RoundingMode.HALF_UP));
        saved.setTotalProceeds(totalProceeds.setScale(2, RoundingMode.HALF_UP));
        saved.setTotalRecycle(totalRecycle);
        saved.setReceivableBal(BigDecimal.ZERO);
        saved.setCreateBy(SecurityUtils.getCurrentUserId());
        orderDao.insert(saved);

        for (EamDisposalLine l : builtLines) {
            l.setOrderId(saved.getId());
            lineDao.insert(l);
        }
        for (EamDisposalRecycle r : builtRecycles) {
            r.setOrderId(saved.getId());
            recycleDao.insert(r);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order", saved);
        out.put("lineCount", builtLines.size());
        return out;
    }

    @Override
    @Transactional
    public void update(EamDisposalOrder req, List<EamDisposalLine> lines,
                       List<EamDisposalRecycle> recycles) {
        EamDisposalOrder old = require(req.getId());
        if (!"DRAFT".equals(old.getAppStatus()) && !"REJECTED".equals(old.getAppStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可编辑（当前 " + old.getAppStatus() + "）");
        }
        // 行/回收整单重建（校验+快照复用 buildSnapshot）
        Map<String, Object> snap = buildSnapshot(old, lines, recycles);
        lineDao.delete(new LambdaQueryWrapper<EamDisposalLine>().eq(EamDisposalLine::getOrderId, old.getId()));
        recycleDao.delete(new LambdaQueryWrapper<EamDisposalRecycle>().eq(EamDisposalRecycle::getOrderId, old.getId()));
        @SuppressWarnings("unchecked")
        List<EamDisposalLine> builtLines = (List<EamDisposalLine>) snap.get("lines");
        @SuppressWarnings("unchecked")
        List<EamDisposalRecycle> builtRecycles = (List<EamDisposalRecycle>) snap.get("recycles");
        for (EamDisposalLine l : builtLines) {
            l.setOrderId(old.getId());
            lineDao.insert(l);
        }
        for (EamDisposalRecycle r : builtRecycles) {
            r.setOrderId(old.getId());
            recycleDao.insert(r);
        }
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(old.getId());
        patch.setVerNo(old.getVerNo());
        patch.setReasonCode(req.getReasonCode());
        patch.setReasonNote(req.getReasonNote());
        patch.setEstResidual(req.getEstResidual());
        patch.setEvalReportNo(req.getEvalReportNo());
        patch.setReviewNote(req.getReviewNote());
        patch.setTotalCost((BigDecimal) snap.get("totalCost"));
        patch.setTotalAccum((BigDecimal) snap.get("totalAccum"));
        patch.setTotalNbv((BigDecimal) snap.get("totalNbv"));
        patch.setTotalProceeds((BigDecimal) snap.get("totalProceeds"));
        patch.setTotalRecycle((BigDecimal) snap.get("totalRecycle"));
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
    }

    /** 行/回收校验 + 快照 + 汇总（create/update 共用；不落库） */
    private Map<String, Object> buildSnapshot(EamDisposalOrder order, List<EamDisposalLine> reqLines,
                                              List<EamDisposalRecycle> recycles) {
        List<EamAssetCard> cards = new ArrayList<>();
        List<String> blockers = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (EamDisposalLine l : reqLines) {
            if (!seen.add(l.getCardId())) {
                throw new ServiceException(422, "同卡不可重复加入处置单：" + l.getCardId());
            }
            EamAssetCard card = cardDao.selectById(l.getCardId());
            if (card == null || !"0".equals(card.getDelFlag())) {
                blockers.add("卡片不存在：" + l.getCardId());
                continue;
            }
            EamDisposalOrder inFlight = orderDao.selectInFlightByCard(card.getId());
            if (inFlight != null && !inFlight.getId().equals(order.getId())) {
                blockers.add("卡片 " + card.getCardNo() + " 已有进行中的处置单 " + inFlight.getDspNo());
            }
            if (!"IN_USE".equals(card.getStatus())) {
                blockers.add("卡片 " + card.getCardNo() + " 状态非在用（" + card.getStatus() + "），不可处置");
            }
            if ("1".equals(card.getCountFreezeFlag())) {
                blockers.add("卡片 " + card.getCardNo() + " 处于盘点冻结状态（BR-4.13-30），不可处置");
            }
            try {
                safetyLockService.assertNoActiveLock(card.getId());
            } catch (ServiceException ex) {
                blockers.add("卡片 " + card.getCardNo() + "：" + ex.getMessage());
            }
            List<EamWorkOrder> openWos = workOrderDao.selectList(new LambdaQueryWrapper<EamWorkOrder>()
                    .eq(EamWorkOrder::getCardId, card.getId())
                    .notIn(EamWorkOrder::getStatus, "CLOSED", "CANCELLED"));
            for (EamWorkOrder wo : openWos) {
                blockers.add("卡片 " + card.getCardNo() + " 存在未关闭工单：" + wo.getWoNo());
            }
            var openTrf = transferOrderDao.selectInFlightByCard(card.getId(), null);
            if (openTrf != null) {
                blockers.add("卡片 " + card.getCardNo() + " 存在未关闭调拨单：" + openTrf.getTrfNo());
            }
            cards.add(card);
        }
        if (!blockers.isEmpty()) {
            throw new ServiceException(422, "处置前置校验未通过（逐项列示）：" + String.join("；", blockers));
        }
        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalAccum = BigDecimal.ZERO;
        BigDecimal totalNbv = BigDecimal.ZERO;
        BigDecimal totalProceeds = BigDecimal.ZERO;
        List<EamDisposalLine> builtLines = new ArrayList<>();
        BigDecimal tolerance = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.5"));
        for (int i = 0; i < reqLines.size(); i++) {
            EamAssetCard card = cards.get(i);
            BigDecimal cost = nz(card.getAssetCost());
            BigDecimal accum = nz(card.getAccumDepr()).add(nz(card.getImpairment()));
            BigDecimal nbv = cost.subtract(accum).max(BigDecimal.ZERO);
            BigDecimal residualValue = card.getResidualValue() == null
                    ? cost.multiply(nz(card.getResidualRate())).setScale(2, RoundingMode.HALF_UP)
                    : card.getResidualValue();
            boolean fully = "FULLY".equals(card.getDeprStatus())
                    || accum.subtract(cost.subtract(residualValue)).abs().compareTo(tolerance) <= 0;
            EamDisposalLine line = reqLines.get(i);
            EamDisposalLine built = new EamDisposalLine();
            built.setCardId(card.getId());
            built.setCardNo(card.getCardNo());
            built.setAssetName(card.getAssetName());
            built.setCategoryCode(card.getCategoryCode());
            built.setDeptName(card.getDeptName());
            built.setCostCenterCode(card.getCostCenterCode());
            built.setOrigCost(cost);
            built.setAccumDepr(accum);
            built.setNbv(nbv);
            built.setFullyDeprFlag(fully ? "1" : "0");
            built.setLineProceeds(line == null ? null : line.getLineProceeds());
            built.setCreateBy(SecurityUtils.getCurrentUserId());
            builtLines.add(built);
            totalCost = totalCost.add(cost);
            totalAccum = totalAccum.add(accum);
            totalNbv = totalNbv.add(nbv);
            totalProceeds = totalProceeds.add(nz(built.getLineProceeds()));
        }
        List<EamDisposalRecycle> builtRecycles = new ArrayList<>();
        BigDecimal totalRecycle = BigDecimal.ZERO;
        if (recycles != null) {
            for (EamDisposalRecycle r : recycles) {
                if (r == null || !hasText(r.getItemName()) || !hasText(r.getItemCode())) {
                    throw new ServiceException(422, "回收明细品名与物料编码必填");
                }
                EamDisposalRecycle rb = new EamDisposalRecycle();
                rb.setItemName(r.getItemName());
                rb.setItemCode(r.getItemCode());
                rb.setQty(r.getQty() == null ? BigDecimal.ONE : r.getQty());
                rb.setEstPrice(r.getEstPrice() == null ? BigDecimal.ZERO : r.getEstPrice());
                rb.setGrStatus("PENDING");
                rb.setCreateBy(SecurityUtils.getCurrentUserId());
                builtRecycles.add(rb);
                totalRecycle = totalRecycle.add(rb.getEstPrice().multiply(rb.getQty()));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", builtLines);
        out.put("recycles", builtRecycles);
        out.put("totalCost", totalCost.setScale(2, RoundingMode.HALF_UP));
        out.put("totalAccum", totalAccum.setScale(2, RoundingMode.HALF_UP));
        out.put("totalNbv", totalNbv.setScale(2, RoundingMode.HALF_UP));
        out.put("totalProceeds", totalProceeds.setScale(2, RoundingMode.HALF_UP));
        out.put("totalRecycle", totalRecycle.setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    // ================= 提交审批 =================

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        EamDisposalOrder order = require(id);
        if (!"DRAFT".equals(order.getAppStatus()) && !"REJECTED".equals(order.getAppStatus())) {
            throw new ServiceException(422, "当前状态不可提交审批（" + order.getAppStatus() + "）");
        }
        BigDecimal nbv = nz(order.getTotalNbv());
        BigDecimal residual = nz(order.getEstResidual());
        // C-4.13-05：评估残值 > NBV × DEPRECIATION_RESIDUAL_ALERT(10%) → 须复核说明
        // DEPRECIATION_RESIDUAL_ALERT 为比例口径（库存值 0.10 = 10%）
        BigDecimal alertRate = sysParamService.getAmount("DEPRECIATION_RESIDUAL_ALERT", new BigDecimal("0.10"));
        if (nbv.signum() > 0 && residual.compareTo(nbv.multiply(alertRate)) > 0) {
            if (!hasText(order.getReviewNote())) {
                throw new ServiceException(422, "残值评估偏高（评估残值 " + residual + " > NBV×"
                        + alertRate.movePointRight(2).stripTrailingZeros().toPlainString()
                        + "%），须填写复核说明后方可提交审批");
            }
        }
        // BR-4.13-31：出售且评估价低于 NBV → 评估报告必传
        if ("SALE".equals(order.getDisposalType())
                && nz(order.getTotalProceeds()).compareTo(nbv) < 0) {
            if (!hasText(order.getEvalReportNo())) {
                throw new ServiceException(422, "出售评估价低于 NBV（处置损失 "
                        + nbv.subtract(nz(order.getTotalProceeds())) + "），须上传评估报告（BR-4.13-31）");
            }
        }
        // BR-32：已提足折旧校验
        assertFullyDepr(order.getId());

        // 分级链（D3：按单卡最高原值取档）
        BigDecimal maxCost = lineDao.selectList(new LambdaQueryWrapper<EamDisposalLine>()
                        .eq(EamDisposalLine::getOrderId, order.getId())).stream()
                .map(EamDisposalLine::getOrigCost).map(EamDisposalServiceImpl::nz)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        BigDecimal thA = sysParamService.getAmount("ASSET_DISPOSAL_THRESHOLD_A", new BigDecimal("500000"));
        BigDecimal thB = sysParamService.getAmount("ASSET_DISPOSAL_THRESHOLD_B", new BigDecimal("5000000"));
        // 每角色一组串行（部门主管 → 资产管理员 → [财务经理] → [总经理]）
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_DEPT_HEAD", "部门主管审批")));
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_ASSET_ADMIN", "资产管理员审核")));
        String tier = "TIER_A";
        if (maxCost.compareTo(thB) >= 0) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR", "财务经理审批（超阈值A）")));
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_GM", "总经理审批（超阈值B）")));
            tier = "TIER_B";
        } else if (maxCost.compareTo(thA) >= 0) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FIN_MGR", "财务经理审批（超阈值A）")));
            tier = "TIER_MID";
        }
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setAppStatus("PENDING_APPROVE");
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        String title = "资产处置审批：" + order.getDspNo() + " "
                + ("SCRAP".equals(order.getDisposalType()) ? "报废" : "出售")
                + " 原值合计 " + order.getTotalCost() + " / 损益 " + nz(order.getGainLoss())
                + "（" + tier + "）";
        var instance = approvalEngine.submit("AssetDisposal", order.getId(), title, null, chain);
        EamDisposalOrder apatch = new EamDisposalOrder();
        apatch.setId(order.getId());
        apatch.setVerNo(orderDao.selectById(order.getId()).getVerNo());
        apatch.setApprId(instance.getId());
        apatch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(apatch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getId());
        out.put("apprId", instance.getId());
        out.put("nodes", chain.size());
        out.put("tier", tier);
        return out;
    }

    @Override
    @Transactional
    public void voidOrder(String id, String reason) {
        EamDisposalOrder order = require(id);
        if (!"DRAFT".equals(order.getAppStatus()) && !"REJECTED".equals(order.getAppStatus())) {
            throw new ServiceException(422, "仅草稿/已驳回可作废");
        }
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setAppStatus("CANCELLED");
        patch.setReasonNote(hasText(reason) ? reason : order.getReasonNote());
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
    }

    // ================= 审批回调 =================

    @Override
    @Transactional
    public Map<String, Object> onApprovalPassed(String id) {
        EamDisposalOrder order = require(id);
        // BR-33：科目前置校验（缺失 L1 阻断并列编码，失败连带回滚审批推进）
        assertAccounts(ACCT_CLEARING, ACCT_GAINLOSS, ACCT_FA, ACCT_ACCUM);
        // 段1：转清理（C-4.13-03 审批通过自动生成，失败整单回滚）
        BigDecimal nbv = nz(order.getTotalNbv());
        BigDecimal accum = nz(order.getTotalAccum());
        BigDecimal cost = nz(order.getTotalCost());
        String summary = "处置转清理：" + order.getDspNo();
        List<GlVoucherService.FinVoucherLineSpec> t1 = new ArrayList<>();
        if (nbv.signum() > 0) {
            t1.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_CLEARING, "DR", nbv, summary));
        }
        if (accum.signum() > 0) {
            t1.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_ACCUM, "DR", accum, summary));
        }
        if (cost.signum() > 0) {
            t1.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_FA, "CR", cost, summary));
        }
        voucherService.create("DSE", LocalDate.now(), summary, "DISPOSAL_TRANSFER", order.getId(), null, t1);
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setAppStatus("APPROVED");
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        log.info("处置单 {} 审批通过，段1 转清理凭证已生成（清理 {} / 累计 {} / 原值 {}）",
                order.getDspNo(), nbv, accum, cost);
        return Map.of("orderId", id, "status", "APPROVED");
    }

    @Override
    @Transactional
    public void onApprovalRejected(String id, String reason) {
        EamDisposalOrder order = require(id);
        String actual = hasText(reason) ? reason : "处置审批驳回";
        java.util.List<com.erp.entity.system.ApprovalTask> rejected =
                approvalTaskDao.selectList(new LambdaQueryWrapper<com.erp.entity.system.ApprovalTask>()
                        .eq(com.erp.entity.system.ApprovalTask::getApprId, order.getApprId())
                        .eq(com.erp.entity.system.ApprovalTask::getStatus, "REJECTED"));
        if (!rejected.isEmpty() && hasText(rejected.get(0).getOpinion())) {
            actual = rejected.get(0).getOpinion();
        }
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setAppStatus("REJECTED");
        patch.setReasonNote(actual);
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        log.info("处置单 {} 审批驳回（{}）", order.getDspNo(), actual);
    }

    // ================= 执行确认 =================

    @Override
    @Transactional
    public Map<String, Object> exec(String id, String proofNo, String envAssessNo, String dataDestroyNo) {
        EamDisposalOrder order = require(id);
        if (!"APPROVED".equals(order.getAppStatus())) {
            throw new ServiceException(422, "当前状态不可执行确认（" + order.getAppStatus() + "，须审批通过）");
        }
        if (!hasText(proofNo)) {
            throw new ServiceException(422, "执行凭证必填（报废证明编号/买卖合同编号）");
        }
        assertAccounts(ACCT_CLEARING, ACCT_GAINLOSS);
        List<EamDisposalLine> lines = linesOf(order.getId());
        // BR-36：分类双标记附件校验（缺失列清单阻断）
        List<String> missing = new ArrayList<>();
        for (EamDisposalLine l : lines) {
            EamAssetCategory cat = hasText(l.getCategoryCode())
                    ? categoryDao.selectOne(new LambdaQueryWrapper<EamAssetCategory>()
                            .eq(EamAssetCategory::getCategoryCode, l.getCategoryCode()).last("LIMIT 1"))
                    : null;
            if (cat == null) {
                continue;
            }
            if ("1".equals(cat.getEnvReviewFlag()) && !hasText(envAssessNo)) {
                missing.add("分类 " + cat.getCategoryCode() + " 须环保安全评估（卡片 " + l.getCardNo() + "）");
            }
            if ("1".equals(cat.getDataDestroyFlag()) && !hasText(dataDestroyNo)) {
                missing.add("分类 " + cat.getCategoryCode() + " 须数据销毁证明（卡片 " + l.getCardNo() + "）");
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "执行前置附件缺失（BR-4.13-36，列示）：" + String.join("；", missing));
        }

        // 回收入库（D4：createFree → confirm → posting 同事务，失败整体阻断）
        List<EamDisposalRecycle> recycles = recyclesOf(order.getId());
        BigDecimal totalRecycle = BigDecimal.ZERO;
        for (EamDisposalRecycle r : recycles) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("supplierId", RECYCLE_SUPPLIER);
            payload.put("arrivalDate", LocalDate.now().toString());
            payload.put("remark", "处置残料回收入库：" + order.getDspNo() + " " + r.getItemName());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", r.getItemCode());
            row.put("receivedQty", r.getQty());
            payload.put("lines", List.of(row));
            Map<String, Object> created = goodsReceiptService.createFree(payload);
            Object grObj = created.get("gr");
            String grId = grObj instanceof com.erp.entity.proc.GoodsReceipt gr
                    ? gr.getId() : (grObj == null ? null : String.valueOf(grObj));
            if (!hasText(grId)) {
                throw new ServiceException(500, "回收入库单创建失败（" + r.getItemName() + "）");
            }
            // 残料回收免检（C-4.12-06：posting 要求检验放行；环保/数据销毁已在处置侧 BR-36 校验）
            grLineDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.erp.entity.proc.GoodsReceiptLine>()
                    .eq(com.erp.entity.proc.GoodsReceiptLine::getGrId, grId)
                    .set(com.erp.entity.proc.GoodsReceiptLine::getQcStatus, "SKIPPED"));
            // 仓位分配确认（C-4.4-11/4.4.5：posting 强前置 putaway 台账 CONFIRMED）
            String grNo = grObj instanceof com.erp.entity.proc.GoodsReceipt g2 ? g2.getGrNo() : null;
            Map<String, Object> rec = binAssignmentService.recommend(grNo, 1);
            // record 为 toMap 结构（非实体）：取 id
            Object recObj = rec.get("record");
            String putawayId = recObj instanceof Map<?, ?> rm && rm.get("id") != null
                    ? String.valueOf(rm.get("id")) : null;
            if (!hasText(putawayId)) {
                throw new ServiceException(500, "回收物无可用仓位（" + r.getItemCode() + "），无法入库");
            }
            binAssignmentService.confirm(putawayId);
            // 顺序=创建→过账（posting 要求 CREATED；confirm 带仓库角色校验，实物确认留给仓库工作台——design D4 偏差记录）
            goodsReceiptService.posting(grId);
            EamDisposalRecycle rpatch = new EamDisposalRecycle();
            rpatch.setId(r.getId());
            rpatch.setVerNo(r.getVerNo());
            rpatch.setGrId(grId);
            rpatch.setGrStatus("POSTED");
            rpatch.setUpdateBy(SecurityUtils.getCurrentUserId());
            recycleDao.updateById(rpatch);
            totalRecycle = totalRecycle.add(r.getEstPrice().multiply(r.getQty()));
        }
        totalRecycle = totalRecycle.setScale(2, RoundingMode.HALF_UP);

        BigDecimal proceeds = nz(order.getTotalProceeds());
        BigDecimal nbv = nz(order.getTotalNbv());
        String execSummary = "处置执行：" + order.getDspNo() + " 凭证 " + proofNo;
        // 段2：出售挂应收（BR-35 未收不得关闭）+ 回收入货（实物补偿清理科目）
        if ("SALE".equals(order.getDisposalType()) && proceeds.signum() > 0) {
            voucherService.create("DSE", LocalDate.now(), execSummary + " 挂应收",
                    "DISPOSAL_PROCEEDS", order.getId(), null, List.of(
                    GlVoucherService.FinVoucherLineSpec.of(ACCT_AR, "DR", proceeds, execSummary),
                    GlVoucherService.FinVoucherLineSpec.of(ACCT_CLEARING, "CR", proceeds, execSummary)));
        }
        if (totalRecycle.signum() > 0) {
            voucherService.create("DSE", LocalDate.now(), execSummary + " 残料入库冲减",
                    "DISPOSAL_RECYCLE", order.getId(), null, List.of(
                    GlVoucherService.FinVoucherLineSpec.of(ACCT_MATERIAL, "DR", totalRecycle, execSummary),
                    GlVoucherService.FinVoucherLineSpec.of(ACCT_CLEARING, "CR", totalRecycle, execSummary)));
        }
        // 段3：损益结转（清理余额 → 6115；收益贷 6115 / 损失借 6115）
        BigDecimal gainLoss = proceeds.add(totalRecycle).subtract(nbv);
        List<GlVoucherService.FinVoucherLineSpec> plLines = new ArrayList<>();
        String plSummary = "处置损益结转：" + order.getDspNo() + " 损益 " + gainLoss;
        if (gainLoss.signum() > 0) {
            plLines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_CLEARING, "DR", gainLoss, plSummary));
            plLines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_GAINLOSS, "CR", gainLoss, plSummary));
        } else if (gainLoss.signum() < 0) {
            BigDecimal loss = gainLoss.abs();
            plLines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_GAINLOSS, "DR", loss, plSummary));
            plLines.add(GlVoucherService.FinVoucherLineSpec.of(ACCT_CLEARING, "CR", loss, plSummary));
        }
        if (!plLines.isEmpty()) {
            voucherService.create("DSE", LocalDate.now(), plSummary, "DISPOSAL_GAINLOSS", order.getId(), null, plLines);
        }

        // 逐卡置 DISPOSED + DISPOSED_AT（BR-34 双条件之执行确认）
        LocalDateTime now = LocalDateTime.now();
        for (EamDisposalLine l : lines) {
            EamAssetCard card = cardDao.selectById(l.getCardId());
            if (card == null) {
                throw new ServiceException(409, "卡片已不存在：" + l.getCardNo());
            }
            EamDisposalLine lpatch = new EamDisposalLine();
            lpatch.setId(l.getId());
            lpatch.setVerNo(l.getVerNo());
            lpatch.setLineProceeds(l.getLineProceeds());
            lineDao.updateById(lpatch);
            EamAssetCard cupd = new EamAssetCard();
            cupd.setId(card.getId());
            cupd.setVerNo(card.getVerNo());
            cupd.setStatus("DISPOSED");
            cupd.setDisposedAt(now);
            cupd.setUpdateBy(SecurityUtils.getCurrentUserId());
            if (cardDao.updateById(cupd) == 0) {
                throw new ServiceException(409, "卡片更新冲突：" + card.getCardNo());
            }
        }

        BigDecimal receivable = "SALE".equals(order.getDisposalType()) ? proceeds : BigDecimal.ZERO;
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setAppStatus(receivable.signum() > 0 ? "RECEIVABLE" : "EXECUTED");
        patch.setTotalRecycle(totalRecycle);
        patch.setGainLoss(gainLoss);
        patch.setReceivableBal(receivable);
        patch.setExecutedAt(now);
        patch.setRemark(proofNo + (hasText(envAssessNo) ? " | 环保评估 " + envAssessNo : "")
                + (hasText(dataDestroyNo) ? " | 数据销毁 " + dataDestroyNo : ""));
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getId());
        out.put("status", patch.getAppStatus());
        out.put("gainLoss", gainLoss);
        out.put("receivableBal", receivable);
        out.put("recyclePosted", recycles.size());
        return out;
    }

    // ================= 收款 / 结案 =================

    @Override
    @Transactional
    public Map<String, Object> receive(String id, BigDecimal amount, String note) {
        EamDisposalOrder order = require(id);
        if (!"RECEIVABLE".equals(order.getAppStatus()) && !"EXECUTED".equals(order.getAppStatus())) {
            throw new ServiceException(422, "当前状态不可登记收款（" + order.getAppStatus() + "）");
        }
        BigDecimal bal = nz(order.getReceivableBal());
        if (bal.signum() <= 0) {
            throw new ServiceException(422, "应收处置款已结清，无需收款");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "收款金额必须大于 0");
        }
        if (amount.compareTo(bal) > 0) {
            throw new ServiceException(422, "收款金额超过应收余额 " + bal);
        }
        String summary = "处置收款：" + order.getDspNo() + " " + amount
                + (hasText(note) ? "（" + note + "）" : "");
        voucherService.create("DSE", LocalDate.now(), summary, "DISPOSAL_PROCEEDS", order.getId(), null, List.of(
                GlVoucherService.FinVoucherLineSpec.of(ACCT_BANK, "DR", amount, summary),
                GlVoucherService.FinVoucherLineSpec.of(ACCT_AR, "CR", amount, summary)));
        BigDecimal newBal = bal.subtract(amount);
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setReceivableBal(newBal.setScale(2, RoundingMode.HALF_UP));
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getId());
        out.put("receivableBal", newBal);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> submitReport(String id) {
        EamDisposalOrder order = require(id);
        if (!"EXECUTED".equals(order.getAppStatus()) && !"RECEIVABLE".equals(order.getAppStatus())) {
            throw new ServiceException(422, "当前状态不可结案（" + order.getAppStatus() + "）");
        }
        // BR-35：应收未结不得关闭
        if (nz(order.getReceivableBal()).signum() > 0) {
            throw new ServiceException(422, "应收处置款未结清（余额 " + order.getReceivableBal() + "），不可结案（BR-4.13-35）");
        }
        // 回收入库须全部完成
        long pending = recycleDao.selectCount(new LambdaQueryWrapper<EamDisposalRecycle>()
                .eq(EamDisposalRecycle::getOrderId, order.getId())
                .eq(EamDisposalRecycle::getGrStatus, "PENDING"));
        if (pending > 0) {
            throw new ServiceException(422, "存在 " + pending + " 条残料回收未入库，不可结案");
        }
        // 卡片须全部 DISPOSED
        for (EamDisposalLine l : linesOf(order.getId())) {
            EamAssetCard card = cardDao.selectById(l.getCardId());
            if (card == null || !"DISPOSED".equals(card.getStatus())) {
                throw new ServiceException(422, "卡片 " + l.getCardNo() + " 未置已处置，不可结案");
            }
        }
        String summary = "处置结案报告：" + order.getDspNo()
                + " 方式 " + order.getDisposalType()
                + " / 原值合计 " + order.getTotalCost()
                + " / NBV " + order.getTotalNbv()
                + " / 处置价 " + order.getTotalProceeds()
                + " / 回收 " + order.getTotalRecycle()
                + " / 损益 " + order.getGainLoss()
                + "（凭证锚 DISPOSAL_TRANSFER/PROCEEDS/RECYCLE/GAINLOSS 单据 " + order.getId() + "）";
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setReportSummary(summary);
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        var instance = approvalEngine.submit("AssetDisposalReport", order.getId(),
                "处置结案归档：" + order.getDspNo(), null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_FIN_ASSET", "财务资产会计归档审核"))));
        EamDisposalOrder patch2 = new EamDisposalOrder();
        patch2.setId(order.getId());
        patch2.setVerNo(orderDao.selectById(order.getId()).getVerNo());
        patch2.setReportApprId(instance.getId());
        patch2.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch2);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getId());
        out.put("apprId", instance.getId());
        out.put("reportSummary", summary);
        return out;
    }

    @Override
    @Transactional
    public void onReportClosed(String id) {
        EamDisposalOrder order = require(id);
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setAppStatus("CLOSED");
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        log.info("处置单 {} 结案归档 CLOSED", order.getDspNo());
    }

    // ================= 整单冲回 =================

    @Override
    @Transactional
    public Map<String, Object> reverse(String id) {
        EamDisposalOrder order = require(id);
        if ("CLOSED".equals(order.getAppStatus())) {
            throw new ServiceException(422, "已结案归档的处置单不可冲回");
        }
        if (!List.of("EXECUTED", "RECEIVABLE").contains(order.getAppStatus())) {
            throw new ServiceException(422, "当前状态不可冲回（" + order.getAppStatus() + "）");
        }
        // 逆序冲回：段3 损益 → 段2 应收/回收/收款 → 段1 转清理
        int reversed = 0;
        for (String st : List.of("DISPOSAL_GAINLOSS", "DISPOSAL_PROCEEDS", "DISPOSAL_RECYCLE", "DISPOSAL_TRANSFER")) {
            for (Map<String, Object> v : voucherService.listBySource(st, order.getId())) {
                Object vid = v.get("id");
                if (vid != null) {
                    voucherService.reverse(String.valueOf(vid), "处置整单冲回", order.getDspNo());
                    reversed++;
                }
            }
        }
        // 卡片回 IN_USE 清 DISPOSED_AT
        for (EamDisposalLine l : linesOf(order.getId())) {
            EamAssetCard card = cardDao.selectById(l.getCardId());
            if (card != null && "DISPOSED".equals(card.getStatus())) {
                // wrapper 显式置 NULL（不依赖实体 null 更新策略）
                cardDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EamAssetCard>()
                        .eq(EamAssetCard::getId, card.getId())
                        .set(EamAssetCard::getStatus, "IN_USE")
                        .set(EamAssetCard::getDisposedAt, null)
                        .set(EamAssetCard::getUpdateBy, SecurityUtils.getCurrentUserId())
                        .set(EamAssetCard::getVerNo, card.getVerNo() + 1));
            }
        }
        // 回收行状态回 PENDING（库存入库单不自动冲销，需人工处理——design 风险偏差记录）
        recycleDao.selectList(new LambdaQueryWrapper<EamDisposalRecycle>()
                        .eq(EamDisposalRecycle::getOrderId, order.getId()))
                .forEach(r -> {
                    EamDisposalRecycle rp = new EamDisposalRecycle();
                    rp.setId(r.getId());
                    rp.setVerNo(r.getVerNo());
                    rp.setGrStatus("PENDING");
                    rp.setUpdateBy(SecurityUtils.getCurrentUserId());
                    recycleDao.updateById(rp);
                });
        EamDisposalOrder patch = new EamDisposalOrder();
        patch.setId(order.getId());
        patch.setVerNo(order.getVerNo());
        patch.setAppStatus("APPROVED");
        patch.setReceivableBal(BigDecimal.ZERO);
        patch.setGainLoss(BigDecimal.ZERO);
        patch.setExecutedAt(null);
        patch.setUpdateBy(SecurityUtils.getCurrentUserId());
        orderDao.updateById(patch);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("orderId", order.getId());
        out.put("reversedVouchers", reversed);
        out.put("status", "APPROVED");
        out.put("note", "库存入库单不自动冲销，如需退回请在库存域处理");
        return out;
    }

    // ================= 私有辅助 =================

    private void assertAccounts(String... codes) {
        List<String> missing = new ArrayList<>();
        for (String code : codes) {
            FinAccount acc = accountDao.selectActiveByCode(code);
            if (acc == null) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "处置科目配置缺失（BR-4.13-33 阻断）：" + String.join("、", missing));
        }
    }

    private void assertFullyDepr(String orderId) {
        BigDecimal tolerance = sysParamService.getAmount("TOLERANCE_DEFAULT", new BigDecimal("0.5"));
        for (EamDisposalLine l : linesOf(orderId)) {
            if (!"1".equals(l.getFullyDeprFlag())) {
                continue;
            }
            EamAssetCard card = cardDao.selectById(l.getCardId());
            if (card == null) {
                continue;
            }
            BigDecimal cost = nz(card.getAssetCost());
            BigDecimal residual = card.getResidualValue() == null
                    ? cost.multiply(nz(card.getResidualRate())).setScale(2, RoundingMode.HALF_UP)
                    : card.getResidualValue();
            BigDecimal expected = cost.subtract(residual);
            if (nz(card.getAccumDepr()).subtract(expected).abs().compareTo(tolerance) > 0) {
                throw new ServiceException(422, "已提足折旧校验失败（BR-4.13-32）：卡片 " + card.getCardNo()
                        + " 累计折旧 " + card.getAccumDepr() + " ≠ 原值−残值 " + expected);
            }
        }
    }

    private List<EamDisposalLine> linesOf(String orderId) {
        return lineDao.selectList(new LambdaQueryWrapper<EamDisposalLine>()
                .eq(EamDisposalLine::getOrderId, orderId));
    }

    private List<EamDisposalRecycle> recyclesOf(String orderId) {
        return recycleDao.selectList(new LambdaQueryWrapper<EamDisposalRecycle>()
                .eq(EamDisposalRecycle::getOrderId, orderId));
    }

    private EamDisposalOrder require(String id) {
        EamDisposalOrder order = id == null ? null : orderDao.selectById(id);
        if (order == null) {
            throw new ServiceException(404, "处置单不存在：" + id);
        }
        return order;
    }

    private String nextDspNo() {
        String date = LocalDate.now().toString().replace("-", "");
        Long seq = orderDao.selectCount(new LambdaQueryWrapper<EamDisposalOrder>()
                .likeRight(EamDisposalOrder::getDspNo, "DSP" + date));
        return "DSP" + date + String.format("%04d", (seq == null ? 0 : seq) + 1);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }

    private static String norm(String v) {
        return v == null ? "" : v.trim().toUpperCase();
    }
}
