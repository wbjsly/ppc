package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpFeedDao;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpOutputDao;
import com.erp.dao.mrp.MrpReportingDao;
import com.erp.dao.vmi.MaterialIssueDao;
import com.erp.dao.vmi.MaterialIssueLineDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpFeed;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpOutput;
import com.erp.entity.mrp.MrpReporting;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.system.ApprovalInstance;
import com.erp.dao.qms.InspectionLotDao;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.MoFreezeService;
import com.erp.service.mrp.MoStatusRules;
import com.erp.service.mrp.ReportingService;
import com.erp.service.qms.InspectionLotService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 车间报工实现（change add-reporting-management，spec shop-floor-reporting ADDED 九需求）。
 * 状态机 CAS：OPEN → CONFIRMED → REVERSED（冲销由 2.6 审批回调推进）；
 * 确认后统一走 {@link #requireOpen} 守卫（投料/产出/工时共用，BR-4.5-25 只读）。
 * 口径预留：首件/IPQC/超产/工时异常卡控在提交链前段插入（3.6/3.7/2.4/2.5）。
 */
@Slf4j
@Service
public class ReportingServiceImpl implements ReportingService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 超产确认审批（FR-4.5-5-4 分级——超阈值挂 PLAN_MGR 签） */
    public static final String BIZ_OVERPROD = "OverProd";
    /** 报工冲销审批（BR-4.5-25——整单红字，PLAN_MGR 单签） */
    public static final String BIZ_REVERSAL = "ReportReversal";

    private final MrpReportingDao reportingDao;
    private final MrpDispatchDao dispatchDao;
    private final MrpOutputDao outputDao;
    private final MrpFeedDao feedDao;
    private final InvStockDao stockDao;
    private final MrpMoDao moDao;
    private final MrpMoOpDao moOpDao;
    private final SysParamService sysParam;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;
    /** @Lazy 断环：ReportingService → MoFreezeService → StatsService → ReportingService */
    private final MoFreezeService moFreezeService;
    /** 分级 NCR 承接（NcrServiceImpl → MoFreezeService 为 @Lazy，环在彼侧已断） */
    private final com.erp.service.qms.NcrService ncrService;
    /** 首件/IPQC 自动建批与断言（inspection-lot D5 兑现；@Lazy 断环） */
    private final InspectionLotService lotService;
    private final InspectionLotDao lotDao;
    /** 检验阻断通知 1h 节流查询（design D9） */
    private final com.erp.dao.system.SysNoticeDao noticeDao;
    /** 阻断通知独立事务（REQUIRES_NEW——随 422 回滚会被吞，实测踩坑） */
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;
    /** 完工接管（4.1/4.2：校验+倒冲+复用 MoService.complete） */
    private final MrpMoBomDao moBomDao;
    private final MaterialIssueDao issueDao;
    private final MaterialIssueLineDao issueLineDao;
    private final MoService moService;

    public ReportingServiceImpl(MrpReportingDao reportingDao, MrpDispatchDao dispatchDao,
                                MrpOutputDao outputDao, MrpFeedDao feedDao, InvStockDao stockDao,
                                MrpMoDao moDao, MrpMoOpDao moOpDao, SysParamService sysParam,
                                @org.springframework.context.annotation.Lazy ApprovalEngine approvalEngine,
                                NoticeService noticeService,
                                @org.springframework.context.annotation.Lazy MoFreezeService moFreezeService,
                                com.erp.service.qms.NcrService ncrService,
                                @org.springframework.context.annotation.Lazy InspectionLotService lotService,
                                InspectionLotDao lotDao,
                                com.erp.dao.system.SysNoticeDao noticeDao,
                                org.springframework.transaction.PlatformTransactionManager transactionManager,
                                MrpMoBomDao moBomDao, MaterialIssueDao issueDao,
                                MaterialIssueLineDao issueLineDao,
                                // @Lazy 断环：Approval→Dispatch→Freeze→Stats→Reporting→Mo→Approval
                                @org.springframework.context.annotation.Lazy MoService moService) {
        this.reportingDao = reportingDao;
        this.dispatchDao = dispatchDao;
        this.outputDao = outputDao;
        this.feedDao = feedDao;
        this.stockDao = stockDao;
        this.moDao = moDao;
        this.moOpDao = moOpDao;
        this.sysParam = sysParam;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
        this.moFreezeService = moFreezeService;
        this.ncrService = ncrService;
        this.lotService = lotService;
        this.lotDao = lotDao;
        this.noticeDao = noticeDao;
        this.transactionManager = transactionManager;
        this.moBomDao = moBomDao;
        this.issueDao = issueDao;
        this.issueLineDao = issueLineDao;
        this.moService = moService;
    }

    // ---------- 2.1 报工单 CRUD 与状态机 ----------

    @Override
    @Transactional
    public MrpReporting open(String dispatchId, String mode) {
        requireEntry("开工报工");
        MrpDispatch dispatch = dispatchDao.selectById(dispatchId);
        if (dispatch == null) {
            throw new ServiceException(422, "派工单不存在：" + dispatchId);
        }
        // 2.2 准入①：终态派工单阻断（偏差 D2——「已过期」以状态口径解释，不引入天数参数；
        // 派工状态机三态 PENDING_CONFIRM/RELEASED/CANCELLED，终态 = CANCELLED；
        // 5.7 报工推进后新增的完成/关闭态由 4.1 扩展时同步并入本判断）
        if (MrpDispatch.ST_CANCELLED.equals(dispatch.getStatus())) {
            throw new ServiceException(422, "派工单已处于终态（" + dispatch.getStatus() + "），不可报工");
        }
        MrpReporting rpt = new MrpReporting();
        rpt.setRptNo(nextRptNo());
        rpt.setDispatchId(dispatch.getId());
        rpt.setDispatchNo(dispatch.getDispatchNo());
        rpt.setMoId(dispatch.getMoId());
        rpt.setMoNo(dispatch.getMoNo());
        rpt.setOpSeq(dispatch.getOpSeq());
        rpt.setOpName(dispatch.getOpName());
        rpt.setWcCode(dispatch.getWcCode());
        rpt.setMode(MrpReporting.MODE_PIECE.equals(mode) ? MrpReporting.MODE_PIECE
                : MrpReporting.MODE_BATCH);
        rpt.setStatus(MrpReporting.ST_OPEN);
        rpt.setStartAt(LocalDateTime.now());
        String current = SecurityUtils.getCurrentUserId();
        rpt.setReporter(current);
        // 派工确认人 = 责任指派人（FR-4.5-5-1 人单不匹配判定基准）
        String owner = dispatch.getConfirmBy();
        rpt.setDispatchOwner(owner);
        // 2.2 准入③：人单不一致 → 确认继续后留痕代报（spec「代报留痕」）
        rpt.setProxyFlag(owner != null && !owner.equals(current) ? "1" : "0");
        // 2.2 准入②：计划开工日早于当日 → 黄标放行（不阻断）
        rpt.setOverdueFlag(dispatch.getPlanStart() != null
                && dispatch.getPlanStart().isBefore(LocalDate.now()) ? "1" : "0");
        rpt.setProdConfirmFlag("0");
        rpt.setHourAlertFlag("0");
        rpt.setOverprodFlag("0");
        reportingDao.insert(rpt);
        // opStatus 流转（design D8）：DISPATCHED → RUNNING（开工报工）
        moOpDao.update(null, new LambdaUpdateWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, rpt.getMoId())
                .eq(MrpMoOp::getOpSeq, rpt.getOpSeq())
                .eq(MrpMoOp::getOpStatus, "DISPATCHED")
                .set(MrpMoOp::getOpStatus, "RUNNING"));
        log.info("reporting {} opened for dispatch {} (proxy={}, overdue={})",
                rpt.getRptNo(), dispatch.getDispatchNo(), rpt.getProxyFlag(), rpt.getOverdueFlag());
        return rpt;
    }

    @Override
    @Transactional
    public MrpReporting confirm(String id) {
        requireEntry("提交报工");
        MrpReporting rpt = get(id);
        // 3.6/3.7 检验断言（spec「首件未合格阻断」「关键工序 IPQC 未决阻断报工」）
        assertInspectionCleared(rpt);
        // 超产挂起前置：OVERPROD_FLAG=1 且审批未通过 → 阻断（spec「超阈值挂起审批」）
        if ("1".equals(rpt.getOverprodFlag()) && !"1".equals(rpt.getProdConfirmFlag())) {
            throw new ServiceException(422, "超产待计划主管审批通过后方可提交（"
                    + rpt.getRptNo() + "）");
        }
        // 工时异常前置：HOUR_ALERT_FLAG=1 且主管未确认 → 阻断（FR-4.5-5-6「提示主管确认」）
        if ("1".equals(rpt.getHourAlertFlag()) && rpt.getHourConfirmBy() == null) {
            throw new ServiceException(422, "工时异常待主管确认后方可提交（"
                    + rpt.getRptNo() + "）");
        }
        // CAS：仅 OPEN 可确认（并发双提交只成功一次，影响行 0 → 409）
        int updated = reportingDao.update(null, new LambdaUpdateWrapper<MrpReporting>()
                .eq(MrpReporting::getId, id)
                .eq(MrpReporting::getStatus, MrpReporting.ST_OPEN)
                .set(MrpReporting::getStatus, MrpReporting.ST_CONFIRMED)
                .set(MrpReporting::getConfirmBy, SecurityUtils.getCurrentUserId())
                .set(MrpReporting::getConfirmAt, LocalDateTime.now()));
        if (updated == 0) {
            if (MrpReporting.ST_CONFIRMED.equals(rpt.getStatus())) {
                throw new ServiceException(409, "报工单已确认：" + rpt.getRptNo());
            }
            throw new ServiceException(409, "报工单状态已变更（" + rpt.getStatus() + "），请刷新");
        }
        // 良率评估（in-process-quality：超差冻结/回拨解冻——同事务，冻结台账与确认一致）
        try {
            moFreezeService.evaluateAndFreeze(rpt.getMoId(), rpt.getOpSeq(), rpt.getRptNo());
        } catch (Exception e) {
            // 冻结评估失败不阻断报工提交（告警走日志；冻结挂点在派工/完工入口兜底）
            log.warn("良率冻结评估失败 mo={} op={}：{}", rpt.getMoId(), rpt.getOpSeq(), e.getMessage());
        }
        // 3.6/3.7 首件/IPQC 自动建批（确认成功后——首单不被自己的批阻断）
        maybeCreateInspectionLots(rpt);
        // opStatus 流转（design D8）：→ DONE（完工报工——确认即该工序完工记录，
        // 同工序后续报工单维持 DONE；冲销失去全部确认单时回退 RUNNING，见 applyReversal）
        moOpDao.update(null, new LambdaUpdateWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, rpt.getMoId())
                .eq(MrpMoOp::getOpSeq, rpt.getOpSeq())
                .in(MrpMoOp::getOpStatus, "DISPATCHED", "RUNNING")
                .set(MrpMoOp::getOpStatus, "DONE"));
        return get(id);
    }

    // ---------- 3.6/3.7 首件与关键工序过程检验（FR-4.5-5-3，inspection-lot D5 兑现） ----------

    /** 断言本工序无未决检验批（FAI/IPQC 各自开关；无批=未触发不阻断） */
    private void assertInspectionCleared(MrpReporting rpt) {
        String moId = rpt.getMoId();
        Integer opSeq = rpt.getOpSeq();
        if (isFirstOp(moId, opSeq)
                && "ON".equalsIgnoreCase(sysParam.getValue("FAI_ENFORCE", "ON"))) {
            requireLotPassed(findLot(moId + "#" + opSeq + "#FAI"),
                    "首件检验未完成或未合格（FAI_ENFORCE=ON）");
        }
        if (isKeyOp(moId, opSeq)
                && "ON".equalsIgnoreCase(sysParam.getValue("IPQC_ENFORCE", "ON"))) {
            requireLotPassed(findLot(moId + "#" + opSeq),
                    "关键工序过程检验未完成或未合格（IPQC_ENFORCE=ON）");
        }
    }

    /** 首件（首道工序首次 GOOD>0 确认）与 IPQC（关键工序首次确认）自动建批 */
    private void maybeCreateInspectionLots(MrpReporting rpt) {
        MrpMo mo = moDao.selectById(rpt.getMoId());
        if (mo == null || mo.getProductCode() == null || mo.getProductCode().isBlank()) {
            return;
        }
        String itemCode = mo.getProductCode();
        // 免检命中不建批（spec 免检通道——无批则不断言）
        if (!lotService.hitExempt(itemCode, null).isEmpty()) {
            return;
        }
        MrpOutput out = outputOf(rpt.getId());
        BigDecimal qty = out == null ? null
                : nvl(out.getGoodQty()).add(nvl(out.getNgQty()));
        if (qty == null || qty.signum() <= 0) {
            qty = BigDecimal.ONE;
        }
        boolean isFirstOp = isFirstOp(rpt.getMoId(), rpt.getOpSeq());
        boolean isKey = isKeyOp(rpt.getMoId(), rpt.getOpSeq());
        // 首件：首道工序 + 本单有合格产出 + 尚无 FAI 批
        if (isFirstOp && out != null && nvl(out.getGoodQty()).signum() > 0
                && findLot(rpt.getMoId() + "#" + rpt.getOpSeq() + "#FAI") == null) {
            createLot(mo, itemCode, rpt.getMoId() + "#" + rpt.getOpSeq() + "#FAI", qty,
                    "首件检验（FR-4.5-5-1 首道工序首次产出触发）");
        }
        // IPQC：关键工序 + 尚无批
        if (isKey && findLot(rpt.getMoId() + "#" + rpt.getOpSeq()) == null) {
            createLot(mo, itemCode, rpt.getMoId() + "#" + rpt.getOpSeq(), qty,
                    "关键工序过程检验（FR-4.5-5-3 首次完工报工触发）");
        }
    }

    private void createLot(MrpMo mo, String itemCode, String refId, BigDecimal qty, String why) {
        Map<String, Object> body = new HashMap<>();
        body.put("lotType", "IPQC");
        body.put("refType", "WORKORDER");
        body.put("refId", refId);
        body.put("itemCode", itemCode);
        body.put("itemName", mo.getProductName());
        body.put("qty", qty);
        try {
            var lot = lotService.createManual(body);
            log.info("检验自动建批 {} ref={}（{}）", lot.getLotNo(), refId, why);
        } catch (ServiceException e) {
            if (e.getMessage() != null && e.getMessage().contains("免检")) {
                log.info("物料 {} 免检，跳过检验建批", itemCode);
                return;
            }
            throw e;
        }
    }

    private InspectionLot findLot(String refId) {
        return lotDao.selectOne(new LambdaQueryWrapper<InspectionLot>()
                .eq(InspectionLot::getRefType, "WORKORDER")
                .eq(InspectionLot::getRefId, refId)
                .last("LIMIT 1"));
    }

    /** 批不存在=未触发放行；RELEASED/SKIPPED=已合格放行；其余 → 422 + 阻断通知（D9 1h 节流） */
    private void requireLotPassed(InspectionLot lot, String message) {
        if (lot == null) {
            return;
        }
        String st = lot.getStatus();
        if ("RELEASED".equals(st) || "SKIPPED".equals(st)) {
            return;
        }
        pushBlockNotice(lot, message);
        throw new ServiceException(422, message + "（检验批 " + lot.getLotNo()
                + " 状态 " + st + "）");
    }

    /**
     * 检验阻断通知（design D9 ②：FAI/IPQC_BLOCK，首推即时、同单同工序 1h 幂等节流）。
     * **独立事务推送**：阻断本身随 422 回滚，同事务的通知 INSERT 会被一并回滚（实测踩坑）——
     * 用 REQUIRES_NEW 新事务提交，节流查询也读已提交数据。
     * bizId = moId#opSeq[#FAI] 区分工序与类型。
     */
    private void pushBlockNotice(InspectionLot lot, String message) {
        try {
            String refId = lot.getRefId();
            if (refId == null || !refId.contains("#")) {
                return;
            }
            boolean fai = refId.endsWith("#FAI");
            String bizType = fai ? "FAI_BLOCK" : "IPQC_BLOCK";
            String title = fai ? "首件检验阻断报工" : "过程检验阻断报工";
            String content = message + "（检验批 " + lot.getLotNo() + "，放行后自动恢复）";
            org.springframework.transaction.support.TransactionTemplate tpl =
                    new org.springframework.transaction.support.TransactionTemplate(
                            transactionManager);
            tpl.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tpl.execute(status -> {
                // 1h 节流：同 bizType + bizId 近 1 小时已有记录则不推（读已提交）
                long recent = noticeDao.selectCount(
                        new LambdaQueryWrapper<com.erp.entity.system.SysNotice>()
                                .eq(com.erp.entity.system.SysNotice::getBizType, bizType)
                                .eq(com.erp.entity.system.SysNotice::getBizId, refId)
                                .gt(com.erp.entity.system.SysNotice::getCreateDate,
                                        LocalDateTime.now().minusHours(1)));
                if (recent == 0) {
                    noticeService.push("ROLE_SHOP_FLOOR", null, title, content, bizType, refId);
                }
                return null;
            });
        } catch (Exception e) {
            // 通知失败不阻断主流程语义（阻断本身照常抛 422）
            log.warn("检验阻断通知推送失败 ref={}：{}", lot.getRefId(), e.getMessage());
        }
    }

    private boolean isFirstOp(String moId, Integer opSeq) {
        if (opSeq == null) {
            return false;
        }
        Integer min = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .orderByAsc(MrpMoOp::getOpSeq)
                .last("LIMIT 1")).stream()
                .map(MrpMoOp::getOpSeq).findFirst().orElse(null);
        return opSeq.equals(min);
    }

    private boolean isKeyOp(String moId, Integer opSeq) {
        MrpMoOp op = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .eq(MrpMoOp::getOpSeq, opSeq)
                .last("LIMIT 1"));
        return op != null && "1".equals(op.getKeyOpFlag());
    }

    @Override
    @Transactional
    public void discard(String id) {
        requireEntry("放弃报工草稿");
        MrpReporting rpt = get(id);
        if (!MrpReporting.ST_OPEN.equals(rpt.getStatus())) {
            // BR-4.5-25：确认后不可删除，仅可冲销
            throw new ServiceException(422, "报工已确认，仅可通过冲销纠正");
        }
        reportingDao.deleteById(id);
    }

    @Override
    public MrpReporting get(String id) {
        MrpReporting rpt = reportingDao.selectById(id);
        if (rpt == null) {
            throw new ServiceException(404, "报工单不存在：" + id);
        }
        return rpt;
    }

    @Override
    public List<MrpReporting> list(String moId, Integer opSeq, String status) {
        requireRead("查询报工记录");
        return reportingDao.selectList(new LambdaQueryWrapper<MrpReporting>()
                .eq(hasText(moId), MrpReporting::getMoId, moId)
                .eq(opSeq != null, MrpReporting::getOpSeq, opSeq)
                .eq(hasText(status), MrpReporting::getStatus, status)
                .orderByDesc(MrpReporting::getCreateDate));
    }

    @Override
    public void requireOpen(String id) {
        MrpReporting rpt = get(id);
        if (!MrpReporting.ST_OPEN.equals(rpt.getStatus())) {
            throw new ServiceException(422, "报工已确认，仅可通过冲销纠正");
        }
    }

    @Override
    public Map<String, Object> confirmedTotals(String moId, Integer opSeq) {
        requireRead("查询工序累计");
        List<MrpReporting> confirmed = reportingDao.selectList(new LambdaQueryWrapper<MrpReporting>()
                .eq(MrpReporting::getMoId, moId)
                .eq(opSeq != null, MrpReporting::getOpSeq, opSeq)
                .eq(MrpReporting::getStatus, MrpReporting.ST_CONFIRMED));
        List<String> rptIds = confirmed.stream().map(MrpReporting::getId).toList();
        BigDecimal good = BigDecimal.ZERO;
        BigDecimal ng = BigDecimal.ZERO;
        if (!rptIds.isEmpty()) {
            List<MrpOutput> outputs = outputDao.selectList(new LambdaQueryWrapper<MrpOutput>()
                    .in(MrpOutput::getRptId, rptIds));
            for (MrpOutput o : outputs) {
                good = good.add(nvl(o.getGoodQty()));
                ng = ng.add(nvl(o.getNgQty()));
            }
        }
        Map<String, Object> result = new HashMap<>();
        result.put("goodQty", good);
        result.put("ngQty", ng);
        result.put("rptCount", (long) confirmed.size());
        // 累计投入 = Σ(GOOD+NG)（design D2 工序级口径）；分母 0 由统计侧返回 null
        result.put("inputQty", good.add(ng));
        return result;
    }

    // ---------- 2.3 投料即扣与退回（design D3） ----------

    @Override
    @Transactional
    public MrpFeed addFeed(String rptId, String itemCode, String batchCode, BigDecimal qty) {
        requireEntry("投料记录");
        requireOpen(rptId);
        if (itemCode == null || itemCode.isBlank()) {
            throw new ServiceException(422, "投料物料必填");
        }
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "投料数量必须大于 0");
        }
        MrpReporting rpt = get(rptId);
        // 行锁 FIFO 扣减 + ge 护栏（预扣同款，防并发扣成负库存）
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, itemCode)
                .gt(InvStock::getAvailableQty, 0)
                .orderByAsc(InvStock::getInboundDate)
                .orderByAsc(InvStock::getBinCode)
                .last("FOR UPDATE"));
        BigDecimal avail = rows.stream()
                .map(s -> nvl(s.getAvailableQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (avail.compareTo(qty) < 0) {
            // 余量护栏：可用量不足 → 422（不产生负库存，spec「退回护栏」同口径）
            throw new ServiceException(422, "可用量不足，无法投料：" + itemCode
                    + "（可用 " + avail.stripTrailingZeros().toPlainString()
                    + "，需 " + qty.stripTrailingZeros().toPlainString() + "）");
        }
        BigDecimal left = qty;
        for (InvStock s : rows) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal rowAvail = nvl(s.getAvailableQty());
            if (rowAvail.signum() <= 0) {
                continue;
            }
            BigDecimal take = rowAvail.min(left);
            int upd = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, s.getId())
                    .ge(InvStock::getAvailableQty, take)
                    .setSql("AVAILABLE_QTY = AVAILABLE_QTY - " + take.toPlainString()));
            if (upd == 0) {
                throw new ServiceException(409, "投料并发冲突，请重试：" + itemCode);
            }
            left = left.subtract(take);
        }
        if (left.signum() != 0) {
            throw new ServiceException(422, "可用量不足，无法投料：" + itemCode);
        }
        MrpFeed feed = new MrpFeed();
        feed.setRptId(rptId);
        feed.setMoId(rpt.getMoId());
        feed.setOpSeq(rpt.getOpSeq());
        feed.setItemCode(itemCode);
        feed.setBatchCode(batchCode);
        feed.setQty(qty);
        feed.setFlowType(MrpFeed.FLOW_HAND);
        feed.setReturnedFlag("0");
        feed.setFeedAt(LocalDateTime.now());
        feedDao.insert(feed);
        log.info("投料即扣 rpt={} {} batch={} qty={}", rpt.getRptNo(), itemCode, batchCode, qty);
        return feed;
    }

    @Override
    public List<MrpFeed> feedsOf(String rptId) {
        requireEntry("查询投料记录");
        return feedDao.selectList(new LambdaQueryWrapper<MrpFeed>()
                .eq(MrpFeed::getRptId, rptId)
                .orderByAsc(MrpFeed::getCreateDate));
    }

    @Override
    @Transactional
    public int reverseFeeds(String rptId, String reason) {
        List<MrpFeed> feeds = feedDao.selectList(new LambdaQueryWrapper<MrpFeed>()
                .eq(MrpFeed::getRptId, rptId)
                .eq(MrpFeed::getReturnedFlag, "0"));
        int n = 0;
        for (MrpFeed f : feeds) {
            BigDecimal qty = nvl(f.getQty());
            if (qty.signum() <= 0) {
                continue;
            }
            // 恢复原账：按物料行锁 FIFO 回加（批次不精确归位——冻结解冻同口径，流水可审计）
            List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getItemCode, f.getItemCode())
                    .orderByAsc(InvStock::getInboundDate)
                    .orderByAsc(InvStock::getBinCode)
                    .last("FOR UPDATE"));
            if (rows.isEmpty()) {
                throw new ServiceException(422, "退回时无库存行可回加：" + f.getItemCode()
                        + "（余量护栏）");
            }
            // 优先回加到原批次行；无则首行
            InvStock target = rows.stream()
                    .filter(s -> f.getBatchCode() != null && f.getBatchCode().equals(s.getBatchNo()))
                    .findFirst()
                    .orElse(rows.get(0));
            stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, target.getId())
                    .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + qty.toPlainString()));
            // 幂等锚点（先扣再标的顺序无妨——同事务）
            feedDao.update(null, new LambdaUpdateWrapper<MrpFeed>()
                    .eq(MrpFeed::getId, f.getId())
                    .eq(MrpFeed::getReturnedFlag, "0")
                    .set(MrpFeed::getReturnedFlag, "1"));
            n++;
        }
        log.info("投料回加 rpt={} rows={} reason={}", rptId, n, reason);
        return n;
    }

    // ---------- 2.4 双模式产出与超产分级（FR-4.5-5-4） ----------

    @Override
    @Transactional
    public MrpOutput saveOutput(String rptId, BigDecimal goodQty, BigDecimal ngQty,
                                String ngDispose, boolean overprodAck) {
        requireEntry("产出记录");
        requireOpen(rptId);
        MrpReporting rpt = get(rptId);
        BigDecimal good = nvl(goodQty);
        BigDecimal ng = nvl(ngQty);
        if (good.signum() < 0 || ng.signum() < 0) {
            throw new ServiceException(422, "产出数量不能为负");
        }
        if (good.signum() == 0 && ng.signum() == 0) {
            throw new ServiceException(422, "合格与不合格产出不能同时为 0");
        }
        // 超产分级（基数 = 工单计划数量，偏差确认口径见接口注释）
        MrpMo mo = moDao.selectById(rpt.getMoId());
        BigDecimal planQty = mo == null ? BigDecimal.ZERO : nvl(mo.getQty());
        if (planQty.signum() > 0) {
            // 本单所在工序累计（已确认单 + 本单本笔）
            Map<String, Object> totals = confirmedTotals(rpt.getMoId(), rpt.getOpSeq());
            BigDecimal prevGood = (BigDecimal) totals.get("goodQty");
            BigDecimal prevNg = (BigDecimal) totals.get("ngQty");
            BigDecimal cumGood = prevGood.add(good);
            BigDecimal cumTotal = cumGood.add(prevNg).add(ng);
            if (cumTotal.compareTo(planQty) > 0) {
                BigDecimal over = cumTotal.subtract(planQty);
                BigDecimal pct = over.multiply(BigDecimal.valueOf(100))
                        .divide(planQty, 2, java.math.RoundingMode.HALF_UP);
                BigDecimal tolerance = new BigDecimal(sysParam.getValue("OVERPROD_TOLERANCE", "5"));
                if (pct.compareTo(tolerance) <= 0) {
                    // 阈值内：操作工二次确认入账
                    if (!overprodAck) {
                        throw new ServiceException(422, "超产提示：累计产出 " + cumTotal
                                + " 超计划 " + planQty + "（超 " + pct + "% ≤ 容差 " + tolerance
                                + "%），请二次确认后提交");
                    }
                } else {
                    // 超阈值：保存成功但挂起——置 flag + 提交 OverProd 审批（不抛异常，
                    // 否则事务回滚连审批一起丢）；阻断点在 confirm 前置校验（spec
                    // 「超阈值挂起审批」：报工单挂起，通过后方计入累计）
                    if (!"1".equals(rpt.getOverprodFlag())) {
                        ApprovalInstance inst = approvalEngine.submit(BIZ_OVERPROD, rptId,
                                "超产确认：" + rpt.getRptNo() + "（超 " + pct + "%）", null,
                                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PLAN_MGR", "计划主管"))));
                        log.info("超产挂审批 {} over={} pct={} apprNo={}",
                                rpt.getRptNo(), over, pct, inst.getApprNo());
                    }
                    reportingDao.update(null,
                            new LambdaUpdateWrapper<MrpReporting>()
                                    .eq(MrpReporting::getId, rptId)
                                    .set(MrpReporting::getOverprodFlag, "1"));
                    log.info("超产挂起 {} 累计 {} > 计划 {}（超 {}%），待计划主管审批",
                            rpt.getRptNo(), cumTotal, planQty, pct);
                }
            } else if ("1".equals(rpt.getOverprodFlag())) {
                // 驳回后改量重提：累计已不超产 → 解除挂起（confirm 前置放行）
                reportingDao.update(null, new LambdaUpdateWrapper<MrpReporting>()
                        .eq(MrpReporting::getId, rptId)
                        .set(MrpReporting::getOverprodFlag, "0"));
            }
        }
        MrpOutput saved = upsertOutput(rpt, good, ng, ngDispose);
        // 分级 NCR 承接（design D6：返工/让步/超阈报废建 NCR + 在制冻结；小额报废域内豁免）
        handleNgDisposition(rpt, saved, ng, ngDispose);
        return saved;
    }

    /** 产出行保存后的分级 NCR 承接（design D6 / spec「不合格产出分级 NCR」） */
    private void handleNgDisposition(MrpReporting rpt, MrpOutput out,
                                     BigDecimal ng, String ngDispose) {
        if (ng == null || ng.signum() <= 0) {
            return;
        }
        boolean directScrap = "SCRAP".equalsIgnoreCase(ngDispose);
        BigDecimal autoQty = new BigDecimal(sysParam.getValue("NCR_AUTO_QTY", "5"));
        if (directScrap && ng.compareTo(autoQty) < 0) {
            // 豁免：小额报废域内处置（不建 NCR）
            outputDao.update(null, new LambdaUpdateWrapper<MrpOutput>()
                    .eq(MrpOutput::getId, out.getId())
                    .set(MrpOutput::getDirectDisposeFlag, "1"));
            return;
        }
        // 一律建 NCR：返工/让步/超阈报废（refId=moId#opSeq#rptNo，幂等）
        String refId = rpt.getMoId() + "#" + rpt.getOpSeq() + "#" + rpt.getRptNo();
        String itemCode = rpt.getMoNo() == null ? null : productCodeOf(rpt.getMoId());
        var ncr = ncrService.createForWorkOrder(rpt.getMoId(), rpt.getOpSeq(), refId,
                itemCode, null, ng,
                ngDispose == null ? null : ngDispose.toUpperCase(),
                "MINOR",
                "报工不合格 " + ng + " 件（报工单 " + rpt.getRptNo()
                        + "，处置 " + (ngDispose == null ? "待评审" : ngDispose) + "）");
        outputDao.update(null, new LambdaUpdateWrapper<MrpOutput>()
                .eq(MrpOutput::getId, out.getId())
                .set(MrpOutput::getNcrId, ncr.getId())
                .set(MrpOutput::getDirectDisposeFlag, "0"));
        out.setNcrId(ncr.getId());
        out.setDirectDisposeFlag("0");
        // 在制冻结：挡该工单后续派工与完工（不冻库存）
        moFreezeService.freezeForNcr(rpt.getMoId(), ncr.getId());
        log.info("报工不合格承接 NCR {} rpt={} ng={} dispose={}",
                ncr.getNcrNo(), rpt.getRptNo(), ng, ngDispose);
    }

    private String productCodeOf(String moId) {
        MrpMo mo = moDao.selectById(moId);
        return mo == null ? null : mo.getProductCode();
    }

    @Override
    public MrpOutput outputOf(String rptId) {
        requireEntry("查询产出记录");
        return outputDao.selectOne(new LambdaQueryWrapper<MrpOutput>()
                .eq(MrpOutput::getRptId, rptId).last("LIMIT 1"));
    }

    @Override
    @Transactional
    public void approveOverProd(String rptId, boolean pass, String opinion) {
        MrpReporting rpt = get(rptId);
        if (pass) {
            reportingDao.update(null, new LambdaUpdateWrapper<MrpReporting>()
                    .eq(MrpReporting::getId, rptId)
                    .set(MrpReporting::getProdConfirmFlag, "1"));
            log.info("超产审批通过 {} opinion={}", rpt.getRptNo(), opinion);
        } else {
            log.info("超产审批驳回 {} opinion={}", rpt.getRptNo(), opinion);
        }
        // 驳回：报工单保持挂起（OVERPROD_FLAG=1 且 PROD_CONFIRM=0 → confirm 前置阻断），
        // 操作工可改量后重提（改量消除超产 → saveOutput 走无超产分支，状态自然放行）
    }

    /** 覆盖式保存产出行（逐件/批量同结构——前端累加后提交，一单一产出行） */
    private MrpOutput upsertOutput(MrpReporting rpt, BigDecimal good, BigDecimal ng,
                                   String ngDispose) {
        MrpOutput existing = outputDao.selectOne(new LambdaQueryWrapper<MrpOutput>()
                .eq(MrpOutput::getRptId, rpt.getId()).last("LIMIT 1"));
        MrpOutput out = existing != null ? existing : new MrpOutput();
        out.setRptId(rpt.getId());
        out.setMoId(rpt.getMoId());
        out.setOpSeq(rpt.getOpSeq());
        out.setGoodQty(good);
        out.setNgQty(ng);
        out.setNgDispose(ngDispose);
        out.setOutputAt(LocalDateTime.now());
        if (existing == null) {
            outputDao.insert(out);
        } else {
            outputDao.updateById(out);
        }
        return out;
    }

    // ---------- 2.5 工时分项与异常待办（FR-4.5-5-6） ----------

    @Override
    @Transactional
    public MrpReporting saveHours(String rptId, BigDecimal setupHours, BigDecimal runHours,
                                  BigDecimal machineHours) {
        requireEntry("工时记录");
        requireOpen(rptId);
        MrpReporting rpt = get(rptId);
        if (nvl(setupHours).signum() < 0 || nvl(runHours).signum() < 0
                || nvl(machineHours).signum() < 0) {
            throw new ServiceException(422, "工时不能为负");
        }
        // 分项偏离比对：快照标准工时（erp_mrp_mo_op SETUP_HOURS/RUN_HOURS）
        boolean alert = false;
        BigDecimal deviation = new BigDecimal(sysParam.getValue("WORKHOUR_DEVIATION_ALERT", "50"));
        MrpMoOp op = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, rpt.getMoId())
                .eq(MrpMoOp::getOpSeq, rpt.getOpSeq())
                .last("LIMIT 1"));
        if (op != null) {
            alert = overDeviation(setupHours, op.getSetupHours(), deviation)
                    || overDeviation(runHours, op.getRunHours(), deviation);
        }
        reportingDao.update(null, new LambdaUpdateWrapper<MrpReporting>()
                .eq(MrpReporting::getId, rptId)
                .set(MrpReporting::getSetupHours, setupHours)
                .set(MrpReporting::getRunHours, runHours)
                .set(MrpReporting::getMachineHours, machineHours)
                .set(MrpReporting::getHourAlertFlag, alert ? "1" : "0")
                // 重录消警：未超限时清确认态（或超限重录后待重新确认）
                .set(MrpReporting::getHourConfirmBy, null)
                .set(MrpReporting::getHourConfirmAt, null));
        if (alert) {
            // 待办通知主管（bizType WORKHOUR_ALERT，按 rptId 幂等——notice push 自带幂等）
            noticeService.push("ROLE_PLAN_MGR", null,
                    "工时异常待确认", "报工单 " + rpt.getRptNo() + " 工时偏离标准工时超过 "
                            + deviation + "%，请确认后入账", "WORKHOUR_ALERT", rptId);
            log.info("工时异常待办 rpt={} deviation阈值={}", rpt.getRptNo(), deviation);
        }
        return get(rptId);
    }

    @Override
    @Transactional
    public void confirmHours(String rptId) {
        requireAnyRole("工时异常确认", "ROLE_PLAN_MGR", "ROLE_ADMIN");
        MrpReporting rpt = get(rptId);
        if (!"1".equals(rpt.getHourAlertFlag())) {
            throw new ServiceException(422, "该报工单无工时异常待确认");
        }
        reportingDao.update(null, new LambdaUpdateWrapper<MrpReporting>()
                .eq(MrpReporting::getId, rptId)
                .set(MrpReporting::getHourConfirmBy, SecurityUtils.getCurrentUserId())
                .set(MrpReporting::getHourConfirmAt, LocalDateTime.now()));
        log.info("工时异常确认 {} by {}", rpt.getRptNo(), SecurityUtils.getCurrentUserId());
    }

    /** 分项偏离 > 阈值（标准值为 0/空时不比对——无基线不误报） */
    private static boolean overDeviation(BigDecimal actual, BigDecimal standard,
                                         BigDecimal deviationPct) {
        if (actual == null || standard == null || standard.signum() <= 0) {
            return false;
        }
        BigDecimal diff = actual.subtract(standard).abs();
        BigDecimal pct = diff.multiply(BigDecimal.valueOf(100))
                .divide(standard, 2, java.math.RoundingMode.HALF_UP);
        return pct.compareTo(deviationPct) > 0;
    }

    // ---------- 2.6 整单红字冲销审批链（BR-4.5-25，design D7 五步反向） ----------

    @Override
    @Transactional
    public Map<String, Object> applyReversal(String rptId, String reason) {
        requireAnyRole("冲销申请", "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_ADMIN");
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "冲销原因必填（BR-4.5-25）");
        }
        MrpReporting rpt = get(rptId);
        if (!MrpReporting.ST_CONFIRMED.equals(rpt.getStatus())) {
            throw new ServiceException(422, "仅已确认报工单可冲销：" + rpt.getStatus());
        }
        // 申请即预写原因（审批回调只有 bizId——执行版从原单读，驳回后保留作痕）
        reportingDao.update(null, new LambdaUpdateWrapper<MrpReporting>()
                .eq(MrpReporting::getId, rptId)
                .set(MrpReporting::getReverseReason, reason.trim()));
        ApprovalInstance inst = approvalEngine.submit(BIZ_REVERSAL, rptId,
                "报工冲销：" + rpt.getRptNo() + "（" + reason.trim() + "）", null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PLAN_MGR", "计划主管"))));
        log.info("冲销申请 {} reason={} by {}", rpt.getRptNo(), reason.trim(),
                SecurityUtils.getCurrentUserId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apprId", inst.getId());
        out.put("apprNo", inst.getApprNo());
        out.put("status", inst.getStatus());
        out.put("rptNo", rpt.getRptNo());
        return out;
    }

    @Override
    @Transactional
    public MrpReporting applyReversal(String rptId) {
        MrpReporting orig = get(rptId);
        if (MrpReporting.ST_REVERSED.equals(orig.getStatus())) {
            // 幂等：已冲销不可再冲（回调重复触发或并发）
            throw new ServiceException(422, "报工单已冲销：" + orig.getRptNo());
        }
        if (!MrpReporting.ST_CONFIRMED.equals(orig.getStatus())) {
            throw new ServiceException(422, "仅已确认报工单可冲销：" + orig.getStatus());
        }
        String operator = SecurityUtils.getCurrentUserId();

        // ① 投料回加（HAND/BFLUSH 逐行恢复原账，幂等锚点）
        int returned = reverseFeeds(rptId, "冲销回加");

        // ②③ 红字镜像单：负值产出与工时（仿 gl-voucher 红字——原单不动、镜像记反向）
        MrpReporting mirror = new MrpReporting();
        mirror.setRptNo(nextRptNo());
        mirror.setDispatchId(orig.getDispatchId());
        mirror.setDispatchNo(orig.getDispatchNo());
        mirror.setMoId(orig.getMoId());
        mirror.setMoNo(orig.getMoNo());
        mirror.setOpSeq(orig.getOpSeq());
        mirror.setOpName(orig.getOpName());
        mirror.setWcCode(orig.getWcCode());
        mirror.setMode(orig.getMode());
        mirror.setStatus(MrpReporting.ST_REVERSED);
        mirror.setReversesId(orig.getId());
        mirror.setReverseReason(orig.getReverseReason());
        mirror.setReverseBy(operator);
        mirror.setReverseAt(LocalDateTime.now());
        mirror.setStartAt(orig.getStartAt());
        mirror.setReporter(operator);
        mirror.setDispatchOwner(orig.getDispatchOwner());
        mirror.setSetupHours(nvl(orig.getSetupHours()).negate());
        mirror.setRunHours(nvl(orig.getRunHours()).negate());
        mirror.setMachineHours(nvl(orig.getMachineHours()).negate());
        mirror.setProdConfirmFlag("0");
        mirror.setHourAlertFlag("0");
        mirror.setOverprodFlag("0");
        mirror.setProxyFlag(orig.getProxyFlag());
        mirror.setOverdueFlag(orig.getOverdueFlag());
        reportingDao.insert(mirror);

        MrpOutput origOut = outputDao.selectOne(new LambdaQueryWrapper<MrpOutput>()
                .eq(MrpOutput::getRptId, rptId).last("LIMIT 1"));
        if (origOut != null) {
            MrpOutput red = new MrpOutput();
            red.setRptId(mirror.getId());
            red.setMoId(orig.getMoId());
            red.setOpSeq(orig.getOpSeq());
            red.setGoodQty(nvl(origOut.getGoodQty()).negate());
            red.setNgQty(nvl(origOut.getNgQty()).negate());
            red.setNgDispose(origOut.getNgDispose());
            red.setOutputAt(LocalDateTime.now());
            outputDao.insert(red);
        }

        // ④ 原单置 REVERSED + 关联镜像（原单内容只读保留——审计成对）
        reportingDao.update(null, new LambdaUpdateWrapper<MrpReporting>()
                .eq(MrpReporting::getId, rptId)
                .eq(MrpReporting::getStatus, MrpReporting.ST_CONFIRMED)
                .set(MrpReporting::getStatus, MrpReporting.ST_REVERSED)
                .set(MrpReporting::getReversedById, mirror.getId())
                .set(MrpReporting::getReverseBy, operator)
                .set(MrpReporting::getReverseAt, LocalDateTime.now()));
        // ⑤ 良率回拨 = 累计自动剔除 REVERSED 单（confirmedTotals 只取 CONFIRMED）；
        //    显式触发冻结评估——回拨 ≤ 阈值时自动解冻 + 通知原接收人（spec「冲销回拨解冻」）
        try {
            moFreezeService.evaluateAndFreeze(orig.getMoId(), orig.getOpSeq(), null);
        } catch (Exception e) {
            log.warn("冲销后冻结评估失败 mo={}：{}", orig.getMoId(), e.getMessage());
        }
        // ⑥ 完工状态回退（4.3，spec「完工状态回退」）：opStatus 回退 + COMPLETED → RELEASED
        rollbackCompletionIfNeeded(orig);
        log.info("冲销执行 {} → 镜像 {} 投料回加={} 行 by {}",
                orig.getRptNo(), mirror.getRptNo(), returned, operator);
        return get(rptId);
    }

    /**
     * 冲销后的完工回退判定（spec completion-backflush「完工状态回退」两场景）：
     * 1) opStatus：该工序失去全部 CONFIRMED 单 → DONE 回退 RUNNING；
     * 2) 工单 COMPLETED 且（存在未完成工序 或 末道累计合格 < 完工基线 qualifiedQty）
     *    → RELEASED + 倒冲 BFLUSH 行回加库存（余量护栏，不足 422）。
     * 场景「冲销中间单保持 Completed」：全工序仍 DONE 且累计仍 ≥ 基线 → 不回退。
     */
    private void rollbackCompletionIfNeeded(MrpReporting orig) {
        String moId = orig.getMoId();
        Integer opSeq = orig.getOpSeq();
        // 1) 工序回退：该工序已无 CONFIRMED 报工单 → DONE → RUNNING
        long confirmedLeft = reportingDao.selectCount(new LambdaQueryWrapper<MrpReporting>()
                .eq(MrpReporting::getMoId, moId)
                .eq(MrpReporting::getOpSeq, opSeq)
                .eq(MrpReporting::getStatus, MrpReporting.ST_CONFIRMED));
        if (confirmedLeft == 0) {
            moOpDao.update(null, new LambdaUpdateWrapper<MrpMoOp>()
                    .eq(MrpMoOp::getMoId, moId)
                    .eq(MrpMoOp::getOpSeq, opSeq)
                    .eq(MrpMoOp::getOpStatus, "DONE")
                    .set(MrpMoOp::getOpStatus, "RUNNING"));
        }
        // 2) 完工回退判定（仅 COMPLETED 需要）
        MrpMo mo = moDao.selectById(moId);
        if (mo == null || !MrpMo.ST_COMPLETED.equals(mo.getStatus())) {
            return;
        }
        List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId).orderByAsc(MrpMoOp::getOpSeq));
        boolean allDone = ops.stream().allMatch(o -> "DONE".equals(o.getOpStatus()));
        Integer lastOp = ops.isEmpty() ? null : ops.get(ops.size() - 1).getOpSeq();
        BigDecimal lastGood = lastOp == null ? BigDecimal.ZERO
                : (BigDecimal) confirmedTotals(moId, lastOp).get("goodQty");
        BigDecimal baseline = nvl(mo.getQualifiedQty());
        if (allDone && lastGood.compareTo(baseline) >= 0) {
            return; // spec 场景「冲销中间单保持 Completed」
        }
        // 回退：COMPLETED → RELEASED（MoStatusRules 白名单已加边）
        MoStatusRules.assertTransit(MrpMo.ST_COMPLETED, MrpMo.ST_RELEASED);
        int upd = moDao.update(null, new LambdaUpdateWrapper<MrpMo>()
                .eq(MrpMo::getId, moId)
                .eq(MrpMo::getStatus, MrpMo.ST_COMPLETED)
                .set(MrpMo::getStatus, MrpMo.ST_RELEASED)
                .set(MrpMo::getQualifiedQty, lastGood));
        if (upd == 0) {
            throw new ServiceException(409, "工单状态已变更，完工回退冲突：" + mo.getMoNo());
        }
        // 倒冲补投 BFLUSH 行回加库存（余量护栏——不足 422 阻断冲销，事务回滚）
        reverseBflushFeeds(moId);
        log.info("完工回退 {}：COMPLETED → RELEASED（未完成工序或累计合格 {} < 基线 {}）",
                mo.getMoNo(), lastGood, baseline);
    }

    /** 倒冲补投回加（完工回退调用；RETURNED_FLAG 幂等，与 HAND 行同护栏） */
    private void reverseBflushFeeds(String moId) {
        for (MrpFeed f : feedDao.selectList(new LambdaQueryWrapper<MrpFeed>()
                .eq(MrpFeed::getMoId, moId)
                .eq(MrpFeed::getFlowType, MrpFeed.FLOW_BFLUSH)
                .eq(MrpFeed::getReturnedFlag, "0"))) {
            BigDecimal qty = nvl(f.getQty());
            if (qty.signum() <= 0) {
                continue;
            }
            List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getItemCode, f.getItemCode())
                    .orderByAsc(InvStock::getInboundDate)
                    .orderByAsc(InvStock::getBinCode)
                    .last("FOR UPDATE"));
            if (rows.isEmpty()) {
                throw new ServiceException(422, "倒冲回加时无库存行可回加：" + f.getItemCode()
                        + "（余量护栏）");
            }
            InvStock target = rows.stream()
                    .filter(s -> f.getBatchCode() != null && f.getBatchCode().equals(s.getBatchNo()))
                    .findFirst().orElse(rows.get(0));
            stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, target.getId())
                    .setSql("AVAILABLE_QTY = AVAILABLE_QTY + " + qty.toPlainString()));
            feedDao.update(null, new LambdaUpdateWrapper<MrpFeed>()
                    .eq(MrpFeed::getId, f.getId())
                    .eq(MrpFeed::getReturnedFlag, "0")
                    .set(MrpFeed::getReturnedFlag, "1"));
        }
    }

    // ---------- 4.1/4.2 完工确认接管与倒冲补足（completion-backflush） ----------

    @Override
    @Transactional
    public Map<String, Object> completeMo(String moId) {
        requireAnyRole("完工确认", "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_ADMIN");
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + moId);
        }
        if (!MrpMo.ST_RELEASED.equals(mo.getStatus())) {
            throw new ServiceException(422, "仅已释放状态可经报工完工（当前 "
                    + mo.getStatus() + "）");
        }
        List<String> pending = new ArrayList<>();

        // 校验①：全工序 DONE（列出未完成项——spec「末道未完成阻断」）
        List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId).orderByAsc(MrpMoOp::getOpSeq));
        if (ops.isEmpty()) {
            throw new ServiceException(422, "工单无工序快照，请在 5.4.5 手动完工");
        }
        List<String> undone = ops.stream()
                .filter(o -> !"DONE".equals(o.getOpStatus()))
                .map(o -> o.getOpSeq() + " " + o.getOpName() + "(" + o.getOpStatus() + ")")
                .toList();
        pending.addAll(undone);

        // 校验②：首件/IPQC 无未决（各自 ENFORCE 开关，OFF 降级放行）
        for (MrpMoOp op : ops) {
            boolean fai = isFirstOp(moId, op.getOpSeq());
            boolean key = isKeyOp(moId, op.getOpSeq());
            if (!fai && !key) {
                continue;
            }
            String refId = fai ? moId + "#" + op.getOpSeq() + "#FAI"
                    : moId + "#" + op.getOpSeq();
            InspectionLot lot = findLot(refId);
            if (lot == null) {
                continue;
            }
            boolean enforce = fai
                    ? "ON".equalsIgnoreCase(sysParam.getValue("FAI_ENFORCE", "ON"))
                    : "ON".equalsIgnoreCase(sysParam.getValue("IPQC_ENFORCE", "ON"));
            String st = lot.getStatus();
            if (enforce && !"RELEASED".equals(st) && !"SKIPPED".equals(st)) {
                pending.add((fai ? "首件" : "关键工序过程检验") + "未决（批 "
                        + lot.getLotNo() + " 状态 " + st + "）");
            }
        }

        // 校验③：无活动冻结（工单级 + 各工序 WC 级——spec「冻结中完工阻断」）
        moFreezeService.assertNotFrozen(moId, null);
        for (MrpMoOp op : ops) {
            if (op.getWcCode() != null && !op.getWcCode().isEmpty()) {
                moFreezeService.assertNotFrozen(moId, op.getWcCode());
            }
        }

        if (!pending.isEmpty()) {
            throw new ServiceException(422, "完工校验未通过，未决项：" + String.join("；", pending));
        }

        // Step1 倒冲补足（同事务，design D8：差额防御——已过账部分不重复扣）
        List<Map<String, Object>> backflush = backflush(mo, ops);

        // Step2 完工（复用 5.4.5，合格产出 = 末道工序累计合格）
        Integer lastOp = ops.get(ops.size() - 1).getOpSeq();
        BigDecimal qualified = (BigDecimal) confirmedTotals(moId, lastOp).get("goodQty");
        moService.complete(moId, qualified == null ? BigDecimal.ZERO : qualified);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moNo", mo.getMoNo());
        out.put("status", MrpMo.ST_COMPLETED);
        out.put("qualifiedQty", qualified);
        out.put("backflushRows", backflush.size());
        out.put("backflush", backflush);
        log.info("报工完工接管 {} 合格={} 倒冲补投 {} 行", mo.getMoNo(), qualified, backflush.size());
        return out;
    }

    /**
     * 倒冲补足：逐 BOM 行 差额 = 应投（QTY×用量×(1+损耗)）− 非回加投料累计 − POSTED 领料合计。
     * 差额 > 0 → BFLUSH 行 + 扣可用量；任一行不足 → 422 列缺料明细（spec「补足缺料阻断完工」）。
     */
    private List<Map<String, Object>> backflush(MrpMo mo, List<MrpMoOp> ops) {
        List<MrpMoBom> bomRows = moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, mo.getId()));
        if (bomRows.isEmpty()) {
            return List.of();
        }
        // POSTED 领料合计（按工单号取已过账领料单行，按物料聚合）
        List<String> postedIssueIds = issueDao.selectList(new LambdaQueryWrapper<com.erp.entity.vmi.MaterialIssue>()
                        .eq(com.erp.entity.vmi.MaterialIssue::getWorkOrderNo, mo.getMoNo())
                        .eq(com.erp.entity.vmi.MaterialIssue::getStatus, "POSTED"))
                .stream().map(com.erp.entity.vmi.MaterialIssue::getId).toList();
        Map<String, BigDecimal> posted = new HashMap<>();
        if (!postedIssueIds.isEmpty()) {
            for (com.erp.entity.vmi.MaterialIssueLine line : issueLineDao.selectList(
                    new LambdaQueryWrapper<com.erp.entity.vmi.MaterialIssueLine>()
                            .in(com.erp.entity.vmi.MaterialIssueLine::getIssueId, postedIssueIds))) {
                posted.merge(line.getItemCode(), nvl(line.getQty()), BigDecimal::add);
            }
        }
        // 非回加投料累计（HAND + 既往 BFLUSH，RETURNED_FLAG='0'）
        Map<String, BigDecimal> fed = new HashMap<>();
        for (MrpFeed f : feedDao.selectList(new LambdaQueryWrapper<MrpFeed>()
                .eq(MrpFeed::getMoId, mo.getId())
                .eq(MrpFeed::getReturnedFlag, "0"))) {
            fed.merge(f.getItemCode(), nvl(f.getQty()), BigDecimal::add);
        }
        List<Map<String, Object>> created = new ArrayList<>();
        List<String> shortages = new ArrayList<>();
        for (MrpMoBom b : bomRows) {
            BigDecimal required = nvl(mo.getQty())
                    .multiply(nvl(b.getUnitQty()))
                    .multiply(BigDecimal.ONE.add(nvl(b.getLossRate())))
                    .setScale(4, java.math.RoundingMode.HALF_UP);
            BigDecimal deficit = required
                    .subtract(fed.getOrDefault(b.getItemCode(), BigDecimal.ZERO))
                    .subtract(posted.getOrDefault(b.getItemCode(), BigDecimal.ZERO));
            if (deficit.signum() <= 0) {
                continue; // 已足额零重复扣（spec「已足额不重复扣」）
            }
            BigDecimal avail = availableOf(b.getItemCode());
            if (avail.compareTo(deficit) < 0) {
                shortages.add(b.getItemCode() + "（差额 "
                        + deficit.stripTrailingZeros().toPlainString() + " / 可用 "
                        + avail.stripTrailingZeros().toPlainString() + "）");
                continue;
            }
            deductAvailable(b.getItemCode(), deficit);
            MrpFeed feed = new MrpFeed();
            feed.setMoId(mo.getId());
            feed.setItemCode(b.getItemCode());
            feed.setItemName(b.getItemName());
            feed.setQty(deficit);
            feed.setFlowType(MrpFeed.FLOW_BFLUSH);
            feed.setReturnedFlag("0");
            feed.setFeedAt(LocalDateTime.now());
            feedDao.insert(feed);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", b.getItemCode());
            row.put("required", required);
            row.put("deficit", deficit);
            created.add(row);
        }
        if (!shortages.isEmpty()) {
            throw new ServiceException(422,
                    "倒冲补足缺料，阻断完工：" + String.join("；", shortages));
        }
        return created;
    }

    /** 物料当前可用量合计 */
    private BigDecimal availableOf(String itemCode) {
        return stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                        .eq(InvStock::getItemCode, itemCode)
                        .gt(InvStock::getAvailableQty, 0))
                .stream().map(s -> nvl(s.getAvailableQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** 行锁 FIFO 扣可用量（余量护栏；调用方先经 availableOf 校验） */
    private void deductAvailable(String itemCode, BigDecimal qty) {
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getItemCode, itemCode)
                .gt(InvStock::getAvailableQty, 0)
                .orderByAsc(InvStock::getInboundDate)
                .orderByAsc(InvStock::getBinCode)
                .last("FOR UPDATE"));
        BigDecimal left = qty;
        for (InvStock s : rows) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal rowAvail = nvl(s.getAvailableQty());
            if (rowAvail.signum() <= 0) {
                continue;
            }
            BigDecimal take = rowAvail.min(left);
            int upd = stockDao.update(null, new LambdaUpdateWrapper<InvStock>()
                    .eq(InvStock::getId, s.getId())
                    .ge(InvStock::getAvailableQty, take)
                    .setSql("AVAILABLE_QTY = AVAILABLE_QTY - " + take.toPlainString()));
            if (upd == 0) {
                throw new ServiceException(409, "倒冲扣减并发冲突，请重试：" + itemCode);
            }
            left = left.subtract(take);
        }
        if (left.signum() != 0) {
            throw new ServiceException(422, "可用量不足，倒冲扣减失败：" + itemCode);
        }
    }

    // ---------- 私有 ----------

    private String nextRptNo() {
        String prefix = "RP" + LocalDate.now().format(NO_FMT);
        long seq = reportingDao.selectCount(new LambdaQueryWrapper<MrpReporting>()
                .like(MrpReporting::getRptNo, prefix));
        return prefix + String.format("%03d", seq + 1);
    }

    /** 录入类写接口：SHOP_FLOOR/ADMIN（spec 角色与菜单权限——5.7.1 作业台） */
    private void requireEntry(String action) {
        requireAnyRole(action, "ROLE_SHOP_FLOOR", "ROLE_ADMIN");
    }

    /** 读接口：四角色（5.7.1 当班记录 + 5.7.2 管理台查询——spec「计划员可用管理台」） */
    private void requireRead(String action) {
        requireAnyRole(action, "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_ADMIN");
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

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
