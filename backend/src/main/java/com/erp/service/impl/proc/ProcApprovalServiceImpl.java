package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.ProcPrApprovalDao;
import com.erp.dao.proc.ProcPrLineDao;
import com.erp.dao.proc.ProcRequisitionDao;
import com.erp.entity.proc.ProcPrApproval;
import com.erp.entity.proc.ProcPrLine;
import com.erp.entity.proc.ProcRequisition;
import com.erp.procurement.RequisitionStateMachine;
import com.erp.service.proc.ProcApprovalService;
import com.erp.service.proc.ProcRequisitionService;
import com.erp.service.proc.ProcRequisitionSupport;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 请购审批实现（design D7）：三档限额路由、逐级通过/驳回重提、超时升级懒 sweep。
 * 节点任务全量创建（node1 ACTIVE，后续 PENDING）；重提批次 +1 历史保留。
 */
@Slf4j
@Service
public class ProcApprovalServiceImpl implements ProcApprovalService {

    private static final String[] NODE_ROLES = { "DEPT_MANAGER", "DIRECTOR", "VP" };

    private final ProcRequisitionDao prDao;
    private final ProcPrLineDao lineDao;
    private final ProcPrApprovalDao approvalDao;
    private final ProcRequisitionSupport support;
    private final ProcRequisitionService requisitionService;

    @Value("${app.proc.dept-approval-limit:50000}")
    private BigDecimal deptApprovalLimit;

    @Value("${app.proc.director-approval-limit:500000}")
    private BigDecimal directorApprovalLimit;

    public ProcApprovalServiceImpl(ProcRequisitionDao prDao,
                                   ProcPrLineDao lineDao,
                                   ProcPrApprovalDao approvalDao,
                                   ProcRequisitionSupport support,
                                   ProcRequisitionService requisitionService) {
        this.prDao = prDao;
        this.lineDao = lineDao;
        this.approvalDao = approvalDao;
        this.support = support;
        this.requisitionService = requisitionService;
    }

    // ---------- 提交审批 ----------

    @Override
    @Transactional
    public Map<String, Object> submit(String prId) {
        ProcRequisition pr = support.requirePr(prId);
        String st = pr.getStatus();
        if (!RequisitionStateMachine.CONFIRMED.equals(st)
                && !RequisitionStateMachine.PENDING_APPROVAL.equals(st)
                && !RequisitionStateMachine.PENDING_MODIFY.equals(st)) {
            throw new ServiceException(422, "当前状态 " + st
                    + " 不可提交审批（可提交态：已确认/待审批/已驳回）");
        }
        List<ProcPrLine> lines = linesOf(prId);
        if (lines.isEmpty()) {
            throw new ServiceException(422, "无请购行不可提交审批");
        }
        // 判级金额 = Σ(qty × 预估单价)（design D7 口径）
        BigDecimal amount = BigDecimal.ZERO;
        for (ProcPrLine l : lines) {
            BigDecimal price = l.getEstUnitPrice() == null ? BigDecimal.ZERO : l.getEstUnitPrice();
            amount = amount.add(l.getQty().multiply(price));
        }
        amount = amount.setScale(2, RoundingMode.HALF_UP);
        // 三档路由
        int nodeCount;
        if (amount.compareTo(deptApprovalLimit) <= 0) {
            nodeCount = 1;
        } else if (amount.compareTo(directorApprovalLimit) <= 0) {
            nodeCount = 2;
        } else {
            nodeCount = 3;
        }
        Integer maxBatch = approvalDao.selectList(new LambdaQueryWrapper<ProcPrApproval>()
                        .eq(ProcPrApproval::getPrId, prId))
                .stream().map(ProcPrApproval::getSubmitBatch).max(Integer::compareTo).orElse(0);
        int batch = maxBatch + 1;
        // SUBMIT 日志行
        insertTask(prId, batch, 0, "APPLICANT", "DONE", "SUBMIT", null);
        for (int i = 1; i <= nodeCount; i++) {
            insertTask(prId, batch, i, NODE_ROLES[i - 1], i == 1 ? "ACTIVE" : "PENDING",
                    "SUBMIT", null);
        }
        pr.setApprovalAmount(amount);
        pr.setRejectReason(null);
        support.persist(pr);
        support.transition(pr, RequisitionStateMachine.APPROVING,
                "提交审批 金额 " + amount + " 节点 " + nodeCount);
        support.publishHead(pr, "PROC.PR.SUBMITTED",
                "提交审批：判级金额 " + amount + "，节点 " + nodeCount + " 级（批次 " + batch + "）");
        log.info("PR {} submitted amount={} nodes={} batch={}", pr.getPrNo(), amount, nodeCount, batch);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pr", pr);
        result.put("amount", amount);
        result.put("nodes", nodeCount);
        result.put("batch", batch);
        return result;
    }

    // ---------- 通过 ----------

    @Override
    @Transactional
    public List<Map<String, Object>> pass(String taskId) {
        ProcPrApproval task = requireTask(taskId);
        requireActive(task);
        ProcRequisition pr = support.requirePr(task.getPrId());
        if (!RequisitionStateMachine.APPROVING.equals(pr.getStatus())) {
            throw new ServiceException(422, "PR 状态 " + pr.getStatus() + " 非审批中，任务不可通过");
        }
        markAction(task, "PASS", null);
        ProcPrApproval next = approvalDao.selectOne(new LambdaQueryWrapper<ProcPrApproval>()
                .eq(ProcPrApproval::getPrId, task.getPrId())
                .eq(ProcPrApproval::getSubmitBatch, task.getSubmitBatch())
                .eq(ProcPrApproval::getNodeNo, task.getNodeNo() + 1));
        if (next != null && "PENDING".equals(next.getStatus())) {
            next.setStatus("ACTIVE");
            approvalDao.updateById(next);
            log.info("PR {} node {} passed, next node {} activated", pr.getPrNo(),
                    task.getNodeNo(), next.getNodeNo());
        } else {
            support.transition(pr, RequisitionStateMachine.APPROVED, "审批通过（批次 "
                    + task.getSubmitBatch() + "）");
            support.publishHead(pr, "PROC.PR.APPROVED",
                    "审批通过：判级金额 " + pr.getApprovalAmount());
            log.info("PR {} approved, amount={}", pr.getPrNo(), pr.getApprovalAmount());
        }
        return logs(pr.getId());
    }

    // ---------- 驳回 ----------

    @Override
    @Transactional
    public List<Map<String, Object>> reject(String taskId, String reason) {
        ProcPrApproval task = requireTask(taskId);
        requireActive(task);
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "驳回原因必填（至少 2 字）");
        }
        ProcRequisition pr = support.requirePr(task.getPrId());
        if (!RequisitionStateMachine.APPROVING.equals(pr.getStatus())) {
            throw new ServiceException(422, "PR 状态 " + pr.getStatus() + " 非审批中，任务不可驳回");
        }
        markAction(task, "REJECT", reason.trim());
        // 同批次其余任务作废
        List<ProcPrApproval> sameBatch = approvalDao.selectList(
                new LambdaQueryWrapper<ProcPrApproval>()
                        .eq(ProcPrApproval::getPrId, task.getPrId())
                        .eq(ProcPrApproval::getSubmitBatch, task.getSubmitBatch())
                        .in(ProcPrApproval::getStatus, "ACTIVE", "PENDING"));
        for (ProcPrApproval t : sameBatch) {
            if (!t.getId().equals(task.getId()) && !"DONE".equals(t.getStatus())) {
                t.setStatus("SUPERSEDED");
                approvalDao.updateById(t);
            }
        }
        pr.setRejectReason(reason.trim());
        support.persist(pr);
        support.transition(pr, RequisitionStateMachine.PENDING_MODIFY,
                "驳回：" + reason.trim());
        support.publishHead(pr, "PROC.PR.REJECTED",
                "审批驳回：" + reason.trim() + "（节点 " + task.getNodeRole() + "）");
        log.info("PR {} rejected by node {} reason={}", pr.getPrNo(), task.getNodeRole(), reason);
        return logs(pr.getId());
    }

    // ---------- 待办（含 sweep） ----------

    @Override
    public List<Map<String, Object>> todo() {
        sweep();
        List<ProcRequisition> approving = prDao.selectList(
                new LambdaQueryWrapper<ProcRequisition>()
                        .eq(ProcRequisition::getStatus, RequisitionStateMachine.APPROVING)
                        .orderByDesc(ProcRequisition::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProcRequisition pr : approving) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("prId", pr.getId());
            row.put("prNo", pr.getPrNo());
            row.put("sourceType", pr.getSourceType());
            row.put("amount", pr.getApprovalAmount());
            row.put("createDate", pr.getCreateDate());
            List<ProcPrApproval> tasks = approvalDao.selectList(
                    new LambdaQueryWrapper<ProcPrApproval>()
                            .eq(ProcPrApproval::getPrId, pr.getId())
                            .orderByAsc(ProcPrApproval::getNodeNo));
            int maxBatch = tasks.stream().map(ProcPrApproval::getSubmitBatch)
                    .max(Integer::compareTo).orElse(1);
            List<Map<String, Object>> chain = new ArrayList<>();
            ProcPrApproval active = null;
            for (ProcPrApproval t : tasks) {
                if (t.getSubmitBatch() != maxBatch || t.getNodeNo() == 0) {
                    continue;
                }
                Map<String, Object> n = new LinkedHashMap<>();
                n.put("taskId", t.getId());
                n.put("nodeNo", t.getNodeNo());
                n.put("role", t.getNodeRole());
                n.put("status", t.getStatus());
                n.put("timeoutFlag", t.getTimeoutFlag());
                n.put("escalateFlag", t.getEscalateFlag());
                n.put("escalateTo", t.getEscalateTo());
                chain.add(n);
                if ("ACTIVE".equals(t.getStatus())) {
                    active = t;
                }
            }
            row.put("routeChain", chain);
            row.put("activeTaskId", active == null ? null : active.getId());
            row.put("activeRole", active == null ? null : active.getNodeRole());
            row.put("timeoutFlag", active == null ? "0" : active.getTimeoutFlag());
            row.put("escalateFlag", active == null ? "0" : active.getEscalateFlag());
            // 辅助信息：同物料历史 PR 预估价（最近 5 条，排除本单）
            List<Map<String, Object>> helpers = new ArrayList<>();
            for (ProcPrLine l : linesOf(pr.getId())) {
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("itemCode", l.getItemCode());
                h.put("currentQty", l.getQty());
                h.put("reqDate", l.getReqDate());
                List<Map<String, Object>> hist = new ArrayList<>();
                List<ProcPrLine> histLines = lineDao.selectList(
                        new LambdaQueryWrapper<ProcPrLine>()
                                .eq(ProcPrLine::getItemCode, l.getItemCode())
                                .ne(ProcPrLine::getPrId, pr.getId())
                                .orderByDesc(ProcPrLine::getCreateDate)
                                .last("LIMIT 5"));
                for (ProcPrLine hl : histLines) {
                    Map<String, Object> hm = new LinkedHashMap<>();
                    hm.put("estUnitPrice", hl.getEstUnitPrice());
                    hm.put("reqDate", hl.getReqDate());
                    hist.add(hm);
                }
                h.put("historyPrices", hist);
                helpers.add(h);
            }
            row.put("helpers", helpers);
            row.put("inventoryHint", "库存可供量待 WMS 接入（偏差表口径）");
            out.add(row);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> logs(String prId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProcPrApproval t : approvalDao.selectList(new LambdaQueryWrapper<ProcPrApproval>()
                .eq(ProcPrApproval::getPrId, prId)
                .orderByAsc(ProcPrApproval::getSubmitBatch)
                .orderByAsc(ProcPrApproval::getNodeNo))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());
            m.put("submitBatch", t.getSubmitBatch());
            m.put("nodeNo", t.getNodeNo());
            m.put("nodeRole", t.getNodeRole());
            m.put("status", t.getStatus());
            m.put("action", t.getAction());
            m.put("actionReason", t.getActionReason());
            m.put("actedBy", t.getActedBy());
            m.put("actionDate", t.getActionDate());
            m.put("timeoutFlag", t.getTimeoutFlag());
            m.put("escalateFlag", t.getEscalateFlag());
            m.put("escalateTo", t.getEscalateTo());
            m.put("createDate", t.getCreateDate());
            out.add(m);
        }
        return out;
    }

    // ---------- 超时/升级懒 sweep（BR-4.2-10） ----------

    private void sweep() {
        List<ProcPrApproval> active = approvalDao.selectList(new LambdaQueryWrapper<ProcPrApproval>()
                .eq(ProcPrApproval::getStatus, "ACTIVE"));
        LocalDateTime now = LocalDateTime.now();
        for (ProcPrApproval t : active) {
            if (t.getNodeNo() == 0) {
                continue;
            }
            long wd = RequisitionStateMachine.businessDaysBetween(t.getCreateDate().toLocalDate(), now);
            boolean changed = false;
            // 两个标记独立置位（≥3 日同时满足超1日提醒语义，不能 else-if 互斥）
            if (wd >= 1 && !"1".equals(t.getTimeoutFlag())) {
                t.setTimeoutFlag("1");
                t.setScanDate(now);
                changed = true;
                log.info("[TODO-NOTIFY] PR任务 {} 超1工作日，超时提醒（BR-4.2-10 桩）", t.getId());
            }
            if (wd >= 3 && !"1".equals(t.getEscalateFlag())) {
                t.setEscalateFlag("1");
                t.setEscalateTo(escalateTarget(t.getNodeNo()));
                t.setScanDate(now);
                changed = true;
                log.info("[TODO-NOTIFY] PR任务 {} 超3工作日，升级至 {}（BR-4.2-10 桩）",
                        t.getId(), t.getEscalateTo());
            }
            if (changed) {
                approvalDao.updateById(t);
            }
        }
    }

    /** 升级对象 = 链上下一（更高）级；VP 已最高 → 自身标注 */
    private String escalateTarget(int nodeNo) {
        if (nodeNo < NODE_ROLES.length) {
            return NODE_ROLES[nodeNo]; // nodeNo 1(索引0) → DIRECTOR
        }
        return NODE_ROLES[NODE_ROLES.length - 1] + "(最高级)";
    }

    // ---------- 私有 ----------

    private void insertTask(String prId, int batch, int nodeNo, String role, String status,
                            String action, String reason) {
        ProcPrApproval t = new ProcPrApproval();
        t.setPrId(prId);
        t.setSubmitBatch(batch);
        t.setNodeNo(nodeNo);
        t.setNodeRole(role);
        t.setStatus(status);
        t.setAction(action);
        t.setActionReason(reason);
        t.setActedBy(SecurityUtils.getCurrentUserId());
        t.setActionDate(LocalDateTime.now());
        t.setTimeoutFlag("0");
        t.setEscalateFlag("0");
        approvalDao.insert(t);
    }

    private void markAction(ProcPrApproval task, String action, String reason) {
        task.setAction(action);
        task.setActionReason(reason);
        task.setActedBy(SecurityUtils.getCurrentUserId());
        task.setActionDate(LocalDateTime.now());
        task.setStatus("PASS".equals(action) ? "PASSED" : "REJECTED");
        approvalDao.updateById(task);
    }

    private void requireActive(ProcPrApproval task) {
        if (!"ACTIVE".equals(task.getStatus())) {
            throw new ServiceException(422, "任务已处理（当前 " + task.getStatus() + "），不可重复操作");
        }
    }

    private ProcPrApproval requireTask(String taskId) {
        ProcPrApproval t = approvalDao.selectById(taskId);
        if (t == null) {
            throw new ServiceException(404, "审批任务不存在");
        }
        if (t.getNodeNo() == 0) {
            throw new ServiceException(422, "提交日志行无动作语义");
        }
        return t;
    }

    private List<ProcPrLine> linesOf(String prId) {
        return lineDao.selectList(new LambdaQueryWrapper<ProcPrLine>()
                .eq(ProcPrLine::getPrId, prId).orderByAsc(ProcPrLine::getLineNo));
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
