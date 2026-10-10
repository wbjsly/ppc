package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.mrp.MrpMoBomDao;
import com.erp.dao.mrp.MrpMoDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpMoShortageDao;
import com.erp.dao.mrp.MrpSuggestionDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpMoShortage;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoCloseCheck;
import com.erp.service.mrp.MoDataSource;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.MoStatusRules;
import com.erp.service.mrp.MrpPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 生产工单实现（change add-work-order-management，spec work-order-management / 00-erp-spec 4.5-3）。
 *
 * 创建：手工/PMO 双入口（D6 回写幂等）→ 头卡控（C-4.5-06 完工日期 L1、唯一在途父项行锁、
 * BR-4.5-15 超交 5%）→ BOM/路线双快照（BR-4.5-16 隔离；无路线提示放行、WC 停用 L1、无定额提示）
 * → KittingService 预检打标（缺料不阻断，L2496 场景）。
 * 状态：MoStatusRules 白名单 + CAS（先 updateById 持久化留痕再 CAS，mrp-plan 模式）。
 * 审批：ApprovalEngine 串行单节点（ROLE_PLAN_MGR），回调 onApproved/onRejected。
 * 关闭：状态 COMPLETED 硬校验 + MoCloseCheck 钩子（D3 占位空通过；1.3 在制无数据源降级）。
 * 权限：写 = PLANNER/ADMIN；读 = 需登录；签署侧 = 审批底座节点管（6.4）。
 */
@Slf4j
@Service
public class MoServiceImpl implements MoService {

    private static final DateTimeFormatter MO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MrpMoDao moDao;
    private final MrpMoBomDao moBomDao;
    private final MrpMoOpDao moOpDao;
    private final MrpMoShortageDao shortageDao;
    private final MrpSuggestionDao suggestionDao;
    private final MdmItemDao mdmItemDao;
    private final MoDataSource moDataSource;
    private final KittingService kittingService;
    private final ApprovalEngine approvalEngine;
    private final List<MoCloseCheck> closeChecks;

    public MoServiceImpl(MrpMoDao moDao, MrpMoBomDao moBomDao, MrpMoOpDao moOpDao,
                         MrpMoShortageDao shortageDao, MrpSuggestionDao suggestionDao,
                         MdmItemDao mdmItemDao, MoDataSource moDataSource,
                         KittingService kittingService, ApprovalEngine approvalEngine,
                         List<MoCloseCheck> closeChecks) {
        this.moDao = moDao;
        this.moBomDao = moBomDao;
        this.moOpDao = moOpDao;
        this.shortageDao = shortageDao;
        this.suggestionDao = suggestionDao;
        this.mdmItemDao = mdmItemDao;
        this.moDataSource = moDataSource;
        this.kittingService = kittingService;
        this.approvalEngine = approvalEngine;
        this.closeChecks = closeChecks == null ? List.of() : closeChecks;
    }

    // ==================== 5.4.1 创建 ====================

    @Override
    public List<Map<String, Object>> candidatePmos() {
        requireLogin("查看可选计划工单");
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpSuggestion s : moDataSource.candidatePmos()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("pmoNo", s.getTargetNo());
            m.put("itemCode", s.getItemCode());
            m.put("itemName", s.getItemName());
            m.put("qty", s.getConfirmQty() != null ? s.getConfirmQty() : s.getSuggestQty());
            m.put("reqDate", s.getConfirmDate() != null ? s.getConfirmDate() : s.getReqDate());
            m.put("remark", s.getRemark());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(MrpMo head) {
        requireAny("创建工单");
        return doCreate(head, null);
    }

    @Override
    @Transactional
    public Map<String, Object> createFromPmo(String suggestId, MrpMo overrides) {
        requireAny("创建工单");
        MrpSuggestion s = suggestionDao.selectById(suggestId);
        if (s == null) {
            throw new ServiceException(404, "计划建议不存在：" + suggestId);
        }
        if (!MrpSuggestion.TYPE_PRODUCTION.equals(s.getType())
                || !MrpSuggestion.ST_CONVERTED.equals(s.getStatus())
                || s.getTargetNo() == null || !s.getTargetNo().startsWith(MrpPlanService.PMO_PREFIX)) {
            throw new ServiceException(422, "该建议非已转正的计划工单（PMO），不能用于建单");
        }
        if (s.getMoNo() != null && !s.getMoNo().isEmpty()) {
            throw new ServiceException(422, "该计划工单已关联工单：" + s.getMoNo());
        }
        MrpMo head = overrides == null ? new MrpMo() : overrides;
        head.setProductCode(s.getItemCode());
        head.setProductName(s.getItemName());
        if (head.getQty() == null) {
            head.setQty(s.getConfirmQty() != null ? s.getConfirmQty() : s.getSuggestQty());
        }
        if (head.getPlanStartDate() == null) {
            head.setPlanStartDate(LocalDate.now());
        }
        if (head.getPlanEndDate() == null) {
            LocalDate suggested = s.getConfirmDate() != null ? s.getConfirmDate() : s.getReqDate();
            // 设计风险预案：PMO 建议日期可能逾期——默认值兜底顺延（显式传值仍走 C-4.5-06 校验）
            LocalDate fallback = LocalDate.now().plusDays(7);
            head.setPlanEndDate(suggested == null || suggested.isBefore(LocalDate.now())
                    ? fallback : suggested);
        }
        if (head.getPriority() == null) {
            head.setPriority(5);
        }
        return doCreate(head, s);
    }

    /** 共享创建主流程（手工入口 suggestion=null） */
    private Map<String, Object> doCreate(MrpMo head, MrpSuggestion pmo) {
        // ---- 头校验（FR-4.5-3-2 / C-4.5-06 / BR-4.5-15）----
        if (head == null || !isNotBlank(head.getProductCode())) {
            throw new ServiceException(422, "产品编码必填");
        }
        String productCode = head.getProductCode().trim();
        MdmItem product = requireActiveMakeItem(productCode);
        if (head.getQty() == null || head.getQty().signum() <= 0) {
            throw new ServiceException(422, "计划数量必须大于 0（FR-4.5-3-2）");
        }
        if (head.getPlanEndDate() == null) {
            throw new ServiceException(422, "计划完工日期必填");
        }
        if (head.getPlanEndDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "计划完工日期早于当前日期，无法创建（C-4.5-06）");
        }
        if (head.getPlanStartDate() == null) {
            head.setPlanStartDate(LocalDate.now());
        }
        if (head.getPriority() == null) {
            head.setPriority(5);
        }
        // 唯一在途：父项行锁 + 在途阻断（design D1，BOM 父项锁先例）
        lockProduct(productCode);
        requireNoInFlight(productCode);
        // 超交 5%（BR-4.5-15）：存在该产品 SO 未交需求时适用；无需求（备库存）跳过
        var demand = moDataSource.base().soDemands(Set.of(productCode)).get(productCode);
        BigDecimal outstanding = demand == null ? BigDecimal.ZERO : demand.qty;
        if (outstanding.signum() > 0) {
            BigDecimal limit = outstanding.multiply(BigDecimal.valueOf(1.05));
            if (head.getQty().compareTo(limit) > 0) {
                throw new ServiceException(422,
                        "计划数量超过销售订单需求量（未交 " + outstanding.stripTrailingZeros().toPlainString()
                                + " × 105% 超交上限），BR-4.5-15");
            }
        }

        // ---- 双快照（FR-4.5-3-3/4）----
        MoDataSource.BomSnapshot bomSnap = moDataSource.publishedBomSnapshot(productCode);
        if (bomSnap == null) {
            throw new ServiceException(422, "产品无已发布 BOM，阻断创建（FR-4.5-3-3），请先在 5.1 发布 BOM");
        }
        MoDataSource.RoutingSnapshot routingSnap = moDataSource.publishedRoutingSnapshot(productCode);
        List<String> warnings = new ArrayList<>();
        List<MrpRoutingOp> opsToPersist = new ArrayList<>();
        if (routingSnap == null) {
            warnings.add("产品无已发布工艺路线，工序快照为空，可在 5.2 路线装配发布后变更补录（FR-4.5-3-4 提示放行）");
        } else {
            Map<String, MrpWorkCenter> wcs = moDataSource.workCenters(
                    routingSnap.ops.stream().map(MrpRoutingOp::getWcCode)
                            .filter(java.util.Objects::nonNull).toList());
            for (MrpRoutingOp op : routingSnap.ops) {
                if (op.getWcCode() != null && !op.getWcCode().isEmpty()) {
                    MrpWorkCenter wc = wcs.get(op.getWcCode());
                    if (wc == null) {
                        throw new ServiceException(422,
                                "工作中心不存在：" + op.getWcCode() + "（工序 " + op.getOpSeq() + "，FR-4.5-3-4 阻断分配）");
                    }
                    if (!MrpWorkCenter.ST_ACTIVE.equals(wc.getStatus())) {
                        throw new ServiceException(422,
                                "工作中心已停用：" + op.getWcCode() + "（工序 " + op.getOpSeq() + "，FR-4.5-3-4 阻断分配）");
                    }
                    MrpOpWcStandard std = moDataSource.standardOf(op.getOpCode(), op.getWcCode());
                    if (std == null) {
                        warnings.add("工序 " + op.getOpCode() + " × 工作中心 " + op.getWcCode()
                                + " 未维护工时定额，请先在 5.2.3 标准工时页维护后变更补录（FR-4.5-3-4 提示更换）");
                    }
                } else {
                    warnings.add("工序 " + op.getOpSeq() + "（" + op.getOpCode() + "）未分配工作中心，请在 5.2.4 检查路线");
                }
                opsToPersist.add(op);
            }
        }

        // ---- 落库（MO_NO 流水 + 快照行 + 预检）----
        MrpMo mo = new MrpMo();
        mo.setProductCode(productCode);
        mo.setProductName(product.getItemName());
        mo.setQty(head.getQty());
        mo.setPlanStartDate(head.getPlanStartDate());
        mo.setPlanEndDate(head.getPlanEndDate());
        mo.setPriority(head.getPriority());
        mo.setStatus(MrpMo.ST_PLANNED);
        mo.setShortageFlag("0");
        mo.setSourceBomId(bomSnap.bomId);
        mo.setBomVersion(bomSnap.bomVersion);
        mo.setSourceRoutingId(routingSnap == null ? null : routingSnap.routingId);
        if (pmo != null) {
            mo.setPlannedMoNo(pmo.getTargetNo());
        }
        insertMoResolvingConflict(mo);
        if (pmo != null) {
            // D6 幂等回写：同事务 UPDATE ... WHERE MO_NO IS NULL，行数 0 → 已关联 422
            int rows = suggestionDao.update(null, new LambdaUpdateWrapper<MrpSuggestion>()
                    .eq(MrpSuggestion::getId, pmo.getId())
                    .isNull(MrpSuggestion::getMoNo)
                    .set(MrpSuggestion::getMoNo, mo.getMoNo()));
            if (rows == 0) {
                throw new ServiceException(422, "该计划工单已被其他工单占用（并发），请刷新列表");
            }
            pmo.setMoNo(mo.getMoNo());
        }

        int lineNo = 0;
        for (MoDataSource.BomSnapshotLine l : bomSnap.lines) {
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
        for (MrpRoutingOp op : opsToPersist) {
            MrpMoOp row = new MrpMoOp();
            row.setMoId(mo.getId());
            row.setOpSeq(op.getOpSeq());
            row.setOpCode(op.getOpCode());
            row.setOpName(op.getOpName());
            row.setWcCode(op.getWcCode());
            row.setWcName(op.getWcName());
            row.setLeadTime(op.getLeadTime());
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
        // 库存预检（FR-4.5-3-6：内嵌调用唯一齐套算法，缺料不阻断仅标记）
        BigDecimal rate = kittingService.check(mo.getId());
        // check() 内部更新的是其加载的副本——刷新实体再取标记，避免警告文案读到内存旧值
        MrpMo fresh = moDao.selectById(mo.getId());
        warnings.add("齐套率 " + rate + "%"
                + ("1".equals(fresh.getShortageFlag()) ? "（已标缺料待料）" : "（齐套）"));

        log.info("工单创建：{} 产品={} 数量={} BOM={} 快照行={}",
                mo.getMoNo(), productCode, mo.getQty(), bomSnap.bomVersion, lineNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mo", fresh);
        out.put("warnings", warnings);
        return out;
    }

    // ==================== 列表与详情 ====================

    @Override
    public List<MrpMo> list(String status, String productCode, String shortageFlag) {
        requireLogin("查询工单");
        LambdaQueryWrapper<MrpMo> w = new LambdaQueryWrapper<>();
        if (isNotBlank(status)) {
            w.eq(MrpMo::getStatus, status.trim());
        }
        if (isNotBlank(productCode)) {
            w.eq(MrpMo::getProductCode, productCode.trim());
        }
        if (isNotBlank(shortageFlag)) {
            w.eq(MrpMo::getShortageFlag, shortageFlag.trim());
        }
        return moDao.selectList(w.orderByDesc(MrpMo::getCreateDate));
    }

    @Override
    public Map<String, Object> detail(String moId) {
        requireLogin("查询工单");
        MrpMo mo = requireMo(moId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mo", mo);
        out.put("bomLines", moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                .eq(MrpMoBom::getMoId, moId).orderByAsc(MrpMoBom::getLineNo)));
        out.put("ops", moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId).orderByAsc(MrpMoOp::getOpSeq)));
        out.put("shortages", kittingService.shortages(moId));
        return out;
    }

    @Override
    public List<MrpMoShortage> shortages(String moId) {
        requireLogin("查询缺料清单");
        requireMo(moId);
        return kittingService.shortages(moId);
    }

    // ==================== 5.4.2 审批 ====================

    @Override
    @Transactional
    public MrpMo submit(String moId) {
        requireAny("提交工单审批");
        MrpMo mo = requireMo(moId);
        if (!MrpMo.ST_PLANNED.equals(mo.getStatus())) {
            // 重复提交（PENDING）与其他状态一并落此 422
            throw new ServiceException(422,
                    "仅计划状态可提交审核（当前状态：" + mo.getStatus() + "，重复提交请等待审批结果）");
        }
        // 挂审批底座单节点（design D3）：重复提交由底座「同一业务 PENDING 实例」422 拒绝
        approvalEngine.submit(BIZ_APPROVE, mo.getId(),
                "工单审批：" + mo.getMoNo() + " " + mo.getProductName(),
                null,
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_PLAN_MGR", "计划主管"))));
        mo.setSubmitBy(currentOperator());
        mo.setSubmitAt(LocalDateTime.now());
        casStatus(mo, MrpMo.ST_PENDING);
        log.info("工单提交审批：{} 操作人={}", mo.getMoNo(), mo.getSubmitBy());
        return mo;
    }

    @Override
    @Transactional
    public void onApproved(String moId) {
        // 回调与签署同事务（ApprovalEngine 契约）；签署者 = 当前认证用户
        MrpMo mo = requireMo(moId);
        mo.setApproveBy(currentOperator());
        mo.setApproveAt(LocalDateTime.now());
        casStatus(mo, MrpMo.ST_CONFIRMED);
        log.info("工单审批通过：{} 审批人={}", mo.getMoNo(), mo.getApproveBy());
    }

    @Override
    @Transactional
    public void onRejected(String moId, String reason) {
        MrpMo mo = requireMo(moId);
        mo.setRejectReason(isNotBlank(reason) ? reason.trim() : null);
        casStatus(mo, MrpMo.ST_PLANNED);
        log.info("工单审批驳回：{} 意见={}", mo.getMoNo(), mo.getRejectReason());
    }

    // ==================== 5.4.3 释放 ====================

    @Override
    @Transactional
    public MrpMo release(String moId) {
        requireAny("释放工单");
        MrpMo mo = requireMo(moId);
        if (!MrpMo.ST_CONFIRMED.equals(mo.getStatus())) {
            throw new ServiceException(422,
                    "仅已确认状态可释放（当前状态：" + mo.getStatus() + "）");
        }
        mo.setReleaseBy(currentOperator());
        mo.setReleaseAt(LocalDateTime.now());
        casStatus(mo, MrpMo.ST_RELEASED);
        // 释放时重跑齐套（FR-4.5-3-6/7）：缺料不阻断，仅打「缺料待料」标记（L2496 场景）
        kittingService.check(moId);
        log.info("工单释放：{} 缺料标记={}", mo.getMoNo(), mo.getShortageFlag());
        return moDao.selectById(moId);
    }

    // ==================== 5.4.4 变更 ====================

    @Override
    @Transactional
    public MrpMo hold(String moId, String reason) {
        requireAny("挂起工单");
        MrpMo mo = requireMo(moId);
        if (!MrpMo.ST_RELEASED.equals(mo.getStatus())) {
            throw new ServiceException(422, "仅已释放状态可挂起（当前状态：" + mo.getStatus() + "）");
        }
        if (!isNotBlank(reason)) {
            throw new ServiceException(422, "挂起原因必填（FR-4.5-3-7）");
        }
        mo.setHoldReason(reason.trim());
        casStatus(mo, MrpMo.ST_HOLD);
        log.info("工单挂起：{} 原因={}", mo.getMoNo(), reason);
        return mo;
    }

    @Override
    @Transactional
    public MrpMo resume(String moId) {
        requireAny("恢复工单");
        MrpMo mo = requireMo(moId);
        casStatus(mo, MrpMo.ST_RELEASED);
        log.info("工单挂起恢复：{}", mo.getMoNo());
        return mo;
    }

    @Override
    @Transactional
    public MrpMo cancel(String moId, String reason) {
        requireAny("取消工单");
        MrpMo mo = requireMo(moId);
        if (!MoStatusRules.canTransit(mo.getStatus(), MrpMo.ST_CANCELLED)) {
            throw new ServiceException(422,
                    "当前状态不可取消（" + mo.getStatus() + "；审批在途请先驳回）");
        }
        if (!isNotBlank(reason)) {
            throw new ServiceException(422, "取消原因必填（FR-4.5-3-7）");
        }
        mo.setCancelReason(reason.trim());
        casStatus(mo, MrpMo.ST_CANCELLED);
        log.info("工单取消：{} 原因={}", mo.getMoNo(), reason);
        return mo;
    }

    @Override
    @Transactional
    public Map<String, Object> split(String moId, List<BigDecimal> childQtys, String reason) {
        requireAny("拆分工单");
        MrpMo parent = requireMo(moId);
        if (List.of(MrpMo.ST_PLANNED, MrpMo.ST_PENDING, MrpMo.ST_CONFIRMED)
                .contains(parent.getStatus())) {
            throw new ServiceException(422, "计划/待审核/已确认状态不允许拆分");
        }
        if (!MrpMo.ST_RELEASED.equals(parent.getStatus()) && !MrpMo.ST_HOLD.equals(parent.getStatus())) {
            throw new ServiceException(422, "仅已释放/挂起状态可拆分（当前状态：" + parent.getStatus() + "）");
        }
        if (childQtys == null || childQtys.isEmpty()) {
            throw new ServiceException(422, "至少拆分一个子工单");
        }
        BigDecimal remaining = parent.getQty().subtract(
                parent.getQualifiedQty() == null ? BigDecimal.ZERO : parent.getQualifiedQty());
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal q : childQtys) {
            if (q == null || q.signum() <= 0) {
                throw new ServiceException(422, "拆分数量必须大于 0");
            }
            sum = sum.add(q);
        }
        if (sum.compareTo(remaining) != 0) {
            // C-4.5-15 L1 + 留痕（失败走日志，事务回滚不留行）
            log.warn("工单拆分数量校验失败：{} 合计={} 剩余={}", parent.getMoNo(), sum, remaining);
            throw new ServiceException(422,
                    "拆分子单数量合计（" + sum.stripTrailingZeros().toPlainString()
                            + "）须等于原单剩余未完工数量（" + remaining.stripTrailingZeros().toPlainString()
                            + "），C-4.5-15");
        }
        // 克隆头 + 快照行（BR-4.5-05 子单继承 BOM 快照与交期）
        List<MrpMo> children = new ArrayList<>();
        for (BigDecimal q : childQtys) {
            MrpMo child = new MrpMo();
            child.setProductCode(parent.getProductCode());
            child.setProductName(parent.getProductName());
            child.setQty(q);
            child.setPlanStartDate(parent.getPlanStartDate());
            child.setPlanEndDate(parent.getPlanEndDate());
            child.setPriority(parent.getPriority());
            child.setStatus(MrpMo.ST_RELEASED);
            child.setShortageFlag("0");
            child.setPlannedMoNo(parent.getPlannedMoNo());
            child.setSourceBomId(parent.getSourceBomId());
            child.setBomVersion(parent.getBomVersion());
            child.setSourceRoutingId(parent.getSourceRoutingId());
            child.setSplitFromMo(parent.getMoNo());
            child.setChangeReason("拆分自 " + parent.getMoNo()
                    + (isNotBlank(reason) ? "：" + reason.trim() : ""));
            child.setReleaseBy(currentOperator());
            child.setReleaseAt(LocalDateTime.now());
            insertMoResolvingConflict(child);
            // 克隆 BOM 快照
            for (MrpMoBom src : moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                    .eq(MrpMoBom::getMoId, parent.getId()).orderByAsc(MrpMoBom::getLineNo))) {
                MrpMoBom row = new MrpMoBom();
                row.setMoId(child.getId());
                row.setLineNo(src.getLineNo());
                row.setTreeLevel(src.getTreeLevel());
                row.setOwnerItem(src.getOwnerItem());
                row.setItemCode(src.getItemCode());
                row.setItemName(src.getItemName());
                row.setUnitQty(src.getUnitQty());
                row.setLossRate(src.getLossRate());
                row.setBaseUnit(src.getBaseUnit());
                row.setSubstituteInfo(src.getSubstituteInfo());
                moBomDao.insert(row);
            }
            // 克隆工序快照
            for (MrpMoOp src : moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                    .eq(MrpMoOp::getMoId, parent.getId()).orderByAsc(MrpMoOp::getOpSeq))) {
                MrpMoOp row = new MrpMoOp();
                row.setMoId(child.getId());
                row.setOpSeq(src.getOpSeq());
                row.setOpCode(src.getOpCode());
                row.setOpName(src.getOpName());
                row.setWcCode(src.getWcCode());
                row.setWcName(src.getWcName());
                row.setLeadTime(src.getLeadTime());
                row.setSetupHours(src.getSetupHours());
                row.setRunHours(src.getRunHours());
                row.setWaitHours(src.getWaitHours());
                row.setMoveHours(src.getMoveHours());
                row.setOpStatus("PENDING");
                moOpDao.insert(row);
            }
            kittingService.check(child.getId());
            children.add(child);
        }
        // 原单减量 + 拆分留痕（C-4.5-15 全程留痕）
        parent.setQty(remaining.subtract(sum).signum() == 0
                ? BigDecimal.ZERO : remaining.subtract(sum));
        parent.setChangeReason("拆分出 " + children.size() + " 张子单（合计 "
                + sum.stripTrailingZeros().toPlainString() + "）"
                + (isNotBlank(reason) ? "：" + reason.trim() : ""));
        moDao.updateById(parent);
        log.info("工单拆分：{} → {} 张子单，剩余={}", parent.getMoNo(), children.size(), parent.getQty());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("parent", moDao.selectById(parent.getId()));
        out.put("children", children);
        return out;
    }

    // ==================== 5.4.5 完工与关闭 ====================

    @Override
    @Transactional
    public MrpMo complete(String moId, BigDecimal qualifiedQty) {
        requireAny("工单完工确认");
        MrpMo mo = requireMo(moId);
        if (!MrpMo.ST_RELEASED.equals(mo.getStatus())) {
            throw new ServiceException(422,
                    "仅已释放状态可完工确认（当前状态：" + mo.getStatus() + "；挂起请先恢复）");
        }
        if (qualifiedQty == null || qualifiedQty.signum() < 0) {
            throw new ServiceException(422, "合格产出数量必填且不可为负");
        }
        mo.setQualifiedQty(qualifiedQty);
        mo.setCompleteBy(currentOperator());
        mo.setCompleteAt(LocalDateTime.now());
        casStatus(mo, MrpMo.ST_COMPLETED);
        log.info("工单完工确认：{} 合格产出={}", mo.getMoNo(), qualifiedQty);
        return mo;
    }

    @Override
    public Map<String, Object> precheckClose(String moId) {
        requireLogin("关闭预检");
        MrpMo mo = requireMo(moId);
        List<Map<String, Object>> results = new ArrayList<>();
        boolean allPassed = true;
        // 硬校验 1：状态 = COMPLETED
        boolean statusOk = MrpMo.ST_COMPLETED.equals(mo.getStatus());
        allPassed &= statusOk;
        results.add(result("状态为已完工", statusOk,
                statusOk ? null : "当前状态 " + mo.getStatus() + "，请先完工确认"));
        // 钩子（D3 占位：未完成工序/成本归集/在制余额——下游落地时接入）
        for (MoCloseCheck check : closeChecks) {
            boolean passed = true;
            String msg = null;
            try {
                check.validate(mo);
            } catch (ServiceException e) {
                passed = false;
                msg = e.getMessage();
            }
            allPassed &= passed;
            results.add(result(check.name(), passed, msg));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moNo", mo.getMoNo());
        out.put("passed", allPassed);
        out.put("checks", results);
        return out;
    }

    @Override
    @Transactional
    public MrpMo close(String moId) {
        requireAny("关闭工单");
        MrpMo mo = requireMo(moId);
        if (!MrpMo.ST_COMPLETED.equals(mo.getStatus())) {
            throw new ServiceException(422,
                    "仅已完工状态可关闭（当前状态：" + mo.getStatus() + "）");
        }
        // 钩子（D3：占位空通过；不满足 → 422 阻断，spec 场景由下游实现触发）
        for (MoCloseCheck check : closeChecks) {
            check.validate(mo);
        }
        mo.setCloseBy(currentOperator());
        mo.setCloseAt(LocalDateTime.now());
        casStatus(mo, MrpMo.ST_CLOSED);
        log.info("工单关闭：{}（成本锁定，终态）", mo.getMoNo());
        return mo;
    }

    // ==================== 下游消费契约 ====================

    @Override
    public Map<String, BigDecimal> inProcess(Set<String> itemCodes) {
        requireLogin("查询在制供给");
        return moDataSource.inProcess(itemCodes);
    }

    // ==================== 私有辅助 ====================

    private Map<String, Object> result(String name, boolean passed, String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("passed", passed);
        m.put("message", msg);
        return m;
    }

    private MrpMo requireMo(String moId) {
        MrpMo mo = moDao.selectById(moId);
        if (mo == null) {
            throw new ServiceException(404, "工单不存在：" + moId);
        }
        return mo;
    }

    /** 产品必须存在、启用且为自制件（FR-4.5-3-1） */
    private MdmItem requireActiveMakeItem(String productCode) {
        MdmItem item = mdmItemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                .eq(MdmItem::getItemCode, productCode));
        if (item == null) {
            throw new ServiceException(422, "产品物料不存在：" + productCode);
        }
        if (!"1".equals(item.getStatus())) {
            throw new ServiceException(422, "产品物料已停用，无法创建工单：" + productCode);
        }
        if (!"MAKE".equals(item.getPurchaseType())) {
            throw new ServiceException(422, "仅自制件（MAKE）可创建生产工单：" + productCode);
        }
        return item;
    }

    /** 同产品唯一在途（design D1：任一在途 → 阻断再创建；拆分由 split 自身豁免此校验） */
    private void requireNoInFlight(String productCode) {
        long inFlight = moDao.selectCount(new LambdaQueryWrapper<MrpMo>()
                .eq(MrpMo::getProductCode, productCode)
                .in(MrpMo::getStatus, MrpMo.ST_PLANNED, MrpMo.ST_PENDING,
                        MrpMo.ST_CONFIRMED, MrpMo.ST_RELEASED, MrpMo.ST_HOLD));
        if (inFlight > 0) {
            throw new ServiceException(422,
                    "该产品存在在途工单（计划/待审核/已确认/已释放/挂起），请先处理在途工单");
        }
    }

    /** 产品行级锁（design D1）：串行化唯一在途判定（无行时靠先到者插入后的 count 阻断兜底） */
    private void lockProduct(String productCode) {
        moDao.selectList(new LambdaQueryWrapper<MrpMo>()
                .eq(MrpMo::getProductCode, productCode)
                .last("FOR UPDATE"));
    }

    /** MO_NO 按日流水：对已赋全字段的实体编号插入，唯一冲突递增重试（run/PMO 号先例） */
    private MrpMo insertMoResolvingConflict(MrpMo mo) {
        String day = LocalDate.now().format(MO_DAY);
        String prefix = "MO-" + day + "-";
        long today = moDao.selectCount(new LambdaQueryWrapper<MrpMo>()
                .likeRight(MrpMo::getMoNo, prefix));
        for (int attempt = 0; attempt < 99; attempt++) {
            mo.setMoNo(prefix + String.format("%03d", today + 1 + attempt));
            try {
                moDao.insert(mo);
                return mo;
            } catch (DuplicateKeyException e) {
                today++;
            }
        }
        throw new ServiceException(422, "工单号分配冲突超限，请稍后重试");
    }

    /**
     * 白名单 + CAS（先 updateById 持久化实体上的业务留痕字段，再以旧状态 CAS——
     * mrp-plan casStatus 模式，569 绿实证）；影响行数 0 → 422 并发冲突。
     */
    private void casStatus(MrpMo mo, String to) {
        MoStatusRules.assertTransit(mo.getStatus(), to);
        moDao.updateById(mo);
        int rows = moDao.update(null, new LambdaUpdateWrapper<MrpMo>()
                .eq(MrpMo::getId, mo.getId())
                .eq(MrpMo::getStatus, mo.getStatus())
                .eq(MrpMo::getVerNo, mo.getVerNo())
                .set(MrpMo::getStatus, to)
                .set(MrpMo::getVerNo, mo.getVerNo() + 1));
        if (rows == 0) {
            throw new ServiceException(422,
                    "状态并发冲突（" + mo.getStatus() + " → " + to + "），请刷新后重试");
        }
        mo.setStatus(to);
        mo.setVerNo(mo.getVerNo() + 1);
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String currentOperator() {
        try {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            return auth == null || auth.getName() == null ? "system" : auth.getName();
        } catch (Exception e) {
            return "system";
        }
    }

    /** 需登录（读操作：未认证 401，spec「角色与菜单权限」） */
    private void requireLogin(String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
    }

    /** 写操作角色门（6.4）：未登录 401、角色不符 403、ADMIN 直通（freeze/BOM requireAny 范式） */
    private void requireAny(String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String r : new String[]{"ROLE_PLANNER", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(new SimpleGrantedAuthority(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action + "（需 ROLE_PLANNER 或 ROLE_ADMIN）");
    }
}
