package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.inv.FreezeService;
import com.erp.service.sd.ReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 冻结/解冻实现（spec freeze-management）。
 * 冻结申请 → 审批底座单节点（BIZ_TYPE=Freeze，节点角色按类型分流，C-4.4-05 由
 * 底座 ROLE_REQUIRED 校验天然实现）→ 通过回调 FreezeCallback 同事务执行平移与预留释放。
 * 解冻 = 独立单据（BIZ_TYPE=Unfreeze）走同类型审批，仅原发起人可发起。
 */
@Slf4j
@Service
public class FreezeServiceImpl implements FreezeService {

    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyMMdd");

    private final InvFreezeDao freezeDao;
    private final InvStockDao stockDao;
    private final ApprovalEngine approvalEngine;
    private final ReservationService reservationService;
    private final ReservationDao reservationDao;
    private final SysParamService sysParamService;
    /** C-4.4-07 召回未结案解冻拦截（spec trace-recall / freeze-management MODIFIED） */
    private final com.erp.dao.inv.TraceOrderDao traceOrderDao;
    private final com.erp.dao.inv.TraceFlowDao traceFlowDao;

    public FreezeServiceImpl(InvFreezeDao freezeDao, InvStockDao stockDao,
                             ApprovalEngine approvalEngine, ReservationService reservationService,
                             ReservationDao reservationDao, SysParamService sysParamService,
                             com.erp.dao.inv.TraceOrderDao traceOrderDao,
                             com.erp.dao.inv.TraceFlowDao traceFlowDao) {
        this.freezeDao = freezeDao;
        this.stockDao = stockDao;
        this.approvalEngine = approvalEngine;
        this.reservationService = reservationService;
        this.reservationDao = reservationDao;
        this.sysParamService = sysParamService;
        this.traceOrderDao = traceOrderDao;
        this.traceFlowDao = traceFlowDao;
    }

    // ---------- 冻结申请 ----------

    @Override
    @Transactional
    public InvFreeze apply(InvFreeze req) {
        return doApply(req, true);
    }

    @Override
    @Transactional
    public InvFreeze applyFromReview(InvFreeze req) {
        // picking-review design D4：自动发起跳过发起角色校验，审批链不省
        if (req != null && isBlank(req.getSource())) {
            req.setSource(InvFreeze.SRC_REVIEW);
        }
        return doApply(req, false);
    }

    private InvFreeze doApply(InvFreeze req, boolean enforceInitiatorRole) {
        if (req == null) {
            throw new ServiceException(422, "冻结申请必填");
        }
        String type = req.getFreezeType();
        if (!InvFreeze.T_QUALITY.equals(type) && !InvFreeze.T_FINANCE.equals(type)) {
            throw new ServiceException(422, "冻结类型仅支持质量/财务（单笔唯一，C-4.4-05）");
        }
        if (isBlank(req.getReason())) {
            throw new ServiceException(422, "冻结原因必填");
        }
        if (req.getQty() == null || req.getQty().signum() <= 0) {
            throw new ServiceException(422, "冻结数量必填且大于 0");
        }
        if (isBlank(req.getItemCode())) {
            throw new ServiceException(422, "物料必填");
        }
        if (isBlank(req.getWarehouseCode())) {
            throw new ServiceException(422, "仓库必填");
        }
        String scope = isBlank(req.getScope()) ? InvFreeze.SCOPE_BATCH : req.getScope();
        if (!InvFreeze.SCOPE_ALL.equals(scope) && !InvFreeze.SCOPE_BATCH.equals(scope)
                && !InvFreeze.SCOPE_BIN.equals(scope)) {
            throw new ServiceException(422, "影响范围仅支持全部库存/指定批次/指定仓位");
        }
        if (!InvFreeze.SCOPE_ALL.equals(scope) && req.getBatchNo() == null) {
            throw new ServiceException(422, "指定批次范围须选择批次（无批次物料传空串）");
        }

        // 发起角色分流（接口管可为）；自动发起路径跳过（design D4）
        if (enforceInitiatorRole) {
            if (InvFreeze.T_QUALITY.equals(type)) {
                requireAny("发起质量冻结", "ROLE_QUALITY_ENG", "ROLE_ADMIN");
            } else {
                requireAny("发起财务冻结", "ROLE_FINANCE", "ROLE_ADMIN");
            }
        } else if (!InvFreeze.T_QUALITY.equals(type)) {
            throw new ServiceException(422, "复核自动发起仅支持质量冻结");
        }

        // 库存存在性（审批通过后执行仍会二次校验，此处避免批入虚空）
        requireStockExists(req, scope);

        InvFreeze f = new InvFreeze();
        f.setFreezeType(type);
        f.setWarehouseCode(req.getWarehouseCode());
        f.setItemCode(req.getItemCode());
        f.setItemName(req.getItemName());
        f.setBatchNo(InvFreeze.SCOPE_ALL.equals(scope) ? null : req.getBatchNo());
        f.setQty(req.getQty());
        f.setReason(req.getReason().trim());
        f.setScope(scope);
        f.setStatus(InvFreeze.ST_PENDING);
        f.setSource(isBlank(req.getSource()) ? InvFreeze.SRC_MANUAL : req.getSource());
        f.setRemark(req.getRemark());
        f.setApplyBy(currentUser());
        f.setFreezeNo(nextFreezeNo());
        freezeDao.insert(f);

        // 单节点审批：节点角色按类型分流（跨类型签署由底座 403，C-4.4-05）
        String approverRole = approverRole(type);
        String approverName = InvFreeze.T_QUALITY.equals(type) ? "质量经理审批" : "财务主管审批";
        var inst = approvalEngine.submit(BIZ_FREEZE, f.getId(),
                "冻结审批：" + f.getFreezeNo() + " / " + f.getItemCode(),
                null,
                List.of(List.of(ApprovalNodeSpec.sign(approverRole, approverName))));
        f.setApprId(inst.getId());
        if (freezeDao.updateById(f) == 0) {
            throw new ServiceException(409, "冻结单状态更新冲突");
        }
        log.info("freeze {} submitted: type={} {}/{} qty={} approver={}",
                f.getFreezeNo(), type, f.getItemCode(), f.getBatchNo(), f.getQty(), approverRole);
        return f;
    }

    // ---------- 解冻申请 ----------

    @Override
    @Transactional
    public InvFreeze applyUnfreeze(String id, String releaseResult, String releaseBasis) {
        InvFreeze f = freezeDao.selectById(id);
        if (f == null) {
            throw new ServiceException(422, "冻结单不存在");
        }
        if (InvFreeze.SRC_NCR.equals(f.getSource())) {
            throw new ServiceException(422, "NCR 来源冻结请在 NCR 流程解冻（双入口隔离）");
        }
        // C-4.4-07/BR-4.4-51 L1：召回未结案拦截（NCR 双入口隔离保持不变——上一行先抛）
        assertTraceClosed(f.getBatchNo());
        if (!InvFreeze.ST_ACTIVE.equals(f.getStatus())) {
            throw new ServiceException(422, "仅生效中的冻结可解冻（当前：" + f.getStatus() + "）");
        }
        if (!currentUser().equals(f.getApplyBy()) && !hasRole("ROLE_ADMIN")) {
            throw new ServiceException(403, "仅原冻结发起人可发起解冻");
        }
        if (isBlank(releaseResult) || isBlank(releaseBasis)) {
            throw new ServiceException(422, "解冻处理结果与解冻依据必填");
        }
        var pending = approvalEngine.findByBiz(BIZ_UNFREEZE, id);
        if (pending != null && "PENDING".equals(pending.getStatus())) {
            throw new ServiceException(422, "解冻审批中，不可重复提交");
        }

        f.setReleaseResult(releaseResult.trim());
        f.setReleaseBasis(releaseBasis.trim());
        if (freezeDao.updateById(f) == 0) {
            throw new ServiceException(409, "冻结单状态更新冲突");
        }

        String approverRole = approverRole(f.getFreezeType());
        String approverName = InvFreeze.T_QUALITY.equals(f.getFreezeType())
                ? "质量经理解冻审批" : "财务主管解冻审批";
        var inst = approvalEngine.submit(BIZ_UNFREEZE, id,
                "解冻审批：" + f.getFreezeNo() + " / " + f.getItemCode(),
                null,
                List.of(List.of(ApprovalNodeSpec.sign(approverRole, approverName))));
        f.setUnfreezeApprId(inst.getId());
        if (freezeDao.updateById(f) == 0) {
            throw new ServiceException(409, "冻结单状态更新冲突");
        }
        log.info("unfreeze {} submitted by {}: approver={}", f.getFreezeNo(),
                currentUser(), approverRole);
        return f;
    }

    /** C-4.4-07：批次存在非 CLOSED 追溯单 → 422「召回未结案」+ 未闭环流向清单（一次查询） */
    private void assertTraceClosed(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return;   // 全部库存范围冻结无批次维度，无法按批拦截
        }
        var open = traceOrderDao.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.inv.TraceOrder>()
                .eq(com.erp.entity.inv.TraceOrder::getBatchNo, batchNo)
                .ne(com.erp.entity.inv.TraceOrder::getStatus, "CLOSED")
                .last("LIMIT 1"));
        if (open == null) {
            return;
        }
        var pending = traceFlowDao.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.inv.TraceFlow>()
                .eq(com.erp.entity.inv.TraceFlow::getTraceId, open.getId())
                .notIn(com.erp.entity.inv.TraceFlow::getStatus,
                        "DISPOSED", "LINKED", "INTERCEPTED", "RECEIVED", "REJECTED", "FAILED"));
        List<String> summary = new ArrayList<>();
        for (var fl : pending) {
            summary.add(fl.getFlowType() + "/" + fl.getStatus()
                    + (fl.getCustomerName() == null ? "" : "@" + fl.getCustomerName()));
        }
        throw new ServiceException(422, "召回未结案：批次 " + batchNo + " 存在未结案追溯单 "
                + open.getTraceNo() + "，结案后方可解冻（C-4.4-07）。未闭环召回清单："
                + (summary.isEmpty() ? "（无）" : summary));
    }

    // ---------- 影响评估预估（FR-4.4-5-3 异常列） ----------

    @Override
    public Map<String, Object> estimate(String warehouseCode, String itemCode, String batchNo,
                                        String scope, BigDecimal qty) {
        if (isBlank(warehouseCode) || isBlank(itemCode)) {
            throw new ServiceException(422, "仓库与物料必填");
        }
        String sc = isBlank(scope) ? InvFreeze.SCOPE_BATCH : scope;
        if (!InvFreeze.SCOPE_ALL.equals(sc) && !InvFreeze.SCOPE_BATCH.equals(sc)
                && !InvFreeze.SCOPE_BIN.equals(sc)) {
            throw new ServiceException(422, "影响范围仅支持全部库存/指定批次/指定仓位");
        }
        LambdaQueryWrapper<InvStock> qw = new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, warehouseCode)
                .eq(InvStock::getItemCode, itemCode);
        if (!InvFreeze.SCOPE_ALL.equals(sc)) {
            if (batchNo == null) {
                throw new ServiceException(422, "指定批次范围须选择批次（无批次物料传空串）");
            }
            qw.eq(InvStock::getBatchNo, batchNo);
        }
        List<InvStock> rows = stockDao.selectList(qw);
        if (rows.isEmpty()) {
            throw new ServiceException(422, "库存维度不存在");
        }
        BigDecimal available = rows.stream()
                .map(s -> s.getAvailableQty() == null ? BigDecimal.ZERO : s.getAvailableQty())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // 关联 SO 数（ACTIVE 预留反查，同冻结执行口径）
        LambdaQueryWrapper<Reservation> rq = new LambdaQueryWrapper<Reservation>()
                .eq(Reservation::getStatus, Reservation.ST_ACTIVE)
                .eq(Reservation::getWarehouseCode, warehouseCode)
                .eq(Reservation::getItemCode, itemCode);
        if (!InvFreeze.SCOPE_ALL.equals(sc)) {
            rq.eq(Reservation::getBatchNo, batchNo == null ? "" : batchNo);
        }
        long soCount = reservationDao.selectList(rq).stream()
                .map(Reservation::getSoNo).filter(java.util.Objects::nonNull).distinct().count();
        // 阈值：FREEZE_IMPACT_RATIO（百分比，默认 50）
        int ratioPct = sysParamService.getInt("FREEZE_IMPACT_RATIO", 50);
        boolean needConfirm = InvFreeze.SCOPE_ALL.equals(sc)
                || (qty != null && available.signum() > 0
                    && qty.compareTo(available.multiply(BigDecimal.valueOf(ratioPct))
                            .divide(BigDecimal.valueOf(100), 4, java.math.RoundingMode.HALF_UP)) >= 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("availableQty", available);
        out.put("affectedQty", available);
        out.put("affectedSoCount", soCount);
        out.put("ratioPct", ratioPct);
        out.put("needConfirm", needConfirm);
        return out;
    }

    // ---------- 查询（4.3.2） ----------

    @Override
    public List<Map<String, Object>> query(String freezeType, String status, String itemCode,
                                           String batchNo, String keyword) {
        LambdaQueryWrapper<InvFreeze> qw = new LambdaQueryWrapper<InvFreeze>()
                .eq(!isBlank(freezeType), InvFreeze::getFreezeType, freezeType)
                .eq(!isBlank(status), InvFreeze::getStatus, status)
                .eq(!isBlank(itemCode), InvFreeze::getItemCode, itemCode)
                .eq(!isBlank(batchNo), InvFreeze::getBatchNo, batchNo)
                .and(!isBlank(keyword), w -> w.like(InvFreeze::getFreezeNo, keyword)
                        .or().like(InvFreeze::getItemCode, keyword)
                        .or().like(InvFreeze::getBatchNo, keyword)
                        .or().like(InvFreeze::getReason, keyword))
                .orderByDesc(InvFreeze::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        String me = currentUser();
        for (InvFreeze f : freezeDao.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("freezeNo", f.getFreezeNo());
            m.put("freezeType", f.getFreezeType());
            m.put("warehouseCode", f.getWarehouseCode());
            m.put("itemCode", f.getItemCode());
            m.put("itemName", f.getItemName());
            m.put("batchNo", f.getBatchNo());
            m.put("qty", f.getQty());
            m.put("reason", f.getReason());
            m.put("scope", f.getScope());
            m.put("status", f.getStatus());
            m.put("source", f.getSource());
            m.put("ncrId", f.getNcrId());
            m.put("applyBy", f.getApplyBy());
            m.put("releaseResult", f.getReleaseResult());
            m.put("releaseBasis", f.getReleaseBasis());
                m.put("createDate", f.getCreateDate());
                m.put("releasedAt", f.getReleasedAt());
                // 影响面快照（4.9.4 展开；未执行的冻结单为 null）
                m.put("impactJson", f.getImpactJson());
            // NCR 来源行只读（解冻走 NCR 流程）；解冻入口仅原发起人
            m.put("readonly", InvFreeze.SRC_NCR.equals(f.getSource()));
            m.put("canUnfreeze", InvFreeze.ST_ACTIVE.equals(f.getStatus())
                    && InvFreeze.SRC_MANUAL.equals(f.getSource())
                    && (f.getApplyBy().equals(me) || hasRole("ROLE_ADMIN")));
            out.add(m);
        }
        return out;
    }

    // ---------- NCR 台账归集（ncr-management MODIFIED） ----------

    @Override
    @Transactional
    public void recordNcrFreeze(String warehouseCode, String itemCode, String batchNo,
                                BigDecimal qty, String ncrId) {
        InvFreeze f = new InvFreeze();
        f.setFreezeNo(nextFreezeNo());
        f.setFreezeType(InvFreeze.T_QUALITY);
        f.setWarehouseCode(warehouseCode);
        f.setItemCode(itemCode);
        f.setBatchNo(batchNo);
        f.setQty(qty);
        f.setReason("NCR 不合格自动冻结（" + ncrId + "）");
        f.setScope(InvFreeze.SCOPE_BATCH);
        f.setStatus(InvFreeze.ST_ACTIVE);   // NCR 冻结即时生效（无审批，BR-4.2-25）
        f.setSource(InvFreeze.SRC_NCR);
        f.setNcrId(ncrId);
        f.setApplyBy("system:ncr");
        freezeDao.insert(f);
        log.info("ncr freeze ledger: {} {} {} qty={} ncr={}", f.getFreezeNo(),
                itemCode, batchNo, qty, ncrId);
    }

    @Override
    @Transactional
    public void recordNcrUnfreeze(String warehouseCode, String itemCode, String batchNo,
                                  BigDecimal qty, String ncrId) {
        // 仅释放该 NCR 自有 ACTIVE 行（不误放手工冻结行）
        List<InvFreeze> rows = freezeDao.selectList(new LambdaQueryWrapper<InvFreeze>()
                .eq(InvFreeze::getStatus, InvFreeze.ST_ACTIVE)
                .eq(InvFreeze::getSource, InvFreeze.SRC_NCR)
                .eq(InvFreeze::getNcrId, ncrId)
                .eq(InvFreeze::getItemCode, itemCode)
                .eq(batchNo != null, InvFreeze::getBatchNo, batchNo)
                .orderByAsc(InvFreeze::getCreateDate));
        BigDecimal remain = qty == null ? null : qty;
        for (InvFreeze f : rows) {
            if (remain != null) {
                if (remain.signum() <= 0) {
                    break;
                }
                if (f.getQty().compareTo(remain) > 0) {
                    // 部分解冻：本行缩减（台账留痕在 NCR 流水，行内直接改量）
                    f.setQty(f.getQty().subtract(remain));
                    freezeDao.updateById(f);
                    remain = BigDecimal.ZERO;
                    continue;
                }
                remain = remain.subtract(f.getQty());
            }
            f.setStatus(InvFreeze.ST_RELEASED);
            f.setReleasedAt(java.time.LocalDateTime.now());
            f.setReleasedBy("system:ncr");
            freezeDao.updateById(f);
        }
        log.info("ncr unfreeze ledger: item={} batch={} ncr={} remain={}", itemCode, batchNo,
                ncrId, remain);
    }

    // ---------- helpers ----------

    private void requireStockExists(InvFreeze req, String scope) {
        if (InvFreeze.SCOPE_ALL.equals(scope)) {
            Long cnt = stockDao.selectCount(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getWarehouseCode, req.getWarehouseCode())
                    .eq(InvStock::getItemCode, req.getItemCode()));
            if (cnt == null || cnt == 0) {
                throw new ServiceException(422, "该仓库无此物料库存，不可冻结");
            }
        } else {
            // 位行级库存下同批次可多行——存在性校验用计数（selectOne 会撞 TooManyResults）
            Long cnt = stockDao.selectCount(new LambdaQueryWrapper<InvStock>()
                    .eq(InvStock::getWarehouseCode, req.getWarehouseCode())
                    .eq(InvStock::getItemCode, req.getItemCode())
                    .eq(InvStock::getBatchNo, req.getBatchNo()));
            if (cnt == null || cnt == 0) {
                throw new ServiceException(422, "库存不存在（维度不可冻结）");
            }
        }
    }

    private String approverRole(String freezeType) {
        return InvFreeze.T_QUALITY.equals(freezeType)
                ? "ROLE_QUALITY_MGR" : "ROLE_FINANCE_MGR";
    }

    /** 冻结单号 FZ+yyMMdd+4位日流水（唯一键冲突重试） */
    private String nextFreezeNo() {
        String prefix = "FZ" + LocalDate.now().format(NO_DAY) + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            Long cnt = freezeDao.selectCount(new LambdaQueryWrapper<InvFreeze>()
                    .likeRight(InvFreeze::getFreezeNo, prefix));
            String no = prefix + String.format("%04d", (cnt == null ? 0 : cnt) + 1 + attempt);
            boolean dup = freezeDao.selectCount(new LambdaQueryWrapper<InvFreeze>()
                    .eq(InvFreeze::getFreezeNo, no)) > 0;
            if (!dup) {
                return no;
            }
        }
        return prefix + System.nanoTime() % 100000;
    }

    private void requireAny(String action, String... roles) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String r : roles) {
            if (auth.getAuthorities().contains(new SimpleGrantedAuthority(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private boolean hasRole(String role) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().contains(new SimpleGrantedAuthority(role));
    }

    private String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 供 FreezeCallback 引用的类型集合校验（避免散落字符串） */
    static final Set<String> TYPES = Set.of(InvFreeze.T_QUALITY, InvFreeze.T_FINANCE);
}
