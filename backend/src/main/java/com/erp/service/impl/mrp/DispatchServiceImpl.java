package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpWorkCenterDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.ReservationService;
import com.erp.service.system.NoticeService;
import com.erp.service.system.WorkCalendarService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 派工单管理实现（change add-dispatch-management，spec dispatch-management ADDED 十一需求）。
 * 前向排程（D3）：日容量 = 日历×班次（WorkCalendarService），既有占用（非 CANCELLED）
 * 按跨日均摊进 used 映射，新单按 [startFrom..] 逐日装箱（free = cap − used），跨天顺延；
 * 排程与重排共用 PACK_LOCK（实例级 synchronized 等价，防两个计划员并发互踩）。
 */
@Slf4j
@Service
public class DispatchServiceImpl implements DispatchService {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 排程守卫：超过该天数视为排不开（422） */
    private static final int MAX_PACK_DAYS = 730;
    /** 排程/重排互斥锁（同 JVM 内防并发装箱） */
    private static final Object PACK_LOCK = new Object();

    /** 缺料派工审批（C-4.5-12） */
    public static final String BIZ_SHORTAGE = "ShortageDispatch";
    /** 外协改派审批（BR-4.5-21） */
    public static final String BIZ_OUTSOURCE = "OutsourceDispatch";
    /** 审批结果通知 bizType（spec：审批结果推送申请人） */
    public static final String NOTICE_RESULT = "DISPATCH_APPROVAL_RESULT";
    /** 外协进展通知 bizType（spec「PR 漏斗下单与回联」：ORDERED/RECEIVED 推申请人） */
    public static final String NOTICE_OUTSOURCE_PROGRESS = "OUTSOURCE_PROGRESS";

    private final MrpDispatchDao dispatchDao;
    private final MrpMoDao moDao;
    private final MrpMoOpDao moOpDao;
    private final MrpWorkCenterDao workCenterDao;
    private final MdmSupplierDao supplierDao;
    private final WorkCalendarService calendarService;
    private final KittingService kittingService;
    private final ReservationService reservationService;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;
    /** 外协 PR 漏斗（design D5：审批通过自动生 PR，回联 sourceDocNo=DISPATCH_NO） */
    private final com.erp.service.proc.ProcRequisitionService requisitionService;
    /** 回联通知按外协审批实例取申请人（派工单不冗余 applyBy） */
    private final com.erp.dao.system.ApprovalInstanceDao approvalInstanceDao;
    /** 6.4 外协候选：定额矩阵 + 负荷 + 协议价参考（D8 仅展示） */
    private final com.erp.dao.mrp.MrpOpWcStandardDao opWcStandardDao;
    private final com.erp.service.mrp.CapacityLoadService capacityLoadService;
    private final com.erp.dao.proc.FrameworkAgreementDao agreementDao;
    private final com.erp.dao.proc.FrameworkAgreementLineDao agreementLineDao;
    /** 良率冻结挂点（C-4.5-03，5.6 D12 预留由 5.7 add-reporting-management 兑现） */
    private final com.erp.service.mrp.MoFreezeService moFreezeService;

    public DispatchServiceImpl(MrpDispatchDao dispatchDao, MrpMoDao moDao,
                               MrpMoOpDao moOpDao, MrpWorkCenterDao workCenterDao,
                               MdmSupplierDao supplierDao, WorkCalendarService calendarService,
                               KittingService kittingService, ReservationService reservationService,
                               // @Lazy 断环：ApprovalServiceImpl 收集回调 → 回调依赖本服务 → 本服务依赖审批引擎
                               @org.springframework.context.annotation.Lazy ApprovalEngine approvalEngine,
                               NoticeService noticeService,
                               com.erp.service.proc.ProcRequisitionService requisitionService,
                               com.erp.dao.system.ApprovalInstanceDao approvalInstanceDao,
                               com.erp.dao.mrp.MrpOpWcStandardDao opWcStandardDao,
                               com.erp.service.mrp.CapacityLoadService capacityLoadService,
                               com.erp.dao.proc.FrameworkAgreementDao agreementDao,
                               com.erp.dao.proc.FrameworkAgreementLineDao agreementLineDao,
                               com.erp.service.mrp.MoFreezeService moFreezeService) {
        this.dispatchDao = dispatchDao;
        this.moDao = moDao;
        this.moOpDao = moOpDao;
        this.workCenterDao = workCenterDao;
        this.supplierDao = supplierDao;
        this.calendarService = calendarService;
        this.kittingService = kittingService;
        this.reservationService = reservationService;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
        this.requisitionService = requisitionService;
        this.approvalInstanceDao = approvalInstanceDao;
        this.opWcStandardDao = opWcStandardDao;
        this.capacityLoadService = capacityLoadService;
        this.agreementDao = agreementDao;
        this.agreementLineDao = agreementLineDao;
        this.moFreezeService = moFreezeService;
    }

    // ---------- 待派列表 ----------

    @Override
    public List<MrpDispatch> list(String status, String keyword) {
        requireAny("查看派工单列表");
        LambdaQueryWrapper<MrpDispatch> qw = new LambdaQueryWrapper<MrpDispatch>()
                .eq(status != null && !status.isEmpty(), MrpDispatch::getStatus, status)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(MrpDispatch::getDispatchNo, keyword)
                        .or().like(MrpDispatch::getMoNo, keyword)
                        .or().like(MrpDispatch::getWcCode, keyword))
                .orderByDesc(MrpDispatch::getCreateDate);
        return dispatchDao.selectList(qw);
    }

    @Override
    public List<Map<String, Object>> pending(String keyword) {
        requireAny("查看待派工列表");
        List<MrpMo> mos = moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                .eq(MrpMo::getStatus, MrpMo.ST_RELEASED)
                .like(keyword != null && !keyword.isEmpty(), MrpMo::getMoNo, keyword)
                // spec FR-4.5-4-1：priority 升序、planStartDate 升序（D6 客户/金额层无数据）
                .orderByAsc(MrpMo::getPriority)
                .orderByAsc(MrpMo::getPlanStartDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpMo mo : mos) {
            List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                    .eq(MrpMoOp::getMoId, mo.getId())
                    .eq(MrpMoOp::getOpStatus, "PENDING")
                    .orderByAsc(MrpMoOp::getOpSeq));
            for (MrpMoOp op : ops) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("moId", mo.getId());
                row.put("moNo", mo.getMoNo());
                row.put("productCode", mo.getProductCode());
                row.put("productName", mo.getProductName());
                row.put("qty", mo.getQty());
                row.put("priority", mo.getPriority());
                row.put("planStartDate", mo.getPlanStartDate());
                row.put("planEndDate", mo.getPlanEndDate());
                row.put("shortageFlag", mo.getShortageFlag());
                row.put("opSeq", op.getOpSeq());
                row.put("opName", op.getOpName());
                row.put("snapshotWcCode", op.getWcCode());
                row.put("snapshotWcName", op.getWcName());
                row.put("durationHours", durationOf(op));
                out.add(row);
            }
        }
        return out;
    }

    // ---------- 生成（单/批量） ----------

    @Override
    @Transactional
    public List<MrpDispatch> generate(List<GenReq> rows) {
        requireAny("生成派工单");
        return doGenerate(rows, false);
    }

    @Override
    @Transactional
    public List<MrpDispatch> generateAfterShortageApproval(List<GenReq> rows) {
        requireAny("缺料派工生成");
        return doGenerate(rows, true);
    }

    private List<MrpDispatch> doGenerate(List<GenReq> rows, boolean shortageApproved) {
        if (rows == null || rows.isEmpty()) {
            throw new ServiceException(422, "派工工序必填");
        }
        List<MrpDispatch> out = new ArrayList<>();
        synchronized (PACK_LOCK) {
            Map<String, Map<LocalDate, BigDecimal>> usedCache = new HashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            for (GenReq req : rows) {
                String key = req.moId() + "#" + req.opSeq();
                if (!seen.add(key)) {
                    continue; // 批次内去重
                }
                out.add(createOne(req, shortageApproved, usedCache));
            }
        }
        return out;
    }

    private MrpDispatch createOne(GenReq req, boolean shortageApproved,
                                  Map<String, Map<LocalDate, BigDecimal>> usedCache) {
        if (req.moId() == null || req.opSeq() == null) {
            throw new ServiceException(422, "moId/opSeq 必填");
        }
        MrpMo mo = moDao.selectById(req.moId());
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + req.moId());
        }
        if (!MrpMo.ST_RELEASED.equals(mo.getStatus())) {
            throw new ServiceException(422, "仅已释放工单可派工，工单 " + mo.getMoNo()
                    + " 当前状态 " + mo.getStatus());
        }
        MrpMoOp op = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, req.moId())
                .eq(MrpMoOp::getOpSeq, req.opSeq()));
        if (op == null) {
            throw new ServiceException(404, "工序不存在：" + req.opSeq());
        }
        if (!"PENDING".equals(op.getOpStatus())) {
            throw new ServiceException(422, "工序 " + mo.getMoNo() + "/" + req.opSeq()
                    + " 已派工（opStatus=" + op.getOpStatus() + "）");
        }
        long dup = dispatchDao.selectCount(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getMoId, req.moId())
                .eq(MrpDispatch::getOpSeq, req.opSeq())
                .ne(MrpDispatch::getStatus, MrpDispatch.ST_CANCELLED));
        if (dup > 0) {
            throw new ServiceException(422, "工序已存在未取消派工单（防重）：" + mo.getMoNo()
                    + "/" + req.opSeq());
        }
        // 齐套 L1 门禁（C-4.5-11）：缺料待料 → 阻断并输出缺料清单与齐套率（read-only detail 不落库）
        if (!shortageApproved && "1".equals(mo.getShortageFlag())) {
            throw new ServiceException(422, kittingGateMessage(mo));
        }
        String wcCode = (req.wcCode() != null && !req.wcCode().isEmpty())
                ? req.wcCode() : op.getWcCode();
        if (wcCode == null || wcCode.isEmpty()) {
            throw new ServiceException(422, "工序无默认工作中心，请指定派工工作中心："
                    + mo.getMoNo() + "/" + req.opSeq());
        }
        validateWorkCenter(wcCode);
        // 良率冻结挂点（C-4.5-03，5.6 D12 预留由 5.7 兑现）：冻结中不可生成新派工
        moFreezeService.assertNotFrozen(mo.getId(), wcCode);

        BigDecimal duration = durationOf(op);
        LocalDate startFrom = mo.getPlanStartDate() != null ? mo.getPlanStartDate() : LocalDate.now();
        Map<LocalDate, BigDecimal> used = usedCache.computeIfAbsent(wcCode,
                w -> existingOccupancy(w));
        LocalDate[] span = pack(wcCode, startFrom, duration, used);

        MrpDispatch d = new MrpDispatch();
        d.setDispatchNo(nextDispatchNo());
        d.setMoId(mo.getId());
        d.setMoNo(mo.getMoNo());
        d.setOpSeq(op.getOpSeq());
        d.setOpName(op.getOpName());
        d.setWcCode(wcCode);
        d.setWcName(op.getWcCode() != null && op.getWcCode().equals(wcCode) ? op.getWcName() : wcNameOf(wcCode));
        d.setWcType(wcTypeOf(wcCode));
        d.setPlanStart(span[0]);
        d.setPlanEnd(span[1]);
        d.setDurationHours(duration);
        d.setStatus(MrpDispatch.ST_PENDING_CONFIRM);
        d.setRiskFlag(riskOf(mo, span[1]) ? "1" : "0");
        d.setShortageFirstFlag(shortageApproved ? "1" : "0");
        d.setOutsourceFlag("OUTSOURCED".equals(d.getWcType()) ? "1" : "0");
        d.setReceiptFlag("0");
        d.setCreateBy(SecurityUtils.getCurrentUserId());
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        dispatchDao.insert(d);
        // 汇入本批次占用缓存（后续行装箱感知）
        mergeUsed(used, span[0], span[1], duration);
        // 工序快照状态推进
        updateOpStatus(mo.getId(), op.getOpSeq(), "DISPATCHED");
        log.info("派工生成 {} {} op{} wc={} {}~{} dur={} risk={} shortageFirst={}",
                d.getDispatchNo(), mo.getMoNo(), op.getOpSeq(), wcCode,
                span[0], span[1], duration, d.getRiskFlag(), d.getShortageFirstFlag());
        return d;
    }

    // ---------- 确认 ----------

    @Override
    @Transactional
    public MrpDispatch confirm(String id) {
        requireAnyRole("确认派工单", "ROLE_SHOP_FLOOR", "ROLE_PLAN_MGR", "ROLE_ADMIN");
        MrpDispatch d = dispatchDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "派工单不存在：" + id);
        }
        if (!MrpDispatch.ST_PENDING_CONFIRM.equals(d.getStatus())) {
            throw new ServiceException(422, "仅待确认派工单可确认，当前 " + d.getStatus());
        }
        // 双点齐套校验（C-4.5-11，生成后到确认前标记可能翻转）：缺料且非缺料先行 → 422
        MrpMo mo = moDao.selectById(d.getMoId());
        if (mo != null && "1".equals(mo.getShortageFlag())
                && !"1".equals(d.getShortageFirstFlag())) {
            throw new ServiceException(422, kittingGateMessage(mo));
        }
        String operator = SecurityUtils.getCurrentUserId();
        int upd = dispatchDao.update(null, new LambdaUpdateWrapper<MrpDispatch>()
                .eq(MrpDispatch::getId, id)
                .eq(MrpDispatch::getStatus, MrpDispatch.ST_PENDING_CONFIRM)
                .set(MrpDispatch::getStatus, MrpDispatch.ST_RELEASED)
                .set(MrpDispatch::getConfirmBy, operator)
                .set(MrpDispatch::getConfirmAt, LocalDateTime.now()));
        if (upd == 0) {
            throw new ServiceException(409, "派工单确认并发冲突，请刷新重试");
        }
        // 确认触发物料预扣（spec：派工单确认时创建预扣；幂等锚点防重复扣）
        if (mo != null) {
            reservationService.reserveForMo(mo.getId(), operator);
        }
        log.info("派工确认 {} by {}", d.getDispatchNo(), operator);
        return dispatchDao.selectById(id);
    }

    // ---------- 取消 ----------

    @Override
    @Transactional
    public MrpDispatch cancel(String id, String reason) {
        requireAny("取消派工单");
        if (reason == null || reason.trim().isEmpty()) {
            throw new ServiceException(422, "取消原因必填（FR-4.5-4-6 派工变更留痕）");
        }
        MrpDispatch d = dispatchDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "派工单不存在：" + id);
        }
        if (MrpDispatch.ST_CANCELLED.equals(d.getStatus())) {
            throw new ServiceException(422, "派工单已取消");
        }
        String operator = SecurityUtils.getCurrentUserId();
        d.setStatus(MrpDispatch.ST_CANCELLED);
        d.setCancelReason(reason.trim());
        d.setUpdateBy(operator);
        dispatchDao.updateById(d);
        updateOpStatus(d.getMoId(), d.getOpSeq(), "PENDING");
        // MO 无其他活动派工（PENDING_CONFIRM/RELEASED）→ 全量释放预扣（多工序时其余派工仍活动则保留锁）
        long others = dispatchDao.selectCount(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getMoId, d.getMoId())
                .in(MrpDispatch::getStatus, MrpDispatch.ST_PENDING_CONFIRM, MrpDispatch.ST_RELEASED));
        if (others == 0) {
            reservationService.releaseByMo(d.getMoId(), "派工取消：" + reason.trim(), operator);
        }
        log.info("派工取消 {} reason={} by {}", d.getDispatchNo(), reason, operator);
        return dispatchDao.selectById(id);
    }

    // ---------- 重排 ----------

    @Override
    @Transactional
    public List<MrpDispatch> reschedule(String wcCode, LocalDate from) {
        requireAny("重新排程");
        if (wcCode == null || wcCode.isEmpty() || from == null) {
            throw new ServiceException(422, "重排需指定工作中心与起始日期");
        }
        synchronized (PACK_LOCK) {
            // 已确认占用固定（种子），未确认按优先级重装箱
            List<MrpDispatch> all = dispatchDao.selectList(new LambdaQueryWrapper<MrpDispatch>()
                    .eq(MrpDispatch::getWcCode, wcCode)
                    .ne(MrpDispatch::getStatus, MrpDispatch.ST_CANCELLED));
            Map<LocalDate, BigDecimal> used = new HashMap<>();
            List<MrpDispatch> affected = new ArrayList<>();
            for (MrpDispatch d : all) {
                if (MrpDispatch.ST_RELEASED.equals(d.getStatus())) {
                    if (d.getPlanStart() != null && !d.getPlanStart().isBefore(from)) {
                        mergeUsed(used, d.getPlanStart(), effectiveEnd(d),
                                nvl(d.getDurationHours()));
                    } else if (d.getPlanStart() != null && effectiveEnd(d).isAfter(from)) {
                        // 跨入窗口：仅计 from 起的均摊份额（v1 口径与负荷一致）
                        BigDecimal per = perDay(d);
                        for (LocalDate day = d.getPlanStart(); !day.isAfter(effectiveEnd(d));
                             day = day.plusDays(1)) {
                            if (!day.isBefore(from)) {
                                used.merge(day, per, BigDecimal::add);
                            }
                        }
                    }
                } else if (MrpDispatch.ST_PENDING_CONFIRM.equals(d.getStatus())
                        && (d.getPlanEnd() == null || !d.getPlanEnd().isBefore(from))) {
                    affected.add(d);
                }
            }
            if (affected.isEmpty()) {
                return new ArrayList<>();
            }
            // 优先级：MO priority → planStartDate → 单号（D6 口径）
            Map<String, MrpMo> moCache = new HashMap<>();
            affected.sort((a, b) -> {
                MrpMo ma = moCache.computeIfAbsent(a.getMoId(), moDao::selectById);
                MrpMo mb = moCache.computeIfAbsent(b.getMoId(), moDao::selectById);
                int c = Integer.compare(pri(ma), pri(mb));
                if (c != 0) return c;
                c = planStart(ma).compareTo(planStart(mb));
                if (c != 0) return c;
                return a.getDispatchNo().compareTo(b.getDispatchNo());
            });
            String operator = SecurityUtils.getCurrentUserId();
            String stamp = "重排 " + LocalDateTime.now().format(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " by " + operator;
            List<MrpDispatch> out = new ArrayList<>();
            for (MrpDispatch d : affected) {
                BigDecimal dur = nvl(d.getDurationHours());
                LocalDate[] span = pack(wcCode, from, dur, used);
                mergeUsed(used, span[0], span[1], dur);
                MrpMo mo = moCache.computeIfAbsent(d.getMoId(), moDao::selectById);
                d.setPlanStart(span[0]);
                d.setPlanEnd(span[1]);
                d.setRiskFlag(riskOf(mo, span[1]) ? "1" : "0");
                d.setRemark(stamp);
                d.setUpdateBy(operator);
                dispatchDao.updateById(d);
                out.add(d);
                log.info("重排 {} {}~{} risk={}", d.getDispatchNo(), span[0], span[1], d.getRiskFlag());
            }
            return out;
        }
    }

    // ---------- 审批链（group 5） ----------

    @Override
    @Transactional
    public Map<String, Object> applyShortage(String moId, Integer opSeq) {
        requireAny("缺料派工申请");
        if (moId == null || opSeq == null) {
            throw new ServiceException(422, "moId/opSeq 必填");
        }
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + moId);
        }
        if (!MrpMo.ST_RELEASED.equals(mo.getStatus())) {
            throw new ServiceException(422, "仅已释放工单可申请缺料派工：" + mo.getMoNo());
        }
        if (!"1".equals(mo.getShortageFlag())) {
            throw new ServiceException(422, "工单 " + mo.getMoNo()
                    + " 当前齐套，无需缺料先行派工（直接生成即可）");
        }
        requirePendingOp(mo, opSeq);
        ApprovalInstance inst = approvalEngine.submit(BIZ_SHORTAGE, moId + "#" + opSeq,
                "缺料派工申请：" + mo.getMoNo() + " 工序" + opSeq, null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PLAN_MGR", "计划主管"))));
        log.info("缺料派工申请 {} op{} by {}", mo.getMoNo(), opSeq,
                SecurityUtils.getCurrentUserId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apprId", inst.getId());
        out.put("apprNo", inst.getApprNo());
        out.put("status", inst.getStatus());
        out.put("moNo", mo.getMoNo());
        out.put("opSeq", opSeq);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> applyOutsource(String dispatchId, String targetWcCode) {
        // outsourcing 权限矩阵：申请仅 PLANNER/ADMIN（签核 PLAN_MGR）
        requireAnyRole("外协改派申请", "ROLE_PLANNER", "ROLE_ADMIN");
        MrpDispatch d = dispatchDao.selectById(dispatchId);
        if (d == null) {
            throw new ServiceException(404, "派工单不存在：" + dispatchId);
        }
        if (MrpDispatch.ST_CANCELLED.equals(d.getStatus())) {
            throw new ServiceException(422, "派工单已取消，不可改派：" + d.getDispatchNo());
        }
        if (targetWcCode == null || targetWcCode.isEmpty()) {
            throw new ServiceException(422, "目标外协工作中心必填");
        }
        MrpWorkCenter tw = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, targetWcCode));
        if (tw == null || !"OUTSOURCED".equals(tw.getWcType())) {
            throw new ServiceException(422, "目标工作中心须为外协类型：" + targetWcCode);
        }
        if (targetWcCode.equals(d.getWcCode())) {
            throw new ServiceException(422, "派工单已在此工作中心：" + targetWcCode);
        }
        validateWorkCenter(targetWcCode); // C-4.5-05 提交时校验（审批通过时再校验一次）
        ApprovalInstance inst = approvalEngine.submit(BIZ_OUTSOURCE,
                dispatchId + "#" + targetWcCode,
                "外协改派：" + d.getDispatchNo() + " → " + targetWcCode, null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PLAN_MGR", "计划主管"))));
        log.info("外协改派申请 {} → {} by {}", d.getDispatchNo(), targetWcCode,
                SecurityUtils.getCurrentUserId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apprId", inst.getId());
        out.put("apprNo", inst.getApprNo());
        out.put("status", inst.getStatus());
        out.put("dispatchNo", d.getDispatchNo());
        out.put("targetWcCode", targetWcCode);
        return out;
    }

    @Override
    @Transactional
    public MrpDispatch approveShortage(String moId, Integer opSeq, String applyBy, String apprNo) {
        MrpDispatch existing = activeDispatchOf(moId, opSeq);
        if (existing != null) {
            return existing; // 幂等：已生成（重复回调防护）
        }
        List<MrpDispatch> created = doGenerate(List.of(new GenReq(moId, opSeq, null)), true);
        // spec：审批通过时重读缺料标记刷新预扣基数——已有活动预扣则释放重建到当前口径
        if (!reservationService.activeOf(moId).isEmpty()) {
            reservationService.releaseByMo(moId, "缺料派工审批刷新", "approval");
            reservationService.reserveForMo(moId, "approval");
        }
        MrpMo mo = moDao.selectById(moId);
        notifyApplicant(applyBy, apprNo, "缺料派工申请已通过",
                (mo == null ? moId : mo.getMoNo()) + " 工序" + opSeq
                        + " 已生成缺料先行派工单，待车间主管确认（" + apprNo + "）");
        return created.get(0);
    }

    @Override
    @Transactional
    public void rejectShortage(String moId, Integer opSeq, String applyBy, String apprNo) {
        MrpMo mo = moDao.selectById(moId);
        notifyApplicant(applyBy, apprNo, "缺料派工申请已驳回",
                (mo == null ? moId : mo.getMoNo()) + " 工序" + opSeq
                        + " 的缺料派工申请被计划主管驳回（" + apprNo + "），未生成派工单");
        log.info("缺料派工驳回 {} op{} appr={}", moId, opSeq, apprNo);
    }

    @Override
    @Transactional
    public MrpDispatch approveOutsource(String dispatchId, String targetWcCode,
                                         String applyBy, String apprNo) {
        MrpDispatch d = dispatchDao.selectById(dispatchId);
        if (d == null) {
            log.warn("外协改派批准但派工单缺失：{}", dispatchId);
            return null;
        }
        if (MrpDispatch.ST_CANCELLED.equals(d.getStatus())) {
            throw new ServiceException(422, "派工单已取消，不可改派：" + d.getDispatchNo());
        }
        if (targetWcCode.equals(d.getWcCode()) && "1".equals(d.getOutsourceFlag())) {
            return d; // 幂等
        }
        validateWorkCenter(targetWcCode); // 通过时二次校验（提交后供应商可能被冻结）
        MrpWorkCenter tw = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, targetWcCode));
        d.setWcCode(targetWcCode);
        d.setWcName(tw == null ? targetWcCode : tw.getWcName());
        d.setWcType("OUTSOURCED");
        d.setOutsourceFlag("1");
        d.setUpdateBy(applyBy);
        // 外协 PR 漏斗（design D5 / spec「PR 漏斗下单与回联」）：通过即自动生 PR，
        // sourceDocNo=DISPATCH_NO 回联；prNo 已存在则跳过（审批重试幂等，不重复生 PR）
        String prNo = d.getPrNo();
        if (prNo == null || prNo.isEmpty()) {
            prNo = createOutsourcePr(d);
            d.setPrNo(prNo);
        }
        dispatchDao.updateById(d);
        notifyApplicant(applyBy, apprNo, "外协改派已通过",
                d.getDispatchNo() + " 已改派至外协工作中心 " + targetWcCode
                        + "（" + apprNo + "），已自动生成请购单 " + prNo
                        + "，请采购员在 2.1 待确认列表确认后转单");
        log.info("外协改派通过 {} → {} PR={}", d.getDispatchNo(), targetWcCode, prNo);
        return dispatchDao.selectById(dispatchId);
    }

    /**
     * 外协 PR 组装（design D5：行物料 = 工单产成品、数量 = MO.QTY、
     * reqDate = 派工单 PLAN_START——外协件须于计划开工前到位）。
     *
     * @return 新 PR 单号
     */
    private String createOutsourcePr(MrpDispatch d) {
        MrpMo mo = moDao.selectById(d.getMoId());
        if (mo == null) {
            throw new ServiceException(422, "外协生 PR 失败：工单不存在 " + d.getMoNo());
        }
        if (mo.getQty() == null || mo.getQty().signum() <= 0) {
            throw new ServiceException(422, "外协生 PR 失败：工单数量无效 " + mo.getMoNo());
        }
        if (d.getPlanStart() == null) {
            throw new ServiceException(422, "外协生 PR 失败：派工单无计划开始日（先排程）" + d.getDispatchNo());
        }
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("itemCode", mo.getProductCode()); // 工单产成品（外协加工回来即产成品）
        row.put("qty", mo.getQty());
        row.put("reqDate", d.getPlanStart().toString());
        String reason = "外协加工 " + d.getDispatchNo() + "（工单 " + mo.getMoNo()
                + " 序 " + d.getOpSeq() + "，外协 WC " + d.getWcCode() + "）";
        Map<String, Object> created = requisitionService.createForOutsource(
                List.of(row), d.getDispatchNo(), reason);
        return String.valueOf(created.get("prNo"));
    }

    @Override
    @Transactional
    public void rejectOutsource(String dispatchId, String applyBy, String apprNo) {
        MrpDispatch d = dispatchDao.selectById(dispatchId);
        notifyApplicant(applyBy, apprNo, "外协改派已驳回",
                (d == null ? dispatchId : d.getDispatchNo())
                        + " 的外协改派申请被驳回（" + apprNo + "），派工保持原状");
        log.info("外协改派驳回 appr={}", apprNo);
    }

    /** 审批结果通知：targetUser=申请人（spec：审批结果推送申请人） */
    private void notifyApplicant(String applyBy, String apprNo, String title, String content) {
        if (applyBy == null || applyBy.isEmpty()) {
            return;
        }
        noticeService.push(null, applyBy, title, content, NOTICE_RESULT, apprNo);
    }

    // ---------- 外协回联（design D5：采购域后置钩子，非外协静默跳过） ----------

    @Override
    @Transactional
    public boolean recallOrdered(String prNo, String poNo) {
        if (prNo == null || prNo.isEmpty() || poNo == null || poNo.isEmpty()) {
            return false;
        }
        MrpDispatch d = dispatchDao.selectOne(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getPrNo, prNo.trim())
                .last("LIMIT 1"));
        if (d == null || !"1".equals(d.getOutsourceFlag())) {
            return false; // 非外协派工的 PO（绝大多数）→ 静默零影响
        }
        if (poNo.equals(d.getPoNo())) {
            return false; // 幂等
        }
        d.setPoNo(poNo.trim());
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        dispatchDao.updateById(d);
        String applicant = outsourceApplicantOf(d.getId());
        if (applicant != null) {
            noticeService.push(null, applicant, "外协已下单",
                    d.getDispatchNo() + " 的外协请购 " + prNo + " 已下达采购订单 " + poNo.trim()
                            + "，等待供应商交货", NOTICE_OUTSOURCE_PROGRESS, d.getId() + "#ORDERED");
        }
        log.info("外协回联 ORDERED {} → {}", d.getDispatchNo(), poNo);
        return true;
    }

    @Override
    @Transactional
    public boolean recallReceived(String poNo) {
        if (poNo == null || poNo.isEmpty()) {
            return false;
        }
        MrpDispatch d = dispatchDao.selectOne(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getPoNo, poNo.trim())
                .last("LIMIT 1"));
        if (d == null || !"1".equals(d.getOutsourceFlag())) {
            return false;
        }
        if ("1".equals(d.getReceiptFlag())) {
            return false; // 幂等
        }
        d.setReceiptFlag("1");
        d.setUpdateBy(SecurityUtils.getCurrentUserId());
        dispatchDao.updateById(d);
        String applicant = outsourceApplicantOf(d.getId());
        if (applicant != null) {
            noticeService.push(null, applicant, "外协已到货",
                    d.getDispatchNo() + " 的外协物料已收货过账（PO " + poNo.trim()
                            + "），可安排后续工序/报工", NOTICE_OUTSOURCE_PROGRESS, d.getId() + "#RECEIVED");
        }
        log.info("外协回联 RECEIVED {} po={}", d.getDispatchNo(), poNo);
        return true;
    }

    /** 外协审批实例的申请人（该派工单最近一条 OutsourceDispatch 实例；无则 null 不推） */
    private String outsourceApplicantOf(String dispatchId) {
        List<com.erp.entity.system.ApprovalInstance> insts =
                approvalInstanceDao.selectList(new LambdaQueryWrapper<com.erp.entity.system.ApprovalInstance>()
                        .eq(com.erp.entity.system.ApprovalInstance::getBizType, BIZ_OUTSOURCE)
                        .like(com.erp.entity.system.ApprovalInstance::getBizId, dispatchId + "#")
                        .orderByDesc(com.erp.entity.system.ApprovalInstance::getApplyDate)
                        .last("LIMIT 1"));
        if (insts.isEmpty()) {
            return null;
        }
        String by = insts.get(0).getApplyBy();
        return by == null || by.isEmpty() ? null : by;
    }

    // ---------- 6.4 外协候选推荐（spec「可替代外协资源推荐」+「外协报价参考 D8」） ----------

    @Override
    public List<Map<String, Object>> outsourceCandidates(String opCode, String productCode,
                                                         String dispatchId) {
        requireAny("查看外协候选");
        // 页面超载下钻入口：按派工单经工序快照解析 opCode 与产成品（派工单不冗余 opCode）
        if (dispatchId != null && !dispatchId.trim().isEmpty()) {
            MrpDispatch d = dispatchDao.selectById(dispatchId.trim());
            if (d == null) {
                throw new ServiceException(404, "派工单不存在：" + dispatchId);
            }
            MrpMoOp op = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                    .eq(MrpMoOp::getMoId, d.getMoId())
                    .eq(MrpMoOp::getOpSeq, d.getOpSeq()));
            if (op == null || op.getOpCode() == null || op.getOpCode().isEmpty()) {
                throw new ServiceException(422, "派工单工序快照无编码（无法匹配定额矩阵）："
                        + d.getDispatchNo());
            }
            opCode = op.getOpCode();
            if (productCode == null || productCode.trim().isEmpty()) {
                MrpMo mo = moDao.selectById(d.getMoId());
                productCode = mo == null ? null : mo.getProductCode();
            }
        }
        if (opCode == null || opCode.trim().isEmpty()) {
            throw new ServiceException(422, "工序编码必填");
        }
        // 1) 定额矩阵：该工序存在定额的工作中心（"能做该工序"即候选来源）
        List<com.erp.entity.mrp.MrpOpWcStandard> stds =
                opWcStandardDao.selectList(new LambdaQueryWrapper<com.erp.entity.mrp.MrpOpWcStandard>()
                        .eq(com.erp.entity.mrp.MrpOpWcStandard::getOpCode, opCode.trim()));
        if (stds.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> wcCodes = new LinkedHashSet<>();
        for (com.erp.entity.mrp.MrpOpWcStandard s : stds) {
            if (s.getWcCode() != null && !s.getWcCode().isEmpty()) {
                wcCodes.add(s.getWcCode());
            }
        }
        if (wcCodes.isEmpty()) {
            return new ArrayList<>();
        }
        // 2) 类型=外协 + 启用（停用过滤）；供应商缺失/非 QUALIFIED 逐个过滤（C-4.5-05 前置）
        List<MrpWorkCenter> outsourced = workCenterDao.selectList(new LambdaQueryWrapper<MrpWorkCenter>()
                .in(MrpWorkCenter::getWcCode, wcCodes)
                .eq(MrpWorkCenter::getWcType, "OUTSOURCED")
                .eq(MrpWorkCenter::getStatus, "1"));
        // 3) 协议价参考（D8）：生效中/临期协议 + 今日有效期内（仅展示，不参与卡控）
        Map<String, BigDecimal> agreementPrice = agreementPriceOf(productCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpWorkCenter wc : outsourced) {
            if (wc.getSupplierCode() == null || wc.getSupplierCode().isEmpty()) {
                continue; // C-4.5-05：未维护供应商不可派工 → 不进候选
            }
            MdmSupplier sup = supplierDao.selectOne(new LambdaQueryWrapper<MdmSupplier>()
                    .eq(MdmSupplier::getSupplierCode, wc.getSupplierCode()));
            if (sup == null || !"QUALIFIED".equals(sup.getStatus())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("wcCode", wc.getWcCode());
            row.put("wcName", wc.getWcName());
            row.put("supplierCode", wc.getSupplierCode());
            row.put("supplierName", sup.getSupplierName());
            row.put("opCode", opCode.trim());
            // 今日负荷率（与 5.6.2 同一读口径；候选 WC 无派工单则 0%）
            java.math.BigDecimal rate = java.math.BigDecimal.ZERO;
            try {
                List<com.erp.service.mrp.CapacityLoadService.LoadRow> rows = capacityLoadService.loads(
                        LocalDate.now(), LocalDate.now(), wc.getWcCode());
                if (!rows.isEmpty() && rows.get(0).loadRate() != null) {
                    rate = rows.get(0).loadRate();
                }
            } catch (ServiceException ignore) {
                // 日历 DISABLED 缺行等查询异常 → 负荷按 0 展示（候选仍可用，不因负荷查询失败丢候选）
            }
            row.put("loadRate", rate);
            // 协议价参考（spec：有则「协议价 ¥X」无则「无协议价」→ 数值 + 文案双字段供页面直用）
            BigDecimal price = agreementPrice.get(sup.getId());
            row.put("agreementPrice", price);
            row.put("priceText", price == null ? "无协议价" : "协议价 ¥" + price.stripTrailingZeros().toPlainString());
            out.add(row);
        }
        // 负荷升序（先推荐闲的），同负荷按 WC 编码
        out.sort((a, b) -> {
            int c = new BigDecimal(String.valueOf(a.get("loadRate")))
                    .compareTo(new BigDecimal(String.valueOf(b.get("loadRate"))));
            return c != 0 ? c : String.valueOf(a.get("wcCode")).compareTo(String.valueOf(b.get("wcCode")));
        });
        return out;
    }

    /**
     * 协议价参考（D8）：status IN (1 生效中, 2 临期) 且今日在有效期内的协议，
     * 按供应商 ID 取该物料中标单价（多协议取第一个；无 → 空 map 页面显「无协议价」）。
     */
    private Map<String, BigDecimal> agreementPriceOf(String productCode) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (productCode == null || productCode.trim().isEmpty()) {
            return out;
        }
        List<com.erp.entity.proc.FrameworkAgreement> agrs =
                agreementDao.selectList(new LambdaQueryWrapper<com.erp.entity.proc.FrameworkAgreement>()
                        .in(com.erp.entity.proc.FrameworkAgreement::getStatus, "1", "2"));
        if (agrs.isEmpty()) {
            return out;
        }
        Set<String> agrIds = new LinkedHashSet<>();
        LocalDate today = LocalDate.now();
        for (com.erp.entity.proc.FrameworkAgreement a : agrs) {
            if (a.getEffectiveDate() != null && a.getExpireDate() != null
                    && !today.isBefore(a.getEffectiveDate()) && !today.isAfter(a.getExpireDate())) {
                agrIds.add(a.getId());
            }
        }
        if (agrIds.isEmpty()) {
            return out;
        }
        List<com.erp.entity.proc.FrameworkAgreementLine> lines =
                agreementLineDao.selectList(new LambdaQueryWrapper<com.erp.entity.proc.FrameworkAgreementLine>()
                        .in(com.erp.entity.proc.FrameworkAgreementLine::getAgreementId, agrIds)
                        .eq(com.erp.entity.proc.FrameworkAgreementLine::getItemCode,
                                productCode.trim().toUpperCase()));
        for (com.erp.entity.proc.FrameworkAgreementLine l : lines) {
            if (l.getAwardSupplierId() != null && l.getUnitPrice() != null) {
                out.putIfAbsent(l.getAwardSupplierId(), l.getUnitPrice());
            }
        }
        return out;
    }

    /** 该 (moId, opSeq) 的未取消派工单（幂等/防重用） */
    private MrpDispatch activeDispatchOf(String moId, Integer opSeq) {
        return dispatchDao.selectOne(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getMoId, moId)
                .eq(MrpDispatch::getOpSeq, opSeq)
                .ne(MrpDispatch::getStatus, MrpDispatch.ST_CANCELLED));
    }

    /** 申请前置：工序须存在且仍待派 */
    private void requirePendingOp(MrpMo mo, Integer opSeq) {
        MrpMoOp op = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId())
                .eq(MrpMoOp::getOpSeq, opSeq));
        if (op == null) {
            throw new ServiceException(404, "工序不存在：" + opSeq);
        }
        if (!"PENDING".equals(op.getOpStatus())) {
            throw new ServiceException(422, "工序已派工，无需缺料先行申请："
                    + mo.getMoNo() + "/" + opSeq);
        }
        if (activeDispatchOf(mo.getId(), opSeq) != null) {
            throw new ServiceException(422, "工序已存在未取消派工单："
                    + mo.getMoNo() + "/" + opSeq);
        }
    }

    // ---------- 排程核心（D3 前向时段装箱） ----------

    /**
     * 把 duration 小时装入 wc 自 startFrom 起的日队列：free = 日容量 − used，
     * 当日不足顺延次日（非工作日容量 0 自动跳过）；返回 [planStart, planEnd]。
     * duration ≤ 0 → 落在首个可用工作日（占 0 小时）。
     */
    private LocalDate[] pack(String wcCode, LocalDate startFrom, BigDecimal duration,
                             Map<LocalDate, BigDecimal> used) {
        LocalDate cursor = startFrom;
        BigDecimal left = duration;
        LocalDate firstDay = null;
        for (int i = 0; i < MAX_PACK_DAYS; i++) {
            BigDecimal cap = calendarService.availableHours(cursor, wcCode);
            BigDecimal usedToday = used.getOrDefault(cursor, BigDecimal.ZERO);
            BigDecimal free = cap.subtract(usedToday);
            if (left.signum() <= 0) {
                // 0 工时或已装完：首个有余量的工作日即落点（0 工时只找工作日）
                if (cap.signum() > 0) {
                    return new LocalDate[]{cursor, cursor};
                }
                cursor = cursor.plusDays(1);
                continue;
            }
            if (free.signum() <= 0) {
                cursor = cursor.plusDays(1);
                continue;
            }
            BigDecimal take = free.min(left);
            if (firstDay == null) {
                firstDay = cursor;
            }
            left = left.subtract(take);
            if (left.signum() <= 0) {
                return new LocalDate[]{firstDay, cursor};
            }
            cursor = cursor.plusDays(1);
        }
        throw new ServiceException(422, "排程失败：自 " + startFrom + " 起 " + MAX_PACK_DAYS
                + " 天内无法排入工作中心 " + wcCode + "（日历/班次容量不足）");
    }

    /** 既有占用（非 CANCELLED）：按 [PLAN_START, PLAN_END] 跨日均摊（与负荷 v1 口径一致） */
    private Map<LocalDate, BigDecimal> existingOccupancy(String wcCode) {
        Map<LocalDate, BigDecimal> used = new HashMap<>();
        List<MrpDispatch> list = dispatchDao.selectList(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getWcCode, wcCode)
                .ne(MrpDispatch::getStatus, MrpDispatch.ST_CANCELLED));
        for (MrpDispatch d : list) {
            if (d.getPlanStart() == null) {
                continue;
            }
            mergeUsed(used, d.getPlanStart(), effectiveEnd(d), nvl(d.getDurationHours()));
        }
        return used;
    }

    private void mergeUsed(Map<LocalDate, BigDecimal> used, LocalDate start, LocalDate end,
                           BigDecimal duration) {
        if (start == null || duration.signum() <= 0) {
            return;
        }
        long span = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        BigDecimal per = duration.divide(BigDecimal.valueOf(span), 4, RoundingMode.HALF_UP);
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            used.merge(day, per, BigDecimal::add);
        }
    }

    private BigDecimal perDay(MrpDispatch d) {
        long span = java.time.temporal.ChronoUnit.DAYS.between(d.getPlanStart(),
                effectiveEnd(d)) + 1;
        return nvl(d.getDurationHours()).divide(BigDecimal.valueOf(span), 4, RoundingMode.HALF_UP);
    }

    // ---------- 校验/杂项 ----------

    /** 齐套门禁消息：read-only detail 取齐套率与缺料行（不落库），拼 422 文案（spec：输出清单+齐套率） */
    private String kittingGateMessage(MrpMo mo) {
        StringBuilder sb = new StringBuilder();
        sb.append("工单 ").append(mo.getMoNo()).append(" 缺料待料，禁止正常派工（C-4.5-11）：");
        try {
            Map<String, Object> detail = kittingService.detail(mo.getId());
            sb.append("齐套率 ").append(detail.get("rate")).append("%");
            @SuppressWarnings("unchecked")
            var lines = (List<Map<String, Object>>) detail.get("lines");
            int n = 0;
            StringBuilder items = new StringBuilder();
            if (lines != null) {
                for (Map<String, Object> l : lines) {
                    if (Boolean.TRUE.equals(l.get("shortage"))) {
                        n++;
                        if (n <= 5) {
                            items.append("、").append(l.get("itemCode"))
                                    .append(" 缺").append(l.get("shortQty"));
                        }
                    }
                }
            }
            if (n > 0) {
                sb.append("，缺料 ").append(n).append(" 行（").append(items)
                        .append(n > 5 ? "…" : "").append("）");
            }
        } catch (RuntimeException ignore) {
            sb.append("（缺料明细取数失败）");
        }
        sb.append("。确需先行开工请走缺料派工申请（C-4.5-12，计划主管审批）");
        return sb.toString();
    }

    /** C-4.5-05 运行时二次校验：停用阻断；外协类型供应商须存在且 QUALIFIED（启用） */
    private void validateWorkCenter(String wcCode) {
        MrpWorkCenter wc = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, wcCode));
        if (wc == null) {
            throw new ServiceException(422, "工作中心不存在：" + wcCode);
        }
        if (!"1".equals(wc.getStatus())) {
            throw new ServiceException(422, "工作中心已停用，不可派工：" + wcCode);
        }
        if ("OUTSOURCED".equals(wc.getWcType())) {
            if (wc.getSupplierCode() == null || wc.getSupplierCode().isEmpty()) {
                throw new ServiceException(422, "外协工作中心未维护供应商，不可派工（C-4.5-05）："
                        + wcCode);
            }
            MdmSupplier sup = supplierDao.selectOne(new LambdaQueryWrapper<MdmSupplier>()
                    .eq(MdmSupplier::getSupplierCode, wc.getSupplierCode()));
            if (sup == null || !"QUALIFIED".equals(sup.getStatus())) {
                throw new ServiceException(422, "外协工作中心的供应商未启用（非 QUALIFIED），"
                        + "不可派工（C-4.5-05）：" + wcCode + " → " + wc.getSupplierCode());
            }
        }
    }

    private void updateOpStatus(String moId, Integer opSeq, String status) {
        int upd = moOpDao.update(null, new LambdaUpdateWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .eq(MrpMoOp::getOpSeq, opSeq)
                .set(MrpMoOp::getOpStatus, status));
        if (upd == 0) {
            throw new ServiceException(409, "工序状态更新冲突：" + moId + "/" + opSeq);
        }
    }

    /** 工序占用工时 = setup + run（D3 口径：排队 wait/移动 move 不占产能） */
    private BigDecimal durationOf(MrpMoOp op) {
        BigDecimal setup = nvl(op.getSetupHours());
        BigDecimal run = nvl(op.getRunHours());
        return setup.add(run);
    }

    private boolean riskOf(MrpMo mo, LocalDate planEnd) {
        return mo.getPlanEndDate() != null && planEnd != null && planEnd.isAfter(mo.getPlanEndDate());
    }

    private LocalDate effectiveEnd(MrpDispatch d) {
        if (d.getPlanEnd() == null || d.getPlanEnd().isBefore(d.getPlanStart())) {
            return d.getPlanStart();
        }
        return d.getPlanEnd();
    }

    private int pri(MrpMo mo) {
        return mo == null || mo.getPriority() == null ? 99 : mo.getPriority();
    }

    private LocalDate planStart(MrpMo mo) {
        return mo == null || mo.getPlanStartDate() == null ? LocalDate.MAX : mo.getPlanStartDate();
    }

    private String wcNameOf(String wcCode) {
        MrpWorkCenter wc = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, wcCode));
        return wc == null ? wcCode : wc.getWcName();
    }

    private String wcTypeOf(String wcCode) {
        MrpWorkCenter wc = workCenterDao.selectOne(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, wcCode));
        return wc == null ? "INTERNAL" : wc.getWcType();
    }

    /** 派工单号：DP + yyyyMMdd + 3 位流水（PACK_LOCK 内调用，无并发重号） */
    private String nextDispatchNo() {
        String prefix = "DP" + LocalDate.now().format(NO_FMT);
        long seq = dispatchDao.selectCount(new LambdaQueryWrapper<MrpDispatch>()
                .like(MrpDispatch::getDispatchNo, prefix));
        return prefix + String.format("%03d", seq + 1);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ---------- 权限 ----------

    /** 派工类写/读入口：PLANNER/PLAN_MGR/SHOP_FLOOR/ADMIN 之一（spec 角色与菜单权限） */
    private void requireAny(String action) {
        requireAnyRole(action, "ROLE_PLANNER", "ROLE_PLAN_MGR", "ROLE_SHOP_FLOOR", "ROLE_ADMIN");
    }

    private void requireAnyRole(String action, String... allowed) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String want : allowed) {
            if (auth.getAuthorities().contains(new SimpleGrantedAuthority(want))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action + "（需 " + String.join("/", allowed) + "）");
    }
}
