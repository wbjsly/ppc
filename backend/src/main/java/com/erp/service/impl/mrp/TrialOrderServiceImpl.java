package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpBomDao;
import com.erp.dao.mrp.MrpBomItemDao;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpRoutingDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.dao.mrp.TrialApplyDao;
import com.erp.dao.system.ApprovalInstanceDao;
import com.erp.entity.inv.InvBatch;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpBom;
import com.erp.entity.mrp.MrpBomItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.TrialApply;
import com.erp.entity.mrp.TrialCost;
import com.erp.entity.mrp.TrialData;
import com.erp.entity.mrp.TrialSummary;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoDataSource;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.TrialOrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 试产工单实现（change add-trial-production-management，spec trial-order）。
 * design D2 编码分池复用 MoService.insertWithMoNo（TP- 前缀）+ checkNoInFlight（三池+阶段细分）；
 * design D4 建单卡控链：C-4.5-14 BOM/路线三分支 + PVT diff（TrialPvtDiff 审批通过才建单）；
 * design D3 试产 BOM 快照经 MoDataSource.bomSnapshot（TRIAL 态版本亦可展平）。
 */
@Slf4j
@Service
public class TrialOrderServiceImpl implements TrialOrderService {

    /** PVT 一致性差异审批 BIZ_TYPE（bizId = 申请单号；清单入通知与标题） */
    public static final String BIZ_TRIAL_PVT_DIFF = "TrialPvtDiff";

    private final TrialApplyDao applyDao;
    private final com.erp.dao.mrp.TrialCostDao trialCostDao;
    private final com.erp.dao.mrp.TrialSummaryDao trialSummaryDao;
    private final com.erp.dao.qms.InspectionLotDao lotDao;
    private final MrpMoDao moDao;
    private final MrpBomDao bomDao;
    private final MrpBomItemDao bomItemDao;
    private final MrpRoutingDao routingDao;
    private final MrpRoutingOpDao routingOpDao;
    private final MrpMoBomDao moBomDao;
    private final MrpMoOpDao moOpDao;
    private final ApprovalInstanceDao approvalInstanceDao;
    private final ApprovalEngine approvalEngine;
    private final MoService moService;
    private final MoDataSource moDataSource;
    private final KittingService kittingService;
    private final com.erp.service.system.NoticeService noticeService;
    private final com.erp.dao.mdm.MdmItemDao mdmItemDao;
    private final com.erp.dao.mrp.MrpFeedDao feedDao;
    private final com.erp.dao.mrp.MrpReportingDao reportingDao;
    private final com.erp.dao.mrp.TrialDataDao trialDataDao;
    private final com.erp.dao.inv.InvTransactionDao txnDao;
    private final com.erp.dao.inv.InvBatchDao batchDao;
    private final com.erp.service.inv.StockPostingEngine stockPostingEngine;
    private final com.erp.service.SysParamService sysParam;
    private final com.erp.service.qms.InspectionLotService lotService;
    private final com.erp.service.mrp.BomService bomService;
    private final com.erp.service.mrp.RoutingService routingService;
    private final TrialCloseChecks closeChecks;

    public TrialOrderServiceImpl(TrialApplyDao applyDao,
                                 com.erp.dao.mrp.TrialCostDao trialCostDao,
                                 com.erp.dao.mrp.TrialSummaryDao trialSummaryDao,
                                 com.erp.dao.qms.InspectionLotDao lotDao,
                                 MrpMoDao moDao, MrpBomDao bomDao,
                                 MrpBomItemDao bomItemDao, MrpRoutingDao routingDao,
                                 MrpRoutingOpDao routingOpDao, MrpMoBomDao moBomDao,
                                 MrpMoOpDao moOpDao, ApprovalInstanceDao approvalInstanceDao,
                                 @org.springframework.context.annotation.Lazy ApprovalEngine approvalEngine,
                                 MoService moService, MoDataSource moDataSource,
                                 KittingService kittingService,
                                 com.erp.service.system.NoticeService noticeService,
                                 com.erp.dao.mdm.MdmItemDao mdmItemDao,
                                 com.erp.dao.mrp.MrpFeedDao feedDao,
                                 com.erp.dao.mrp.MrpReportingDao reportingDao,
                                 com.erp.dao.mrp.TrialDataDao trialDataDao,
                                 com.erp.dao.inv.InvTransactionDao txnDao,
                                 com.erp.dao.inv.InvBatchDao batchDao,
                                 com.erp.service.inv.StockPostingEngine stockPostingEngine,
                                 com.erp.service.SysParamService sysParam,
                                 @org.springframework.context.annotation.Lazy
                                         com.erp.service.qms.InspectionLotService lotService,
                                 @org.springframework.context.annotation.Lazy
                                         com.erp.service.mrp.BomService bomService,
                                 @org.springframework.context.annotation.Lazy
                                         com.erp.service.mrp.RoutingService routingService,
                                 TrialCloseChecks closeChecks) {
        this.applyDao = applyDao;
        this.trialCostDao = trialCostDao;
        this.trialSummaryDao = trialSummaryDao;
        this.lotDao = lotDao;
        this.moDao = moDao;
        this.bomDao = bomDao;
        this.bomItemDao = bomItemDao;
        this.routingDao = routingDao;
        this.routingOpDao = routingOpDao;
        this.moBomDao = moBomDao;
        this.moOpDao = moOpDao;
        this.approvalInstanceDao = approvalInstanceDao;
        this.approvalEngine = approvalEngine;
        this.moService = moService;
        this.moDataSource = moDataSource;
        this.kittingService = kittingService;
        this.noticeService = noticeService;
        this.mdmItemDao = mdmItemDao;
        this.feedDao = feedDao;
        this.reportingDao = reportingDao;
        this.trialDataDao = trialDataDao;
        this.txnDao = txnDao;
        this.batchDao = batchDao;
        this.stockPostingEngine = stockPostingEngine;
        this.sysParam = sysParam;
        this.lotService = lotService;
        this.bomService = bomService;
        this.routingService = routingService;
        this.closeChecks = closeChecks;
    }

    @Override
    @Transactional
    public Map<String, Object> createFromApply(String trialNo, String bomId, String routingId,
                                               boolean ackFormalBom) {
        requireWriter("转开试产工单");
        if (trialNo == null || trialNo.isBlank()) {
            throw new ServiceException(422, "申请单号必填");
        }
        // ---- ① 申请单资格（APPROVED 且未转开）----
        TrialApply apply = applyDao.selectOne(new LambdaQueryWrapper<TrialApply>()
                .eq(TrialApply::getTrialNo, trialNo.trim()).last("LIMIT 1"));
        if (apply == null) {
            throw new ServiceException(422, "试产申请单不存在：" + trialNo);
        }
        if (TrialApply.ST_CONVERTED.equals(apply.getStatus())) {
            throw new ServiceException(422, "申请单已转开工单 " + apply.getMoNo()
                    + "，同一申请单至多一张（1:1）：" + trialNo);
        }
        if (!TrialApply.ST_APPROVED.equals(apply.getStatus())) {
            throw new ServiceException(422,
                    "仅审批通过的申请单可转开工单（当前 " + apply.getStatus() + "）：" + trialNo);
        }
        String productCode = apply.getProductCode();
        String stage = apply.getTrialStage();

        // ---- ② 唯一在途（TRIAL 池按阶段细分）----
        moService.checkNoInFlight(productCode, MrpMo.MO_TYPE_TRIAL, stage);

        // ---- ③ BOM 卡控（C-4.5-14：TRIAL 放行 / PUBLISHED 确认 / 其余阻断）----
        MrpBom bom = resolveBom(productCode, bomId, ackFormalBom);

        // ---- ④ 路线卡控（同 BOM 规则；双无 → 阻断提示先编制试产路线）----
        MrpRouting routing = resolveRouting(productCode, routingId, ackFormalBom);

        // ---- ⑤ PVT 一致性 diff（BR-4.5-35：差异清单审批通过才建单）----
        if (TrialApply.PVT.equals(stage)) {
            List<String> diffs = pvtDiff(productCode, bom, routing);
            if (!diffs.isEmpty()) {
                Map<String, Object> diffGate = gatePvtDiff(apply, diffs);
                if (diffGate != null) {
                    return diffGate; // 待审批/重新发起：零工单
                }
            } else if (bom.getTrialStage() == null) {
                // 无量产基准且选用正式版：留痕放行（首发新品，design D4）
                log.info("PVT 建单无量产基准留痕放行：{} BOM={}", trialNo, bom.getId());
            }
        }

        // ---- 落库（TP- 编号 + 双快照 + 齐套预检）----
        MrpMo mo = new MrpMo();
        mo.setMoType(MrpMo.MO_TYPE_TRIAL);
        mo.setTrialStage(stage);
        mo.setSrcType("APPLY");
        mo.setSrcNo(apply.getTrialNo());
        mo.setSrcQty(apply.getQty());
        mo.setProductCode(productCode);
        mo.setProductName(apply.getProductName());
        mo.setQty(apply.getQty());
        mo.setPlanStartDate(LocalDate.now());
        mo.setPlanEndDate(LocalDate.now().plusDays(7));
        mo.setPriority(5);
        mo.setStatus(MrpMo.ST_PLANNED);
        mo.setShortageFlag("0");
        mo.setSourceBomId(bom.getId());
        mo.setBomVersion(bom.getVersionLabel());
        mo.setSourceRoutingId(routing.getId());
        moService.insertWithMoNo(mo);

        // BOM 快照（TRIAL 态版本亦可展平——MoDataSource.bomSnapshot）
        MoDataSource.BomSnapshot snap = moDataSource.bomSnapshot(bom);
        int lineNo = 0;
        for (MoDataSource.BomSnapshotLine l : snap.lines) {
            MrpMoBom row = new MrpMoBom();
            row.setMoId(mo.getId());
            row.setLineNo(++lineNo);
            row.setTreeLevel(l.treeLevel);
            row.setOwnerItem(l.ownerItem);
            row.setItemCode(l.itemCode);
            row.setItemName(l.itemName);
            row.setUnitQty(l.unitQty);
            row.setLossRate(l.lossRate);
            row.setBaseUnit(l.baseUnit);
            row.setSubstituteInfo(l.substituteInfo);
            moBomDao.insert(row);
        }
        // 工序快照（路线行 + 定额带出）
        List<MrpRoutingOp> routeOps = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, routing.getId())
                .orderByAsc(MrpRoutingOp::getOpSeq));
        for (MrpRoutingOp op : routeOps) {
            MrpMoOp row = new MrpMoOp();
            row.setMoId(mo.getId());
            row.setOpSeq(op.getOpSeq());
            row.setOpCode(op.getOpCode());
            row.setOpName(op.getOpName());
            row.setWcCode(op.getWcCode());
            row.setWcName(op.getWcName());
            row.setLeadTime(op.getLeadTime());
            row.setStandardYield(op.getStandardYield());
            row.setKeyOpFlag(op.getKeyOpFlag());
            row.setOpStatus("PENDING");
            if (op.getWcCode() != null && !op.getWcCode().isEmpty()) {
                MrpOpWcStandard std = moDataSource.standardOf(op.getOpCode(), op.getWcCode());
                if (std != null) {
                    row.setSetupHours(std.getSetupHours());
                    row.setRunHours(std.getRunHours());
                    row.setWaitHours(std.getWaitHours());
                    row.setMoveHours(std.getMoveHours());
                }
            }
            moOpDao.insert(row);
        }
        // 齐套预检（TRIAL 正常口径，缺料不阻断仅标记——spec trial-order MRP 与排产隔离）
        BigDecimal rate = kittingService.check(mo.getId());
        List<String> warnings = new ArrayList<>();
        MrpMo fresh = moDao.selectById(mo.getId());
        warnings.add("齐套率 " + rate + "%"
                + ("1".equals(fresh.getShortageFlag()) ? "（已标缺料待料）" : "（齐套）"));
        if (ackFormalBom) {
            warnings.add("已确认使用正式 BOM/路线试产（C-4.5-14 提示确认留痕）");
        }

        // 申请单 → CONVERTED（1:1 回写）
        int updated = applyDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<TrialApply>()
                .eq(TrialApply::getId, apply.getId())
                .eq(TrialApply::getStatus, TrialApply.ST_APPROVED)
                .set(TrialApply::getStatus, TrialApply.ST_CONVERTED)
                .set(TrialApply::getMoId, mo.getId())
                .set(TrialApply::getMoNo, mo.getMoNo())
                .set(TrialApply::getConvertBy, currentOperator())
                .set(TrialApply::getConvertAt, java.time.LocalDateTime.now()));
        if (updated == 0) {
            throw new ServiceException(409, "申请单并发转开冲突，请刷新后重试：" + trialNo);
        }
        log.info("试产工单转开：{} ← {} 阶段={} BOM={} 快照行={}",
                mo.getMoNo(), trialNo, stage, snap.bomVersion, lineNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mo", fresh);
        out.put("warnings", warnings);
        return out;
    }

    @Override
    public List<MrpMo> list(String trialStage, String status) {
        requireWriter("查询试产工单");
        return moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                .eq(MrpMo::getMoType, MrpMo.MO_TYPE_TRIAL)
                .eq(trialStage != null && !trialStage.isBlank(), MrpMo::getTrialStage,
                        trialStage == null ? null : trialStage.trim().toUpperCase())
                .eq(status != null && !status.isBlank(), MrpMo::getStatus, status)
                .orderByDesc(MrpMo::getCreateDate));
    }

    // ================= 阶段推导（spec 生命周期与阶段推导，零状态机改动） =================

    public static final String PHASE_EXECUTING = "EXECUTING";
    public static final String PHASE_PENDING_INSP = "PENDING_INSP";
    public static final String PHASE_PENDING_STOCK_IN = "PENDING_STOCK_IN";
    public static final String PHASE_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String PHASE_PENDING_CLOSE = "PENDING_CLOSE";
    public static final String PHASE_CLOSED = "CLOSED";

    @Override
    public String phaseOf(String moId) {
        requireWriter("查询试产阶段");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            throw new ServiceException(422, "试产工单不存在：" + moId);
        }
        if (MrpMo.ST_CLOSED.equals(mo.getStatus())
                || MrpMo.ST_CANCELLED.equals(mo.getStatus())) {
            return PHASE_CLOSED;
        }
        if (!MrpMo.ST_COMPLETED.equals(mo.getStatus())) {
            return PHASE_EXECUTING;
        }
        // COMPLETED 段：检验批（#TRIAL）→ 放行回调冲回分录 → 总结评审 → 关单
        com.erp.entity.qms.InspectionLot lot = lotDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.qms.InspectionLot>()
                        .eq(com.erp.entity.qms.InspectionLot::getRefType, "WORKORDER")
                        .eq(com.erp.entity.qms.InspectionLot::getRefId, moId + "#TRIAL")
                        .orderByDesc(com.erp.entity.qms.InspectionLot::getCreateDate)
                        .last("LIMIT 1"));
        if (lot == null || lot.getResult() == null || "PENDING".equals(lot.getResult())) {
            return PHASE_PENDING_INSP;
        }
        if (!"RELEASED".equals(lot.getStatus())) {
            // 已判合格但未放行（或不合格待处置）——仍属检验段
            return PHASE_PENDING_INSP;
        }
        // 放行后：冲回分录存在 = 入库过账完成（放行回调幂等锚，design D8）
        Long reversal = trialCostDao.selectCount(new LambdaQueryWrapper<TrialCost>()
                .eq(TrialCost::getMoId, moId)
                .eq(TrialCost::getCostType, TrialCost.T_REVERSAL));
        if (reversal == null || reversal == 0) {
            return PHASE_PENDING_STOCK_IN;
        }
        TrialSummary summary = trialSummaryDao.selectOne(new LambdaQueryWrapper<TrialSummary>()
                .eq(TrialSummary::getMoId, moId).last("LIMIT 1"));
        if (summary == null || !TrialSummary.ST_APPROVED.equals(summary.getStatus())) {
            return PHASE_PENDING_REVIEW;
        }
        return PHASE_PENDING_CLOSE; // 评审通过未关单（关单 → phaseOf 返回 CLOSED）
    }

    // ================= 完工 TRIAL 分支（spec trial-execution 产出入样品仓与放行前置） =================

    @Override
    @Transactional
    public Map<String, Object> onCompleted(MrpMo mo, java.math.BigDecimal qualified) {
        // 系统动作（完工链调用，鉴权在调用方）；三步各自幂等（冲销重入安全）
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            throw new ServiceException(422, "仅试产工单可执行试产完工分支");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        java.math.BigDecimal q = qualified == null ? java.math.BigDecimal.ZERO : qualified;

        // ① 合格产出经引擎 WIP_IN 入样品仓 QC_QTY（BR-4.5-34 不产生可销售库存）
        //    幂等锚 = 同单 TRIAL_STOCK_IN 流水（冲销重入跳过，库存不重复入账）
        String batchNo;
        Long prior = txnDao.selectCount(new LambdaQueryWrapper<com.erp.entity.inv.InvTransaction>()
                .eq(com.erp.entity.inv.InvTransaction::getBizDocType, "TRIAL_STOCK_IN")
                .eq(com.erp.entity.inv.InvTransaction::getBizDocNo, mo.getMoNo()));
        if (prior != null && prior > 0) {
            batchNo = txnDao.selectOne(new LambdaQueryWrapper<com.erp.entity.inv.InvTransaction>()
                    .eq(com.erp.entity.inv.InvTransaction::getBizDocType, "TRIAL_STOCK_IN")
                    .eq(com.erp.entity.inv.InvTransaction::getBizDocNo, mo.getMoNo())
                    .last("LIMIT 1")).getBatchNo();
            out.put("stockIn", false); // 幂等跳过
        } else if (q.signum() > 0) {
            batchNo = nextTrialBatchNo();
            MdmItem item = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, mo.getProductCode()).last("LIMIT 1"));
            String sampleWh = sysParam.getValue("TRIAL_SAMPLE_WH", "WH-SAMPLE");
            com.erp.service.inv.StockPostingEngine.Line line =
                    new com.erp.service.inv.StockPostingEngine.Line();
            line.warehouseCode = sampleWh;
            line.itemCode = mo.getProductCode();
            line.itemName = mo.getProductName();
            line.batchNo = batchNo;
            line.qty = q;
            line.intoQc = true; // QC_QTY（待处理池）——未放行不入 AVAILABLE
            if (item != null && "1".equals(item.getBatchFlag())) {
                // 批次管理物料效期必填（BR-4.1-08）：shelfLifeDays 缺省 365 天兜底
                int days = item.getShelfLifeDays() == null ? 365 : item.getShelfLifeDays();
                line.expiryDate = LocalDate.now().plusDays(days);
            }
            stockPostingEngine.post(com.erp.service.inv.StockPostingEngine.Request.of(
                    "WIP_IN", "TRIAL_STOCK_IN", mo.getMoNo(), List.of(line)));
            out.put("stockIn", true);
            out.put("batchNo", batchNo);
            out.put("warehouse", sampleWh);
            log.info("试产完工入库 {}：{} 件 → {} QC（批次 {}）", mo.getMoNo(), q, sampleWh, batchNo);
        } else {
            batchNo = null;
            out.put("stockIn", false);
        }

        // ② OQC 检验批 refId=<moId>#TRIAL（幂等在 lotService 内；合格量 0 不建批）
        if (q.signum() > 0) {
            com.erp.entity.qms.InspectionLot lot = lotService.createTrialLot(
                    mo.getId(), mo.getProductCode(), mo.getProductName(), batchNo, q);
            out.put("lotNo", lot.getLotNo());
            out.put("lotStatus", lot.getStatus());
        }

        // ③ 料工费归集（TRIAL: 重建式——冲销重入重算，3.11 补回拨对账口径）
        out.put("cost", aggregateTrialCost(mo));
        return out;
    }

    @Override
    public Map<String, Object> costOf(String moId) {
        requireWriter("查询试产成本");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            throw new ServiceException(422, "试产工单不存在：" + moId);
        }
        List<TrialCost> rows = trialCostDao.selectList(new LambdaQueryWrapper<TrialCost>()
                .eq(TrialCost::getMoId, moId)
                .orderByAsc(TrialCost::getCostType).orderByAsc(TrialCost::getEntryNo));
        BigDecimal collected = BigDecimal.ZERO;
        BigDecimal reversalPending = BigDecimal.ZERO;
        BigDecimal reversalConfirmed = BigDecimal.ZERO;
        for (TrialCost r : rows) {
            BigDecimal amt = r.getAmount() == null ? BigDecimal.ZERO : r.getAmount();
            if (TrialCost.T_REVERSAL.equals(r.getCostType())) {
                if (TrialCost.FIN_CONFIRMED.equals(r.getFinStatus())) {
                    reversalConfirmed = reversalConfirmed.add(amt);
                } else {
                    reversalPending = reversalPending.add(amt);
                }
            } else {
                collected = collected.add(amt);
            }
        }
        // 汇总 = 归集合计 − 已确认冲回（PENDING 不计入——design 风险「确认前剔除」）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moNo", mo.getMoNo());
        out.put("rows", rows);
        out.put("collectedTotal", collected);
        out.put("reversalPending", reversalPending);
        out.put("reversalConfirmed", reversalConfirmed);
        out.put("netTotal", collected.subtract(reversalConfirmed));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> promote(String moId) {
        requireWriter("试产转量产复制");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            throw new ServiceException(422, "试产工单不存在：" + moId);
        }
        // ① 评审闸口（FR-4.5-8-7：未经评审不得转量产）
        TrialSummary summary = trialSummaryDao.selectOne(new LambdaQueryWrapper<TrialSummary>()
                .eq(TrialSummary::getMoId, moId).last("LIMIT 1"));
        if (summary == null || !TrialSummary.ST_APPROVED.equals(summary.getStatus())) {
            throw new ServiceException(422, "试产总结评审未通过，禁止转量产（FR-4.5-8-7）："
                    + mo.getMoNo() + "（评审状态 "
                    + (summary == null ? "未建总结单" : summary.getStatus()) + "）");
        }
        // ② 克隆 BOM / 路线为量产草稿（SRC_TRIAL_NO 留痕；原试产版零改动）
        if (mo.getSourceBomId() == null || mo.getSourceRoutingId() == null) {
            throw new ServiceException(422, "试产工单缺少 BOM/路线快照来源，无法转量产：" + mo.getMoNo());
        }
        MrpBom prodBom = bomService.promoteFromTrial(mo.getSourceBomId(), mo.getMoNo());
        MrpRouting prodRouting = routingService.promoteFromTrial(mo.getSourceRoutingId(), mo.getMoNo());
        log.info("试产转量产复制：{} → BOM {} / 路线 {}", mo.getMoNo(),
                prodBom.getVersionLabel(), prodRouting.getVersionLabel());
        // ③ 返回两版本号供前端跳转既有发布页（design D11）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bomId", prodBom.getId());
        out.put("bomVersion", prodBom.getVersionLabel());
        out.put("routingId", prodRouting.getId());
        out.put("routingVersion", prodRouting.getVersionLabel());
        out.put("srcTrialNo", mo.getMoNo());
        out.put("next", "请在 BOM/路线发布页提交审核（发布两处再校验来源评审 APPROVED）");
        return out;
    }

    @Override
    public List<Map<String, Object>> remainderOf(String moId) {
        requireWriter("查询试产剩余物料");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            throw new ServiceException(422, "试产工单不存在：" + moId);
        }
        return closeChecks.remainderOf(mo);
    }

    @Override
    @Transactional
    public Map<String, Object> ackRemainder(String moId, String note) {
        requireWriter("确认试产剩余物料");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_TRIAL.equals(mo.getMoType())) {
            throw new ServiceException(422, "试产工单不存在：" + moId);
        }
        List<Map<String, Object>> remainder = closeChecks.remainderOf(mo);
        // 留痕台账（notice bizType=TRIAL_REMAINDER_ACK；TrialCloseChecks 校验锚）
        noticeService.push(null, currentOperator(),
                "试产剩余物料确认：" + mo.getMoNo(),
                (note == null || note.isBlank() ? "已确认剩余物料处理（退库/转样品）" : note.trim())
                        + "；余量明细 " + remainder.size() + " 项",
                TrialCloseChecks.BIZ_REMAINDER_ACK, moId);
        log.info("试产剩余物料确认留痕：{} 余量 {} 项 意见={}", mo.getMoNo(), remainder.size(), note);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moNo", mo.getMoNo());
        out.put("remainder", remainder);
        out.put("acked", true);
        return out;
    }

    /** 试产成本归集（FR-4.5-8-8 料工费；BR-4.5-36 EXPENSE_TYPE=RD 不入卷积；重建式幂等） */
    private Map<String, Object> aggregateTrialCost(MrpMo mo) {
        // 重建：删旧归集行（TRIAL: 前缀；REVERSAL 冲回行不动——对账独立）
        trialCostDao.delete(new LambdaQueryWrapper<TrialCost>()
                .eq(TrialCost::getMoId, mo.getId())
                .in(TrialCost::getCostType,
                        TrialCost.T_MATERIAL, TrialCost.T_LABOR, TrialCost.T_OVERHEAD)
                .like(TrialCost::getRemark, TrialCost.REMARK_PREFIX));
        MdmItem item = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, mo.getProductCode()).last("LIMIT 1"));
        java.math.BigDecimal stdCost = item == null || item.getStandardCost() == null
                ? java.math.BigDecimal.ZERO : item.getStandardCost();

        // 材料 = 投料行（未回加）× 各自子件标准成本（与 5.8 返工归集同口径，design D7）
        java.math.BigDecimal feedQty = java.math.BigDecimal.ZERO;
        java.math.BigDecimal material = java.math.BigDecimal.ZERO;
        List<com.erp.entity.mrp.MrpFeed> feeds = feedDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.mrp.MrpFeed>()
                        .eq(com.erp.entity.mrp.MrpFeed::getMoId, mo.getId())
                        .eq(com.erp.entity.mrp.MrpFeed::getReturnedFlag, "0"));
        java.util.Set<String> codes = feeds.stream().map(com.erp.entity.mrp.MrpFeed::getItemCode)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, java.math.BigDecimal> costByItem = new java.util.HashMap<>();
        if (!codes.isEmpty()) {
            for (MdmItem it : mdmItemDao.selectList(new LambdaQueryWrapper<MdmItem>()
                    .in(MdmItem::getItemCode, codes))) {
                if (it.getStandardCost() != null) {
                    costByItem.put(it.getItemCode(), it.getStandardCost());
                }
            }
        }
        for (com.erp.entity.mrp.MrpFeed f : feeds) {
            feedQty = feedQty.add(nvl(f.getQty()));
            java.math.BigDecimal c = costByItem.get(f.getItemCode());
            material = material.add(nvl(f.getQty()).multiply(c == null ? BigDecimal.ZERO : c));
        }

        // 工时 = 采集表实测工时合计 × TRIAL_LABOR_RATE（偏差 D7 采集表权威）
        java.math.BigDecimal laborHours = java.math.BigDecimal.ZERO;
        for (TrialData d : trialDataDao.selectList(new LambdaQueryWrapper<TrialData>()
                .eq(TrialData::getMoId, mo.getId()))) {
            laborHours = laborHours.add(nvl(d.getActualHours()));
        }
        java.math.BigDecimal labor = laborHours.multiply(paramDecimal("TRIAL_LABOR_RATE", "60"));

        // 费用 = 报工设备运行时间（CONFIRMED）× TRIAL_MACHINE_RATE
        java.math.BigDecimal machineHours = java.math.BigDecimal.ZERO;
        for (com.erp.entity.mrp.MrpReporting r : reportingDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.mrp.MrpReporting>()
                        .eq(com.erp.entity.mrp.MrpReporting::getMoId, mo.getId())
                        .eq(com.erp.entity.mrp.MrpReporting::getStatus,
                                com.erp.entity.mrp.MrpReporting.ST_CONFIRMED))) {
            machineHours = machineHours.add(nvl(r.getMachineHours()));
        }
        java.math.BigDecimal overhead = machineHours.multiply(paramDecimal("TRIAL_MACHINE_RATE", "100"));

        insertCostRow(mo, TrialCost.T_MATERIAL, material, "完工归集-材料 " + feedQty.toPlainString());
        insertCostRow(mo, TrialCost.T_LABOR, labor, "完工归集-工时 " + laborHours.toPlainString() + "h");
        insertCostRow(mo, TrialCost.T_OVERHEAD, overhead,
                "完工归集-制费 " + machineHours.toPlainString() + "h");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("material", material);
        out.put("labor", labor);
        out.put("overhead", overhead);
        out.put("total", material.add(labor).add(overhead));
        log.info("试产成本归集 {}：料 {} 工 {} 费 {}", mo.getMoNo(), material, labor, overhead);
        return out;
    }

    /**
     * 归集行插入（ENTRY_NO = TC+yyyyMMdd+6 位流水；FIN_STATUS=CONFIRMED 归集直接确认）。
     * 冲突递增重试——本表 UK 在 ENTRY_NO 且逻辑删除行仍占号（5.8 COPQ 同款教训），
     * 重建式归集删除后 count 只算存活行会撞已删行号。
     */
    private void insertCostRow(MrpMo mo, String costType, java.math.BigDecimal amount, String detail) {
        TrialCost row = new TrialCost();
        String prefix = "TC" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        Long cnt = trialCostDao.selectCount(new LambdaQueryWrapper<TrialCost>()
                .likeRight(TrialCost::getEntryNo, prefix));
        long seq = (cnt == null ? 0 : cnt) + 1;
        row.setMoId(mo.getId());
        row.setMoNo(mo.getMoNo());
        row.setCostType(costType);
        row.setAmount(amount);
        row.setExpenseType("RD");
        row.setFinStatus(TrialCost.FIN_CONFIRMED);
        row.setRemark(TrialCost.REMARK_PREFIX + detail);
        row.setCreateBy("system");
        for (int attempt = 0; attempt < 999; attempt++) {
            row.setEntryNo(prefix + String.format("%06d", seq + attempt));
            try {
                trialCostDao.insert(row);
                return;
            } catch (org.springframework.dao.DuplicateKeyException e) {
                seq++; // 已删行占号：跳号重试
            }
        }
        throw new ServiceException(422, "试产归集行号分配冲突超限，请稍后重试");
    }

    /** sys_param 十进制值（缺省兜底） */
    private java.math.BigDecimal paramDecimal(String key, String def) {
        try {
            return new java.math.BigDecimal(sysParam.getValue(key, def));
        } catch (RuntimeException e) {
            return new java.math.BigDecimal(def);
        }
    }

    /** 试产产出批次号 B+yyMMdd+4 位流水（batch-master 生成规则；查重避让既有批次） */
    private String nextTrialBatchNo() {
        String prefix = "B" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"));
        long cnt = batchDao.selectCount(new LambdaQueryWrapper<InvBatch>()
                .likeRight(InvBatch::getBatchNo, prefix));
        for (int attempt = 0; attempt < 99; attempt++) {
            String candidate = prefix + String.format("%04d", (cnt + 1 + attempt) % 10000);
            long exists = batchDao.selectCount(new LambdaQueryWrapper<InvBatch>()
                    .eq(InvBatch::getBatchNo, candidate));
            if (exists == 0) {
                return candidate;
            }
        }
        return prefix + String.format("%04d", (int) (System.nanoTime() % 10000));
    }

    // ================= C-4.5-14 卡控 =================

    /** BOM 三分支：指定版本校验 / 自动优选 TRIAL → PUBLISHED（需确认）/ 试产草稿未审批 422 */
    private MrpBom resolveBom(String productCode, String bomId, boolean ack) {
        if (bomId != null && !bomId.isBlank()) {
            MrpBom bom = bomDao.selectById(bomId.trim());
            if (bom == null || !productCode.equals(bom.getParentItemCode())) {
                throw new ServiceException(422, "BOM 版本不存在或不属于该产品：" + bomId);
            }
            return checkBomStatus(bom, ack);
        }
        // 自动优选：TRIAL 态最新 → 已发布最新
        MrpBom trial = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, productCode)
                .eq(MrpBom::getStatus, MrpBom.ST_TRIAL)
                .orderByDesc(MrpBom::getVersionMajor).orderByDesc(MrpBom::getVersionMinor)
                .last("LIMIT 1"));
        if (trial != null) {
            return trial;
        }
        MrpBom published = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, productCode)
                .eq(MrpBom::getStatus, MrpBom.ST_PUBLISHED)
                .orderByDesc(MrpBom::getVersionMajor).orderByDesc(MrpBom::getVersionMinor)
                .last("LIMIT 1"));
        if (published != null) {
            return checkBomStatus(published, ack);
        }
        // 无 TRIAL/已发布：存在未审批试产版 → C-4.5-14 阻断
        Long unapproved = bomDao.selectCount(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, productCode)
                .isNotNull(MrpBom::getTrialStage));
        if (unapproved != null && unapproved > 0) {
            throw new ServiceException(422,
                    "试产 BOM 未审批（C-4.5-14），请先完成试产 BOM 特别审批：" + productCode);
        }
        throw new ServiceException(422,
                "产品无试产版或已发布 BOM，阻断创建（FR-4.5-3-3）：" + productCode);
    }

    private MrpBom checkBomStatus(MrpBom bom, boolean ack) {
        if (MrpBom.ST_TRIAL.equals(bom.getStatus())) {
            return bom; // 试产已审批 → 放行
        }
        if (MrpBom.ST_PUBLISHED.equals(bom.getStatus())) {
            if (!ack) {
                throw new ServiceException(422,
                        "正在使用正式 BOM（" + bom.getVersionLabel() + "）试产，确认继续？"
                                + "（C-4.5-07 同类提示，确认后重试）");
            }
            return bom;
        }
        throw new ServiceException(422,
                "试产 BOM 状态非「试产已审批」（当前 " + bom.getStatus()
                        + "），阻断创建（C-4.5-14），请完成试产 BOM 特别审批");
    }

    /** 路线三分支（同 BOM 规则；双无 → 阻断提示先编制试产路线） */
    private MrpRouting resolveRouting(String productCode, String routingId, boolean ack) {
        if (routingId != null && !routingId.isBlank()) {
            MrpRouting routing = routingDao.selectById(routingId.trim());
            if (routing == null || !productCode.equals(routing.getItemCode())) {
                throw new ServiceException(422, "路线版本不存在或不属于该产品：" + routingId);
            }
            return checkRoutingStatus(routing, ack);
        }
        MrpRouting trial = routingDao.selectOne(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, productCode)
                .eq(MrpRouting::getStatus, MrpRouting.ST_TRIAL)
                .orderByDesc(MrpRouting::getVersionMajor).orderByDesc(MrpRouting::getVersionMinor)
                .last("LIMIT 1"));
        if (trial != null) {
            return trial;
        }
        MrpRouting published = routingDao.selectOne(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, productCode)
                .eq(MrpRouting::getRouteType, MrpRouting.T_NORMAL)
                .eq(MrpRouting::getStatus, MrpRouting.ST_PUBLISHED)
                .orderByDesc(MrpRouting::getVersionMajor).orderByDesc(MrpRouting::getVersionMinor)
                .last("LIMIT 1"));
        if (published != null) {
            return checkRoutingStatus(published, ack);
        }
        Long unapproved = routingDao.selectCount(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, productCode)
                .isNotNull(MrpRouting::getTrialStage));
        if (unapproved != null && unapproved > 0) {
            throw new ServiceException(422,
                    "试产路线未审批（C-4.5-14），请先完成试产路线特别审批：" + productCode);
        }
        throw new ServiceException(422,
                "产品无 TRIAL 态且无正式已发布路线，阻断创建，请先编制试产路线：" + productCode);
    }

    private MrpRouting checkRoutingStatus(MrpRouting routing, boolean ack) {
        if (MrpRouting.ST_TRIAL.equals(routing.getStatus())) {
            return routing;
        }
        if (MrpRouting.ST_PUBLISHED.equals(routing.getStatus())) {
            if (!ack) {
                throw new ServiceException(422,
                        "正在使用正式工艺路线（V" + routing.getVersionLabel() + "）试产，确认继续？"
                                + "（C-4.5-07 同类提示，确认后重试）");
            }
            return routing;
        }
        throw new ServiceException(422,
                "试产路线状态非「试产已审批」（当前 " + routing.getStatus()
                        + "），阻断创建（C-4.5-14），请完成试产路线特别审批");
    }

    // ================= PVT 一致性 diff（BR-4.5-35） =================

    /**
     * 试产 BOM/路线 vs 拟发布量产版逐项比对（内存比对不落表，design D4）。
     * 量产基线 = 最新 PUBLISHED（TRIAL_STAGE 空），无则最新量产草稿；双无 → 空 diff（首发放行留痕）。
     * 返回差异清单（BOM 行：子项/用量/损耗率；路线行：工序/工作中心/工时/良率）。
     */
    private List<String> pvtDiff(String productCode, MrpBom trialBom, MrpRouting trialRouting) {
        List<String> diffs = new ArrayList<>();
        // ---- BOM 基线（拟发布量产版：最新 PUBLISHED 优先，回退最新非试产草稿，双无 → 首发放行）----
        MrpBom baseBom = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                .eq(MrpBom::getParentItemCode, productCode)
                .isNull(MrpBom::getTrialStage)
                .eq(MrpBom::getStatus, MrpBom.ST_PUBLISHED)
                .orderByDesc(MrpBom::getVersionMajor).orderByDesc(MrpBom::getVersionMinor)
                .last("LIMIT 1"));
        if (baseBom == null) {
            baseBom = bomDao.selectOne(new LambdaQueryWrapper<MrpBom>()
                    .eq(MrpBom::getParentItemCode, productCode)
                    .isNull(MrpBom::getTrialStage)
                    .orderByDesc(MrpBom::getVersionMajor).orderByDesc(MrpBom::getVersionMinor)
                    .last("LIMIT 1"));
        }
        if (baseBom != null && !baseBom.getId().equals(trialBom.getId())) {
            Map<String, MrpBomItem> baseRows = bomRows(baseBom.getId());
            Map<String, MrpBomItem> trialRows = bomRows(trialBom.getId());
            for (Map.Entry<String, MrpBomItem> e : trialRows.entrySet()) {
                MrpBomItem t = e.getValue();
                MrpBomItem b = baseRows.get(e.getKey());
                if (b == null) {
                    diffs.add("BOM 新增子项 " + e.getKey() + " 用量 " + t.getQty());
                    continue;
                }
                if (cmp(t.getQty(), b.getQty()) != 0) {
                    diffs.add("BOM 子项 " + e.getKey() + " 用量 "
                            + b.getQty().stripTrailingZeros().toPlainString() + " → "
                            + t.getQty().stripTrailingZeros().toPlainString());
                }
                if (cmp(t.getLossRate(), b.getLossRate()) != 0) {
                    diffs.add("BOM 子项 " + e.getKey() + " 损耗率 "
                            + nvl(b.getLossRate()).stripTrailingZeros().toPlainString() + " → "
                            + nvl(t.getLossRate()).stripTrailingZeros().toPlainString());
                }
            }
            for (String key : baseRows.keySet()) {
                if (!trialRows.containsKey(key)) {
                    diffs.add("BOM 缺失子项 " + key);
                }
            }
        }
        // ---- 路线基线 ----
        MrpRouting baseRouting = routingDao.selectOne(new LambdaQueryWrapper<MrpRouting>()
                .eq(MrpRouting::getItemCode, productCode)
                .isNull(MrpRouting::getTrialStage)
                .eq(MrpRouting::getStatus, MrpRouting.ST_PUBLISHED)
                .orderByDesc(MrpRouting::getVersionMajor).orderByDesc(MrpRouting::getVersionMinor)
                .last("LIMIT 1"));
        if (baseRouting != null && !baseRouting.getId().equals(trialRouting.getId())) {
            Map<Integer, MrpRoutingOp> baseOps = routeRows(baseRouting.getId());
            Map<Integer, MrpRoutingOp> trialOps = routeRows(trialRouting.getId());
            for (Map.Entry<Integer, MrpRoutingOp> e : trialOps.entrySet()) {
                MrpRoutingOp t = e.getValue();
                MrpRoutingOp b = baseOps.get(e.getKey());
                if (b == null) {
                    diffs.add("路线新增工序 seq=" + e.getKey() + " " + t.getOpCode());
                    continue;
                }
                if (!Objects.equals(t.getOpCode(), b.getOpCode())
                        || !Objects.equals(t.getWcCode(), b.getWcCode())) {
                    diffs.add("路线 seq=" + e.getKey() + " 工序/工作中心 "
                            + b.getOpCode() + "@" + b.getWcCode() + " → "
                            + t.getOpCode() + "@" + t.getWcCode());
                }
                if (cmp(t.getStandardYield(), b.getStandardYield()) != 0) {
                    diffs.add("路线 seq=" + e.getKey() + " 标准良率 "
                            + nvl(b.getStandardYield()).stripTrailingZeros().toPlainString() + " → "
                            + nvl(t.getStandardYield()).stripTrailingZeros().toPlainString());
                }
            }
            for (Integer key : baseOps.keySet()) {
                if (!trialOps.containsKey(key)) {
                    diffs.add("路线缺失工序 seq=" + key);
                }
            }
        }
        return diffs;
    }

    /**
     * 差异审批闸口（design D4）：无实例 → 发起并返回 pendingDiff（零工单）；
     * PENDING → 返回 pendingDiff；APPROVED → 放行（返回 null 继续建单）；
     * REJECTED → 重新发起（修订后重试语义，仍零工单）。清单入标题与通知（审批人可见）。
     */
    private Map<String, Object> gatePvtDiff(TrialApply apply, List<String> diffs) {
        // 状态优先级选取（驳回→重发可能同秒建多实例， createDate 排序不稳定——
        // APPROVED > PENDING > 最新 REJECTED，与实例时序无关的确定性判定）
        List<ApprovalInstance> insts = approvalInstanceDao.selectList(
                new LambdaQueryWrapper<ApprovalInstance>()
                        .eq(ApprovalInstance::getBizType, BIZ_TRIAL_PVT_DIFF)
                        .eq(ApprovalInstance::getBizId, apply.getTrialNo())
                        .orderByDesc(ApprovalInstance::getCreateDate));
        ApprovalInstance approved = insts.stream()
                .filter(i -> "APPROVED".equals(i.getStatus())).findFirst().orElse(null);
        if (approved != null) {
            return null; // 审批通过 → 继续建单
        }
        ApprovalInstance pending = insts.stream()
                .filter(i -> "PENDING".equals(i.getStatus())).findFirst().orElse(null);
        if (pending != null) {
            return Map.of("pendingDiff", true, "apprNo", pending.getApprNo(),
                    "trialNo", apply.getTrialNo(), "diffCount", diffs.size());
        }
        // 无实例 或 已驳回（修订后重试 → 重新发起）
        String title = "PVT 一致性差异审批：" + apply.getTrialNo() + "（" + diffs.size() + " 项差异）";
        ApprovalInstance inst = approvalEngine.submit(BIZ_TRIAL_PVT_DIFF, apply.getTrialNo(),
                title.length() > 255 ? title.substring(0, 255) : title, null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PROCESS_MGR", "工艺主管"))));
        // 完整差异清单推工艺主管（标题 255 限制，清单走通知，spec「清单逐行列示」）
        noticeService.push("ROLE_PROCESS_MGR", null,
                "PVT 差异清单（" + apply.getTrialNo() + "）",
                String.join("\n", diffs), "TRIAL_PVT_DIFF", apply.getTrialNo());
        log.info("PVT 差异审批发起：{} 差异 {} 项 apprNo={}（重试语义={}）",
                apply.getTrialNo(), diffs.size(), inst.getApprNo(), !insts.isEmpty());
        return Map.of("pendingDiff", true, "apprNo", inst.getApprNo(),
                "trialNo", apply.getTrialNo(), "diffCount", diffs.size());
    }

    private Map<String, MrpBomItem> bomRows(String bomId) {
        Map<String, MrpBomItem> out = new LinkedHashMap<>();
        for (MrpBomItem row : bomItemDao.selectList(new LambdaQueryWrapper<MrpBomItem>()
                .eq(MrpBomItem::getBomId, bomId))) {
            out.put(row.getItemCode(), row);
        }
        return out;
    }

    private Map<Integer, MrpRoutingOp> routeRows(String routingId) {
        Map<Integer, MrpRoutingOp> out = new LinkedHashMap<>();
        for (MrpRoutingOp row : routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, routingId))) {
            out.put(row.getOpSeq(), row);
        }
        return out;
    }

    private static int cmp(BigDecimal a, BigDecimal b) {
        return nvl(a).compareTo(nvl(b));
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 申请侧写权限（spec trial-order 菜单权限六角色） */
    private void requireWriter(String action) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : new String[]{"ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_PROCESS_ENG",
                "ROLE_PROCESS_MGR", "ROLE_PM", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private String currentOperator() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
