package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.inv.InvTransactionDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.qms.NcrDao;
import com.erp.dao.sd.SdReturnDao;
import com.erp.dao.sd.SdReturnLineDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.inv.InvTransaction;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.qms.Ncr;
import com.erp.entity.sd.SdReturn;
import com.erp.entity.sd.SdReturnLine;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.service.mrp.MoDataSource;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.ReworkOrderService;
import com.erp.service.mrp.RoutingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 返工工单实现（change add-rework-management，spec rework-order）。
 * design D3 三池守恒：转在制 QC→WIP 平移（QTY 不变），冻结行 REF_MO_ID 钉住至入库回调；
 * design D2 编码分池复用 MoService.insertWithMoNo（RW- 前缀）；
 * 阶段推导（D9）由检验批 + 冻结行拼装，状态机零改动。
 */
@Slf4j
@Service
public class ReworkOrderServiceImpl implements ReworkOrderService,
        com.erp.service.qms.LotReleaseListener {

    /** 判定来源类型 */
    public static final String SRC_NCR = "NCR";
    public static final String SRC_RETURN = "RETURN";

    /** 阶段：复检批无合格结论（待检验） */
    public static final String PHASE_PENDING_INSP = "PENDING_INSP";
    /** 阶段：检验合格未入库过账（待入库） */
    public static final String PHASE_PENDING_STOCK_IN = "PENDING_STOCK_IN";

    /** 二次返工审批 BIZ_TYPE（approval-workflow 底座；bizId = 判定单号） */
    public static final String BIZ_REWORK_SECOND = "ReworkSecond";

    /** NCR 处置=返工（qms D_REWORK 同值） */
    private static final String NCR_D_REWORK = "REWORK";
    private static final String NCR_ST_CANCELLED = "CANCELLED";

    private final NcrDao ncrDao;
    private final SdReturnDao returnDao;
    private final SdReturnLineDao returnLineDao;
    private final MrpMoDao moDao;
    private final InvStockDao stockDao;
    private final InvFreezeDao freezeDao;
    private final InvTransactionDao txnDao;
    private final MoService moService;
    private final com.erp.dao.qms.InspectionLotDao lotDao;
    private final RoutingService routingService;
    private final MrpRoutingOpDao routingOpDao;
    private final MrpMoOpDao moOpDao;
    private final MoDataSource moDataSource;
    private final MrpMoBomDao moBomDao;
    private final com.erp.service.qms.InspectionLotService lotService;
    private final com.erp.dao.system.ApprovalInstanceDao approvalInstanceDao;
    private final com.erp.service.approval.ApprovalEngine approvalEngine;
    private final com.erp.service.system.NoticeService noticeService;
    private final com.erp.dao.mrp.MrpFeedDao feedDao;
    private final com.erp.dao.mrp.MrpReportingDao reportingDao;
    private final com.erp.dao.mdm.MdmItemDao mdmItemDao;
    private final com.erp.dao.qms.CopqEntryDao copqEntryDao;
    private final com.erp.service.qms.CopqService copqService;
    private final com.erp.service.SysParamService sysParam;

    public ReworkOrderServiceImpl(NcrDao ncrDao, SdReturnDao returnDao, SdReturnLineDao returnLineDao,
                                  MrpMoDao moDao, InvStockDao stockDao, InvFreezeDao freezeDao,
                                  InvTransactionDao txnDao, MoService moService,
                                  com.erp.dao.qms.InspectionLotDao lotDao,
                                  RoutingService routingService, MrpRoutingOpDao routingOpDao,
                                  MrpMoOpDao moOpDao, MoDataSource moDataSource,
                                  MrpMoBomDao moBomDao,
                                  @org.springframework.context.annotation.Lazy com.erp.service.qms.InspectionLotService lotService,
                                  com.erp.dao.system.ApprovalInstanceDao approvalInstanceDao,
                                  @org.springframework.context.annotation.Lazy com.erp.service.approval.ApprovalEngine approvalEngine,
                                  com.erp.service.system.NoticeService noticeService,
                                  com.erp.dao.mrp.MrpFeedDao feedDao,
                                  com.erp.dao.mrp.MrpReportingDao reportingDao,
                                  com.erp.dao.mdm.MdmItemDao mdmItemDao,
                                  com.erp.dao.qms.CopqEntryDao copqEntryDao,
                                  com.erp.service.qms.CopqService copqService,
                                  com.erp.service.SysParamService sysParam) {
        this.ncrDao = ncrDao;
        this.returnDao = returnDao;
        this.returnLineDao = returnLineDao;
        this.moDao = moDao;
        this.stockDao = stockDao;
        this.freezeDao = freezeDao;
        this.txnDao = txnDao;
        this.moService = moService;
        this.lotDao = lotDao;
        this.routingService = routingService;
        this.routingOpDao = routingOpDao;
        this.moOpDao = moOpDao;
        this.moDataSource = moDataSource;
        this.moBomDao = moBomDao;
        this.lotService = lotService;
        this.approvalInstanceDao = approvalInstanceDao;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
        this.feedDao = feedDao;
        this.reportingDao = reportingDao;
        this.mdmItemDao = mdmItemDao;
        this.copqEntryDao = copqEntryDao;
        this.copqService = copqService;
        this.sysParam = sysParam;
    }

    // ================= 来源候选（spec 判定来源双接入） =================

    @Override
    public List<Map<String, Object>> sourceCandidates(String srcType) {
        requirePlanner("查询返工判定来源");
        List<Map<String, Object>> out = new ArrayList<>();
        if (SRC_NCR.equals(srcType)) {
            List<Ncr> ncrs = ncrDao.selectList(new LambdaQueryWrapper<Ncr>()
                    .eq(Ncr::getDisposition, NCR_D_REWORK)
                    .ne(Ncr::getStatus, NCR_ST_CANCELLED)
                    .orderByDesc(Ncr::getCreateDate));
            for (Ncr n : ncrs) {
                if (linked(n.getNcrNo())) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("srcType", SRC_NCR);
                m.put("srcNo", n.getNcrNo());
                m.put("productCode", n.getItemCode());
                m.put("productName", n.getItemName());
                m.put("batchNo", n.getBatchNo());
                m.put("qty", n.getQty());
                m.put("severity", n.getSeverity());
                out.add(m);
            }
        } else if (SRC_RETURN.equals(srcType)) {
            List<SdReturn> rets = returnDao.selectList(new LambdaQueryWrapper<SdReturn>()
                    .eq(SdReturn::getJudgeHandle, SdReturn.HANDLE_REWORKABLE)
                    .in(SdReturn::getStatus, SdReturn.ST_JUDGED, SdReturn.ST_APPROVING,
                            SdReturn.ST_APPROVED, SdReturn.ST_DONE)
                    .orderByDesc(SdReturn::getCreateDate));
            for (SdReturn r : rets) {
                if (linked(r.getReturnNo())) {
                    continue;
                }
                List<SdReturnLine> lines = linesOf(r.getId());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("srcType", SRC_RETURN);
                m.put("srcNo", r.getReturnNo());
                m.put("productCode", lines.isEmpty() ? null : lines.get(0).getItemCode());
                m.put("productName", lines.isEmpty() ? null : lines.get(0).getItemName());
                m.put("batchNo", lines.isEmpty() ? null : lines.get(0).getStockBatchNo());
                m.put("qty", lines.stream().map(SdReturnLine::getJudgeQty)
                        .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add));
                out.add(m);
            }
        } else {
            throw new ServiceException(422, "判定来源类型仅支持 NCR/RETURN：" + srcType);
        }
        return out;
    }

    /** 同判定单已建返工工单数（回次派生：REWORK_ROUND = built + 1，design D6） */
    private long builtCount(String srcNo) {
        return moDao.selectCount(new LambdaQueryWrapper<MrpMo>()
                .eq(MrpMo::getSrcNo, srcNo));
    }

    /** 候选过滤用（已关联=已建 ≥1） */
    private boolean linked(String srcNo) {
        return builtCount(srcNo) > 0;
    }

    /** 二次返工审批已通过（BIZ_ReworkSecond 实例 APPROVED） */
    private boolean secondApproved(String srcNo) {
        return approvalInstanceDao.selectCount(new LambdaQueryWrapper<com.erp.entity.system.ApprovalInstance>()
                .eq(com.erp.entity.system.ApprovalInstance::getBizType, BIZ_REWORK_SECOND)
                .eq(com.erp.entity.system.ApprovalInstance::getBizId, srcNo)
                .eq(com.erp.entity.system.ApprovalInstance::getStatus, "APPROVED")) > 0;
    }

    // ================= 创建（spec 创建卡控 + 冻结转在制） =================

    @Override
    public MrpMo create(MrpMo head) {
        return create(head, null);
    }

    @Override
    @Transactional
    public MrpMo create(MrpMo head, List<MrpMoBom> addlLines) {
        requirePlanner("创建返工工单");
        if (head == null || !hasText(head.getSrcType()) || !hasText(head.getSrcNo())) {
            throw new ServiceException(422, "判定来源必填（srcType + srcNo）");
        }
        if (head.getQty() == null || head.getQty().signum() <= 0) {
            throw new ServiceException(422, "投入数量必须大于 0");
        }
        String srcType = head.getSrcType().trim();
        String srcNo = head.getSrcNo().trim();

        // ---- 来源解析与资格（spec：不存在或状态不满足返工 → 阻断）----
        String productCode;
        String productName;
        String batchNo;
        BigDecimal srcQty;
        if (SRC_NCR.equals(srcType)) {
            Ncr ncr = ncrDao.selectOne(new LambdaQueryWrapper<Ncr>().eq(Ncr::getNcrNo, srcNo).last("LIMIT 1"));
            if (ncr == null) {
                throw new ServiceException(422, "判定单不存在：" + srcNo);
            }
            if (!NCR_D_REWORK.equals(ncr.getDisposition()) || NCR_ST_CANCELLED.equals(ncr.getStatus())) {
                throw new ServiceException(422, "NCR " + srcNo + " 非可返工状态（处置=返工且未作废方可建单）");
            }
            productCode = ncr.getItemCode();
            productName = ncr.getItemName();
            batchNo = ncr.getBatchNo();
            srcQty = ncr.getQty();
        } else if (SRC_RETURN.equals(srcType)) {
            SdReturn ret = returnDao.selectOne(new LambdaQueryWrapper<SdReturn>()
                    .eq(SdReturn::getReturnNo, srcNo).last("LIMIT 1"));
            if (ret == null) {
                throw new ServiceException(422, "判定单不存在：" + srcNo);
            }
            if (!SdReturn.HANDLE_REWORKABLE.equals(ret.getJudgeHandle())) {
                throw new ServiceException(422, "退货单 " + srcNo + " 判定结论非可返修，不可开立返工工单");
            }
            List<SdReturnLine> lines = linesOf(ret.getId());
            if (hasText(head.getProductCode())) {
                lines = lines.stream().filter(l -> head.getProductCode().equals(l.getItemCode())).toList();
            }
            if (lines.isEmpty()) {
                throw new ServiceException(422, "退货判定单无可返修行：" + srcNo);
            }
            productCode = lines.get(0).getItemCode();
            productName = lines.get(0).getItemName();
            batchNo = lines.get(0).getStockBatchNo();
            srcQty = lines.stream().map(SdReturnLine::getJudgeQty)
                    .filter(java.util.Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
        } else {
            throw new ServiceException(422, "判定来源类型仅支持 NCR/RETURN：" + srcType);
        }
        if (hasText(head.getProductCode()) && !head.getProductCode().equals(productCode)) {
            throw new ServiceException(422, "产品与判定单不一致：" + head.getProductCode() + " ≠ " + productCode);
        }
        String batchKey = batchNo == null ? "" : batchNo;

        // ---- 回次门禁（spec 返工次数与二次返工审批 / BR-4.5-02）----
        long built = builtCount(srcNo);
        if (built >= 2) {
            throw new ServiceException(422,
                    "同批次累计两次返工检验不合格强制报废，不得创建第三次返工工单（BR-4.5-02）");
        }
        if (built == 1 && !secondApproved(srcNo)) {
            throw new ServiceException(422,
                    "二次返工须质量主管审批通过（FR-4.5-7-6），请先在 5.8.2 发起二次返工审批：" + srcNo);
        }
        boolean round2 = built == 1;
        // ---- C-4.5-13：数量 ≤ 判定批准量 ----
        if (head.getQty().compareTo(srcQty) > 0) {
            throw new ServiceException(422, "投入数量 " + head.getQty().toPlainString()
                    + " 超过判定单批准数量 " + srcQty.toPlainString() + "（C-4.5-13）");
        }

        // ---- C-4.5-13：批次状态三查（行锁 + 复核）----
        List<InvFreeze> actives = freezeDao.selectList(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getItemCode, productCode)
                .eq(InvFreeze::getBatchNo, batchKey)
                .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE));
        if (actives.stream().anyMatch(f -> InvFreeze.T_FINANCE.equals(f.getFreezeType()))) {
            throw new ServiceException(422, "投入批次存在财务冻结，须先经财务解冻方可返工（C-4.5-13）");
        }
        // round2：上一轮钉住转移（在制已持有 → 不重复 QC→WIP）；无钉住则回落常规转在制路径
        InvFreeze transferPin = null;
        if (round2) {
            List<String> priorIds = moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                            .eq(MrpMo::getSrcNo, srcNo))
                    .stream().map(MrpMo::getId).toList();
            if (!priorIds.isEmpty()) {
                transferPin = freezeDao.selectOne(new LambdaQueryWrapper<InvFreeze>()
                        .in(InvFreeze::getRefMoId, priorIds)
                        .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE)
                        .last("LIMIT 1"));
            }
        }
        InvStock stock = null;
        if (transferPin == null) {
            stock = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getItemCode, productCode)
                    .eq(InvStock::getBatchNo, batchKey)
                    .last("FOR UPDATE"));
            boolean qualityFrozen = actives.stream().anyMatch(f -> InvFreeze.T_QUALITY.equals(f.getFreezeType()));
            boolean pending = stock != null && nvl(stock.getQcQty()).signum() > 0;
            if (!qualityFrozen && !pending) {
                throw new ServiceException(422, "投入批次非质量冻结/待处理状态（C-4.5-13），请核对 NCR/判定单");
            }
            if (stock == null || nvl(stock.getQcQty()).compareTo(head.getQty()) < 0) {
                throw new ServiceException(422, "批次待检/冻结数量不足投入量（需 "
                        + head.getQty().toPlainString() + "，当前 "
                        + (stock == null ? "0" : nvl(stock.getQcQty()).toPlainString()) + "）");
            }
        }

        // ---- 选路（FR-4.5-7-3：须同产品已发布 REWORK 路线，无则先编制审核）----
        Map<String, Object> route = routingService.publishedRoute(productCode, MrpRouting.T_REWORK);
        if (route == null || route.get("id") == null) {
            throw new ServiceException(422,
                    "产品无已发布返工路线（FR-4.5-7-3），请工艺工程师编制返工路线并审核发布后使用");
        }
        List<MrpRoutingOp> routeOps = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getRoutingId, route.get("id"))
                .orderByAsc(MrpRoutingOp::getOpSeq));
        if (routeOps.isEmpty()) {
            throw new ServiceException(422, "返工路线无工序行：" + route.get("id"));
        }

        // ---- 插入 RW 工单（PLANNED，走既有审批/释放链）----
        MrpMo mo = new MrpMo();
        mo.setMoType(MrpMo.MO_TYPE_RW);
        mo.setSrcType(srcType);
        mo.setSrcNo(srcNo);
        mo.setSrcQty(srcQty);
        mo.setProductCode(productCode);
        mo.setProductName(productName);
        mo.setQty(head.getQty());
        mo.setPlanStartDate(head.getPlanStartDate() == null ? LocalDate.now() : head.getPlanStartDate());
        mo.setPlanEndDate(head.getPlanEndDate() == null ? LocalDate.now().plusDays(7) : head.getPlanEndDate());
        mo.setPriority(head.getPriority() == null ? 5 : head.getPriority());
        mo.setStatus(MrpMo.ST_PLANNED);
        mo.setShortageFlag("0");
        mo.setSourceRoutingId((String) route.get("id"));
        moService.insertWithMoNo(mo);

        // ---- 工序快照（spec「选路带出快照」：工时/标准良率/关键标记随快照，改版不影响在制）----
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
                com.erp.entity.mrp.MrpOpWcStandard std =
                        moDataSource.standardOf(op.getOpCode(), op.getWcCode());
                if (std != null) {
                    row.setSetupHours(std.getSetupHours());
                    row.setRunHours(std.getRunHours());
                    row.setWaitHours(std.getWaitHours());
                    row.setMoveHours(std.getMoveHours());
                }
            }
            moOpDao.insert(row);
        }

        // ---- 转在制过账（FR-4.5-7-4：QC→WIP 平移，冻结行钉住不释放；round2=轮次转移不动账）----
        BigDecimal n = head.getQty();
        BigDecimal qcBefore;
        if (transferPin != null) {
            qcBefore = BigDecimal.ZERO;
            // 轮次转移：在制归属 上一轮工单 → 本轮（库存账不动——在制量已由上一轮转入）
            freezeDao.update(null, new LambdaUpdateWrapper<InvFreeze>()
                    .eq(InvFreeze::getId, transferPin.getId())
                    .set(InvFreeze::getRefMoId, mo.getId()));
            log.info("返工轮次转移 SRC={}：钉住 {} → {}（{} 在制账不动）", srcNo,
                    transferPin.getRefMoId(), mo.getId(), srcNo);
        } else {
            qcBefore = nvl(stock.getQcQty());
            stock.setQcQty(qcBefore.subtract(n));
            stock.setWipQty(nvl(stock.getWipQty()).add(n));
            stockDao.updateById(stock);
            InvFreeze pin = actives.stream()
                    .filter(f -> InvFreeze.T_QUALITY.equals(f.getFreezeType()))
                    .findFirst().orElse(null);
            if (pin != null) {
                // 钉住 = REF_MO_ID 非空；STATUS 保持 ACTIVE（「不解除冻结标记」）
                freezeDao.update(null, new LambdaUpdateWrapper<InvFreeze>()
                        .eq(InvFreeze::getId, pin.getId())
                        .set(InvFreeze::getRefMoId, mo.getId()));
            }
        }
        // ---- 返工 BOM 快照（BR-4.5-32：恒含 1 行 INPUT=不合格品自身投入；ADDL 行=追加清单，可为空）----
        int bomLineNo = 0;
        MrpMoBom inputRow = new MrpMoBom();
        inputRow.setMoId(mo.getId());
        inputRow.setLineNo(++bomLineNo);
        inputRow.setTreeLevel(1);
        inputRow.setItemCode(productCode);
        inputRow.setItemName(productName);
        inputRow.setUnitQty(BigDecimal.ONE);
        inputRow.setLossRate(BigDecimal.ZERO);
        inputRow.setLineType(MrpMoBom.LINE_INPUT);
        moBomDao.insert(inputRow);
        if (addlLines != null) {
            for (MrpMoBom l : addlLines) {
                if (l == null || !hasText(l.getItemCode())) {
                    continue;
                }
                MrpMoBom row = new MrpMoBom();
                row.setMoId(mo.getId());
                row.setLineNo(++bomLineNo);
                row.setTreeLevel(1);
                row.setItemCode(l.getItemCode().trim());
                row.setItemName(l.getItemName());
                row.setUnitQty(l.getUnitQty() == null ? BigDecimal.ONE : l.getUnitQty());
                row.setLossRate(l.getLossRate() == null ? BigDecimal.ZERO : l.getLossRate());
                row.setBaseUnit(l.getBaseUnit());
                row.setLineType(MrpMoBom.LINE_ADDL);
                moBomDao.insert(row);
            }
        }

        if (stock != null) {
            writeTxn(mo.getMoNo(), productCode, batchKey, n, qcBefore, stock.getQcQty());
            log.info("返工工单 {} 创建：来源 {} {} 投入 {} 批次 {}，转在制 QC {}→{} WIP={}",
                    mo.getMoNo(), srcType, srcNo, n, batchKey,
                    qcBefore, stock.getQcQty(), stock.getWipQty());
        } else {
            log.info("返工工单 {} 创建：来源 {} {} 投入 {} 批次 {}，轮次转移（在制账不动）",
                    mo.getMoNo(), srcType, srcNo, n, batchKey);
        }
        return mo;
    }

    @Override
    public List<MrpMo> list(String status, String productCode) {
        requirePlannerRead("查询返工工单");
        return moService.listByType(status, productCode, MrpMo.MO_TYPE_RW);
    }

    // ================= 阶段推导（spec 生命周期阶段推导，design D9） =================

    @Override
    public String phaseOf(String moId) {
        requirePlannerRead("查询返工阶段");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(422, "工单不存在：" + moId);
        }
        if (!MrpMo.MO_TYPE_RW.equals(mo.getMoType()) || !MrpMo.ST_COMPLETED.equals(mo.getStatus())) {
            return mo.getStatus();
        }
        Long passed = lotDao.selectCount(new LambdaQueryWrapper<com.erp.entity.qms.InspectionLot>()
                .eq(com.erp.entity.qms.InspectionLot::getRefType, "WORKORDER")
                .eq(com.erp.entity.qms.InspectionLot::getRefId, moId + "#REWORK")
                .eq(com.erp.entity.qms.InspectionLot::getResult, "PASS"));
        if (passed == null || passed == 0) {
            return PHASE_PENDING_INSP;
        }
        Long pinned = freezeDao.selectCount(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getRefMoId, moId)
                .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE));
        return (pinned != null && pinned > 0) ? PHASE_PENDING_STOCK_IN : MrpMo.ST_CLOSED;
    }

    // ================= 完工触发复检（spec 完工触发复检加严） =================

    @Override
    public com.erp.entity.qms.InspectionLot onCompleted(MrpMo mo, java.math.BigDecimal qualified) {
        // 建批是系统动作（不设入口角色门——调用方 completeMo 已鉴权）
        String batchNo = null;
        if (SRC_NCR.equals(mo.getSrcType())) {
            Ncr ncr = ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                    .eq(Ncr::getNcrNo, mo.getSrcNo()).last("LIMIT 1"));
            batchNo = ncr == null ? null : ncr.getBatchNo();
        } else if (SRC_RETURN.equals(mo.getSrcType())) {
            List<SdReturnLine> lines = linesOf0(mo.getSrcNo());
            batchNo = lines.isEmpty() ? null : lines.get(0).getStockBatchNo();
        }
        return lotService.createReworkRecheck(mo.getId(), mo.getProductCode(),
                mo.getProductName(), batchNo, qualified == null ? java.math.BigDecimal.ZERO : qualified);
    }

    private List<SdReturnLine> linesOf0(String returnNo) {
        SdReturn ret = returnDao.selectOne(new LambdaQueryWrapper<SdReturn>()
                .eq(SdReturn::getReturnNo, returnNo).last("LIMIT 1"));
        return ret == null ? List.of() : linesOf(ret.getId());
    }

    // ================= 二次返工审批与强制报废（spec 返工次数与二次返工审批） =================

    @Override
    public Map<String, Object> applySecond(String srcNo) {
        requireQuality("发起二次返工审批");
        if (!hasText(srcNo)) {
            throw new ServiceException(422, "判定单号必填");
        }
        if (builtCount(srcNo) == 0) {
            throw new ServiceException(422, "该判定单尚无返工工单，无需二次返工审批：" + srcNo);
        }
        if (secondApproved(srcNo)) {
            return Map.of("already", true, "srcNo", srcNo);
        }
        // 幂等：已有 PENDING 实例直接返回
        com.erp.entity.system.ApprovalInstance pending =
                approvalInstanceDao.selectOne(new LambdaQueryWrapper<com.erp.entity.system.ApprovalInstance>()
                        .eq(com.erp.entity.system.ApprovalInstance::getBizType, BIZ_REWORK_SECOND)
                        .eq(com.erp.entity.system.ApprovalInstance::getBizId, srcNo)
                        .eq(com.erp.entity.system.ApprovalInstance::getStatus, "PENDING")
                        .last("LIMIT 1"));
        if (pending != null) {
            return Map.of("apprNo", pending.getApprNo(), "pending", true, "srcNo", srcNo);
        }
        com.erp.entity.system.ApprovalInstance inst = approvalEngine.submit(
                BIZ_REWORK_SECOND, srcNo, "二次返工审批：" + srcNo, null,
                java.util.List.of(java.util.List.of(
                        com.erp.service.approval.ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "质量主管"))));
        log.info("二次返工审批发起 {} apprNo={}", srcNo, inst.getApprNo());
        return Map.of("apprNo", inst.getApprNo(), "pending", true, "srcNo", srcNo);
    }

    @Override
    @Transactional
    public void enforceScrap(String moId) {
        requireQuality("执行强制报废");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_RW.equals(mo.getMoType())) {
            throw new ServiceException(422, "返工工单不存在：" + moId);
        }
        // 当前轮次 = 在制钉住持有者（轮次转移后旧轮不再持有—— createDate 秒级同刻不可靠，用钉住判定）
        InvFreeze pin = freezeDao.selectOne(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getRefMoId, moId)
                .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE)
                .last("LIMIT 1"));
        if (pin == null) {
            throw new ServiceException(422,
                    "该工单非当前返工轮次（在制钉住已转移或已释放），不可强制报废（BR-4.5-02）：" + moId);
        }
        // 同判定单须已有第 1 张（本单为第 2 轮）+ 复检已判不合格
        long family = builtCount(mo.getSrcNo());
        if (family < 2) {
            throw new ServiceException(422, "仅累计第二次返工检验不合格可强制报废（BR-4.5-02）");
        }
        Long failed = lotDao.selectCount(new LambdaQueryWrapper<com.erp.entity.qms.InspectionLot>()
                .eq(com.erp.entity.qms.InspectionLot::getRefType, "WORKORDER")
                .eq(com.erp.entity.qms.InspectionLot::getRefId, moId + "#REWORK")
                .eq(com.erp.entity.qms.InspectionLot::getResult, "FAIL"));
        if (failed == null || failed == 0) {
            throw new ServiceException(422, "复检批未判不合格，不可强制报废：" + moId);
        }
        String itemCode = mo.getProductCode();
        String batchKey = pin == null ? "" : (pin.getBatchNo() == null ? "" : pin.getBatchNo());
        InvStock stock = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, itemCode)
                .eq(InvStock::getBatchNo, batchKey)
                .last("FOR UPDATE"));
        BigDecimal qty = nvl(mo.getQty());
        if (stock == null || nvl(stock.getWipQty()).compareTo(qty) < 0) {
            // 回落：任一在制 > 0 的行
            stock = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getItemCode, itemCode)
                    .gt(InvStock::getWipQty, 0)
                    .last("FOR UPDATE"));
        }
        if (stock == null || nvl(stock.getWipQty()).compareTo(qty) < 0) {
            throw new ServiceException(422, "在制池数量不足报废（需 " + qty.toPlainString()
                    + "，当前 " + (stock == null ? "0" : nvl(stock.getWipQty()).toPlainString()) + "）");
        }
        BigDecimal wipBefore = nvl(stock.getWipQty());
        BigDecimal totalBefore = nvl(stock.getQty());
        stock.setWipQty(wipBefore.subtract(qty));
        stock.setQty(totalBefore.subtract(qty)); // 报废出库：总量出账（QTY=WIP 同减守恒）
        stockDao.updateById(stock);
        // 冻结行释放（报废终局——「入库与解冻」的报废分支）
        if (pin != null) {
            freezeDao.update(null, new LambdaUpdateWrapper<InvFreeze>()
                    .eq(InvFreeze::getId, pin.getId())
                    .set(InvFreeze::getStatus, InvFreeze.ST_RELEASED)
                    .set(InvFreeze::getRefMoId, null)
                    .set(InvFreeze::getReleasedBy, currentOperator())
                    .set(InvFreeze::getReleaseResult, "二次返工检验不合格强制报废（BR-4.5-02）"));
        }
        // 报废凭证流水（类型变更记录，spec「生成报废凭证」——凭证=REWORK_SCRAP 流水行）
        writeTxn(mo.getMoNo(), itemCode, batchKey, qty, wipBefore.subtract(qty),
                InvTransaction.DIR_OUT, "SCRAP"); // bizDocType=REWORK_SCRAP
        // 工单关闭终局（先出账在制→close 预检在制余额=0 通过；ops 已 DONE——复检批在完工后才存在）
        moService.close(moId);
        noticeService.push("ROLE_QUALITY_MGR", null,
                "返工强制报废执行", "返工工单 " + mo.getMoNo() + " 累计两次返工检验不合格，"
                        + "已强制报废出账 " + qty.toPlainString() + "（BR-4.5-02）",
                "REWORK_SCRAP", moId);
        noticeService.push("ROLE_PLANNER", null,
                "返工强制报废执行", "返工工单 " + mo.getMoNo() + " 已强制报废关闭，判定单 "
                        + mo.getSrcNo() + " 后续不可再开返工工单",
                "REWORK_SCRAP", moId);
        log.info("返工强制报废 {} 数量={} 批次={} by {}", mo.getMoNo(), qty, batchKey, currentOperator());
    }

    /** 质量侧权限（spec 5.8.2：QUALITY_ENG/QUALITY_MGR/ADMIN） */
    private void requireQuality(String action) {
        requireAnyRole(action, "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR", "ROLE_ADMIN");
    }

    private String currentOperator() {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    // ================= 返工成本归集（spec 返工成本归集与经济性） =================

    @Override
    public Map<String, Object> aggregateCost(MrpMo mo, java.math.BigDecimal qualified) {
        // 系统动作（完工链/冲销链调用，鉴权在调用方）
        if (mo == null || !MrpMo.MO_TYPE_RW.equals(mo.getMoType())) {
            throw new ServiceException(422, "仅返工工单可归集返工成本");
        }
        // ---- 材料：投料行（未回加）× 物料标准成本（缺成本列值→按 0 计并留痕，偏差 D3）----
        List<com.erp.entity.mrp.MrpFeed> feeds = feedDao.selectList(new LambdaQueryWrapper<com.erp.entity.mrp.MrpFeed>()
                .eq(com.erp.entity.mrp.MrpFeed::getMoId, mo.getId())
                .eq(com.erp.entity.mrp.MrpFeed::getReturnedFlag, "0"));
        java.util.Set<String> codes = feeds.stream().map(com.erp.entity.mrp.MrpFeed::getItemCode)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        java.util.Map<String, java.math.BigDecimal> costByItem = new java.util.HashMap<>();
        if (!codes.isEmpty()) {
            for (com.erp.entity.mdm.MdmItem it : mdmItemDao.selectList(
                    new LambdaQueryWrapper<com.erp.entity.mdm.MdmItem>()
                            .in(com.erp.entity.mdm.MdmItem::getItemCode, codes))) {
                if (it.getStandardCost() != null) {
                    costByItem.put(it.getItemCode(), it.getStandardCost());
                }
            }
        }
        java.math.BigDecimal material = java.math.BigDecimal.ZERO;
        boolean costMissing = false;
        for (com.erp.entity.mrp.MrpFeed f : feeds) {
            java.math.BigDecimal c = costByItem.get(f.getItemCode());
            if (c == null) {
                costMissing = true;
                continue;
            }
            material = material.add(nvl(f.getQty()).multiply(c));
        }

        // ---- 工时：CONFIRMED 报工单分项 × 费率（WC 无费率列 → sys_param 兜底，design D3）----
        List<com.erp.entity.mrp.MrpReporting> rpts = reportingDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.mrp.MrpReporting>()
                        .eq(com.erp.entity.mrp.MrpReporting::getMoId, mo.getId())
                        .eq(com.erp.entity.mrp.MrpReporting::getStatus,
                                com.erp.entity.mrp.MrpReporting.ST_CONFIRMED));
        java.math.BigDecimal setup = java.math.BigDecimal.ZERO;
        java.math.BigDecimal run = java.math.BigDecimal.ZERO;
        java.math.BigDecimal machine = java.math.BigDecimal.ZERO;
        for (com.erp.entity.mrp.MrpReporting r : rpts) {
            setup = setup.add(nvl(r.getSetupHours()));
            run = run.add(nvl(r.getRunHours()));
            machine = machine.add(nvl(r.getMachineHours()));
        }
        java.math.BigDecimal laborRate = param("REWORK_LABOR_RATE", "60");
        java.math.BigDecimal machineRate = param("REWORK_MACHINE_RATE", "100");
        java.math.BigDecimal labor = setup.add(run).multiply(laborRate);
        java.math.BigDecimal machineCost = machine.multiply(machineRate);
        java.math.BigDecimal total = material.add(labor).multiply(java.math.BigDecimal.ONE)
                .add(java.math.BigDecimal.ZERO).add(machineCost); // 材料+人工+设备
        total = material.add(labor).add(machineCost);

        // ---- 重置成本基准（STANDARD=物料标准成本 × 投入数量）与单位成本留痕 ----
        com.erp.entity.mdm.MdmItem product = mdmItemDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.mdm.MdmItem>()
                        .eq(com.erp.entity.mdm.MdmItem::getItemCode, mo.getProductCode()));
        java.math.BigDecimal originalUnit = product == null ? null : product.getStandardCost();
        java.math.BigDecimal qualifiedQty = qualified == null ? java.math.BigDecimal.ZERO : qualified;
        java.math.BigDecimal unitAfter = null;
        if (originalUnit != null) {
            java.math.BigDecimal addUnit = qualifiedQty.signum() > 0
                    ? total.divide(qualifiedQty, 4, java.math.RoundingMode.HALF_UP)
                    : java.math.BigDecimal.ZERO;
            unitAfter = originalUnit.add(addUnit);
        }

        // ---- 重建式挂 COPQ 内部失败（挂原判定单；remark 前缀 REWORK: 隔离 NCR 其他归集）----
        String copqSourceType;
        String copqSourceId;
        String ncrId = null;
        if (SRC_NCR.equals(mo.getSrcType())) {
            Ncr ncr = ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                    .eq(Ncr::getNcrNo, mo.getSrcNo()).last("LIMIT 1"));
            if (ncr == null) {
                throw new ServiceException(422, "归集失败：判定 NCR 不存在 " + mo.getSrcNo());
            }
            copqSourceType = "NCR";
            copqSourceId = ncr.getId();
            ncrId = ncr.getId();
        } else {
            SdReturn ret = returnDao.selectOne(new LambdaQueryWrapper<SdReturn>()
                    .eq(SdReturn::getReturnNo, mo.getSrcNo()).last("LIMIT 1"));
            if (ret == null) {
                throw new ServiceException(422, "归集失败：退货单不存在 " + mo.getSrcNo());
            }
            copqSourceType = "RETURN";
            copqSourceId = ret.getId();
        }
        // 重建：删旧 REWORK 归集行 → 重录（冲销回拨=再次调用即按当前累计重算）
        copqEntryDao.delete(new LambdaQueryWrapper<com.erp.entity.qms.CopqEntry>()
                .eq(com.erp.entity.qms.CopqEntry::getSourceType, copqSourceType)
                .eq(com.erp.entity.qms.CopqEntry::getSourceId, copqSourceId)
                .eq(com.erp.entity.qms.CopqEntry::getCategory, "INTERNAL_FAILURE")
                .likeRight(com.erp.entity.qms.CopqEntry::getRemark, "REWORK:"));
        if (total.signum() > 0) {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("category", "INTERNAL_FAILURE");
            body.put("sourceType", copqSourceType);
            body.put("sourceId", copqSourceId);
            body.put("sourceNo", mo.getMoNo());
            body.put("ncrId", ncrId);
            body.put("itemCode", mo.getProductCode());
            body.put("amount", total);
            body.put("remark", "REWORK:" + mo.getMoNo()
                    + (unitAfter != null ? " 单位成本=" + unitAfter.stripTrailingZeros().toPlainString() : "")
                    + (costMissing ? "（部分物料缺标准成本按 0 计，偏差 D3）" : ""));
            copqService.record(body);
        }

        // ---- 经济性提示（归集额 > 重置成本 → 通知质量主管，提示不阻断；Notice 幂等含 bizId）----
        if (originalUnit != null) {
            java.math.BigDecimal reset = originalUnit.multiply(nvl(mo.getQty()));
            if (total.compareTo(reset) > 0) {
                noticeService.push("ROLE_QUALITY_MGR", null,
                        "返工经济性评估提示", "返工工单 " + mo.getMoNo() + " 归集额 "
                                + total.stripTrailingZeros().toPlainString()
                                + " 已超产品重置成本 " + reset.stripTrailingZeros().toPlainString()
                                + "，请评估返工经济性（FR-4.5-7-7）",
                        "REWORK_ECON", mo.getId());
            }
        }

        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("materialCost", material);
        out.put("laborCost", labor);
        out.put("machineCost", machineCost);
        out.put("totalCost", total);
        out.put("unitCostAfter", unitAfter);
        log.info("返工成本归集 {} 料={} 工={} 费={} 合计={} 单位成本={}",
                mo.getMoNo(), material, labor, machineCost, total, unitAfter);
        return out;
    }

    private java.math.BigDecimal param(String key, String def) {
        try {
            return new java.math.BigDecimal(sysParam.getValue(key, def));
        } catch (Exception e) {
            return new java.math.BigDecimal(def);
        }
    }

    // ================= 复检合格入库解冻关单（spec 复检合格入库解冻关单 FR-4.5-7-8） =================

    @Override
    public void onReleased(com.erp.entity.qms.InspectionLot lot) {
        // 仅返工复检批（refId=<moId>#REWORK 且合格放行）触发闭环；其他批忽略
        if (lot == null || !"WORKORDER".equals(lot.getRefType())
                || lot.getRefId() == null || !lot.getRefId().endsWith("#REWORK")) {
            return;
        }
        if (!"PASS".equals(lot.getResult()) && !"RELEASED".equals(lot.getStatus())) {
            return;
        }
        closeFromInspection(lot.getRefId().substring(0, lot.getRefId().length() - "#REWORK".length()));
    }

    @Override
    @Transactional
    public void closeFromInspection(String moId) {
        requireAnyRole("返工入库解冻关单", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG",
                "ROLE_QUALITY_MGR", "ROLE_PLANNER", "ROLE_ADMIN");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_RW.equals(mo.getMoType())) {
            throw new ServiceException(422, "返工工单不存在：" + moId);
        }
        // 幂等锚点：无活动钉住 = 已闭环或已释放
        InvFreeze pin = freezeDao.selectOne(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getRefMoId, moId)
                .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE)
                .last("LIMIT 1"));
        if (pin == null) {
            if (MrpMo.ST_CLOSED.equals(mo.getStatus())) {
                log.info("返工入库闭环跳过（已关闭，幂等） {}", mo.getMoNo());
                return;
            }
            // spec「冻结未解除阻断入库」：工单未闭环且不存在本单钉住的活动冻结 → 状态异常阻断
            throw new ServiceException(422, "入库过账被阻断：不存在本单钉住的活动冻结行"
                    + "（冻结状态异常，请核对原质量冻结——FR-4.5-7-8）：" + mo.getMoNo());
        }
        String batchKey = pin.getBatchNo() == null ? "" : pin.getBatchNo();
        BigDecimal qty = nvl(mo.getQty());
        InvStock stock = stockDao.selectOne(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, mo.getProductCode())
                .eq(InvStock::getBatchNo, batchKey)
                .last("FOR UPDATE"));
        if (stock == null || nvl(stock.getWipQty()).compareTo(qty) < 0) {
            throw new ServiceException(422, "入库过账失败：在制池数量不足（需 " + qty.toPlainString()
                    + "，当前 " + (stock == null ? "0" : nvl(stock.getWipQty()).toPlainString()) + "）");
        }
        // ① 合格产出入可用库存 + ② 在制扣减（QTY 不变，池间平移）
        stock.setWipQty(nvl(stock.getWipQty()).subtract(qty));
        stock.setAvailableQty(nvl(stock.getAvailableQty()).add(qty));
        stockDao.updateById(stock);
        // ③ 冻结行释放（「原质量冻结自动解除」+ 解冻执行记录）
        freezeDao.update(null, new LambdaUpdateWrapper<InvFreeze>()
                .eq(InvFreeze::getId, pin.getId())
                .set(InvFreeze::getStatus, InvFreeze.ST_RELEASED)
                .set(InvFreeze::getRefMoId, null)
                .set(InvFreeze::getReleasedBy, currentOperator())
                .set(InvFreeze::getReleaseResult, "返工复检合格入库（FR-4.5-7-8）"));
        // 库存状态变更流水（WIP→AVAILABLE 平移留痕）
        writeTxn(mo.getMoNo(), mo.getProductCode(), batchKey, qty,
                nvl(stock.getWipQty()), InvTransaction.DIR_IN, "STOCK_IN");
        // ④ 返工工单关闭终局
        if (MrpMo.ST_COMPLETED.equals(mo.getStatus())) {
            moService.close(moId);
        }
        log.info("返工入库闭环 {}：WIP→可用 {} 批次 {} 冻结 RELEASED 工单 CLOSED",
                mo.getMoNo(), qty, batchKey);
    }

    @Override
    public Map<String, Object> costOf(String moId) {
        requirePlannerRead("查询返工成本");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.MO_TYPE_RW.equals(mo.getMoType())) {
            throw new ServiceException(422, "返工工单不存在：" + moId);
        }
        String copqSourceType = "NCR".equals(mo.getSrcType()) ? "NCR" : "RETURN";
        String ncrId = null;
        if ("NCR".equals(mo.getSrcType())) {
            Ncr ncr = ncrDao.selectOne(new LambdaQueryWrapper<Ncr>()
                    .eq(Ncr::getNcrNo, mo.getSrcNo()).last("LIMIT 1"));
            ncrId = ncr == null ? null : ncr.getId();
            if (ncrId == null) {
                return Map.of();
            }
        } else {
            SdReturn ret = returnDao.selectOne(new LambdaQueryWrapper<SdReturn>()
                    .eq(SdReturn::getReturnNo, mo.getSrcNo()).last("LIMIT 1"));
            if (ret == null) {
                return Map.of();
            }
            String rid = ret.getId();
            com.erp.entity.qms.CopqEntry row = copqEntryDao.selectOne(
                    new LambdaQueryWrapper<com.erp.entity.qms.CopqEntry>()
                            .eq(com.erp.entity.qms.CopqEntry::getSourceType, copqSourceType)
                            .eq(com.erp.entity.qms.CopqEntry::getSourceId, rid)
                            .eq(com.erp.entity.qms.CopqEntry::getCategory, "INTERNAL_FAILURE")
                            .likeRight(com.erp.entity.qms.CopqEntry::getRemark,
                                    "REWORK:" + mo.getMoNo())
                            .last("LIMIT 1"));
            return row == null ? Map.of() : Map.of(
                    "amount", row.getAmount(), "remark", row.getRemark(),
                    "status", row.getStatus(), "sourceNo", mo.getMoNo());
        }
        com.erp.entity.qms.CopqEntry row = copqEntryDao.selectOne(
                new LambdaQueryWrapper<com.erp.entity.qms.CopqEntry>()
                        .eq(com.erp.entity.qms.CopqEntry::getSourceType, copqSourceType)
                        .eq(com.erp.entity.qms.CopqEntry::getSourceId, ncrId)
                        .eq(com.erp.entity.qms.CopqEntry::getCategory, "INTERNAL_FAILURE")
                        .likeRight(com.erp.entity.qms.CopqEntry::getRemark,
                                "REWORK:" + mo.getMoNo())
                        .last("LIMIT 1"));
        return row == null ? Map.of() : Map.of(
                "amount", row.getAmount(), "remark", row.getRemark(),
                "status", row.getStatus(), "sourceNo", mo.getMoNo());
    }

    // ================= 私有 =================

    /** 报废/转移流水（type+direction 可变） */
    private void writeTxn(String moNo, String itemCode, String batchNo, BigDecimal qty,
                          BigDecimal after, String direction, String typeCode) {
        String prefix = "TX" + java.time.format.DateTimeFormatter.ofPattern("yyMMdd")
                .format(java.time.LocalDate.now()) + "-";
        long base = txnDao.maxSeqByPrefix(prefix);
        for (int attempt = 0; attempt < 5; attempt++) {
            InvTransaction t = new InvTransaction();
            t.setTxnNo(prefix + String.format("%06d", base + 1 + attempt));
            t.setDirection(direction);
            t.setTypeCode(typeCode);
            t.setBizDocType("REWORK_" + typeCode);
            t.setBizDocNo(moNo);
            t.setWarehouseCode(InvStock.DEFAULT_WH);
            t.setItemCode(itemCode);
            t.setBatchNo(batchNo == null ? "" : batchNo);
            t.setBinCode("");
            t.setQty(qty);
            t.setBeforeQty(after.add(qty));
            t.setAfterQty(after);
            t.setRemark(typeCode + "（" + moNo + "）");
            try {
                txnDao.insert(t);
                return;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                base++;
            }
        }
        throw new ServiceException(409, "库存流水号生成冲突，请重试");
    }

    /** 库存状态变更流水留痕（FR-4.5-7-4「库存状态变更记录」；TX+yyMMdd+6 位撞号重试） */
    private void writeTxn(String moNo, String itemCode, String batchNo,
                          BigDecimal qty, BigDecimal qcBefore, BigDecimal qcAfter) {
        String prefix = "TX" + java.time.format.DateTimeFormatter.ofPattern("yyMMdd")
                .format(java.time.LocalDate.now()) + "-";
        long base = txnDao.maxSeqByPrefix(prefix);
        for (int attempt = 0; attempt < 5; attempt++) {
            InvTransaction t = new InvTransaction();
            t.setTxnNo(prefix + String.format("%06d", base + 1 + attempt));
            t.setDirection(InvTransaction.DIR_OUT);
            t.setTypeCode("WIP_TRANSFER");
            t.setBizDocType("REWORK_WIP");
            t.setBizDocNo(moNo);
            t.setWarehouseCode(InvStock.DEFAULT_WH);
            t.setItemCode(itemCode);
            t.setBatchNo(batchNo == null ? "" : batchNo);
            t.setBinCode("");
            t.setQty(qty);
            t.setBeforeQty(qcBefore);
            t.setAfterQty(qcAfter);
            t.setRemark("返工转在制 QC→WIP（" + moNo + "）");
            try {
                txnDao.insert(t);
                return;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                base++;
            }
        }
        throw new ServiceException(409, "库存流水号生成冲突，请重试");
    }

    private List<SdReturnLine> linesOf(String returnId) {
        return returnLineDao.selectList(new LambdaQueryWrapper<SdReturnLine>()
                .eq(SdReturnLine::getReturnId, returnId)
                .orderByAsc(SdReturnLine::getLineNo));
    }

    /** 5.8.1 计划侧写权限（spec 角色：PLANNER/PLAN_MGR/ADMIN） */
    private void requirePlanner(String action) {
        requireAnyRole(action, "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_ADMIN");
    }

    /** 阶段/列表读：计划侧 + 质量侧（两页互读，spec 关联跳转只读） */
    private void requirePlannerRead(String action) {
        requireAnyRole(action, "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_ADMIN",
                "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
    }

    private void requireAnyRole(String action, String... allowed) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder
                        .getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : allowed) {
            if (auth.getAuthorities().contains(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action + "（需 " + String.join("/", allowed) + "）");
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
