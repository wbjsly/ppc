package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDao;
import com.erp.dao.proc.PoApprovalLogDao;
import com.erp.dao.proc.PoApprovalTaskDao;
import com.erp.dao.proc.PurchaseOrderDao;
import com.erp.dao.proc.PurchaseOrderLineDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.proc.PoApprovalLog;
import com.erp.entity.proc.PoApprovalTask;
import com.erp.entity.proc.PurchaseOrder;
import com.erp.entity.proc.PurchaseOrderLine;
import com.erp.entity.system.SysUser;
import com.erp.service.proc.PoApprovalService;
import com.erp.service.proc.ProcBudgetService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PO 独立审批（design D1，spec purchase-order「订单三档分级审批」）。
 * 判级：SPECIAL > 超预算（科目本年累计 + 本次 > 预算 × 110%）> 常规；
 * 价控行结果 ESCALATE（BR-4.2-15/17）→ 链尾强制追加采购总监并标记 ESCALATE_FLAG。
 * 待办口径与 2.1.4 一致（单租户管理端全量 ACTIVE 节点 + 节点角色标注，无采购角色表）。
 * <p>本变更偏差（add-framework-agreement-order proposal 偏差表）：</p>
 * <ul>
 *   <li>偏差 D3 —— 审批展示的供应商绩效/库存水位为"模块未上线"占位（todo 返回 placeholder 字段）；</li>
 *   <li>偏差 D4 —— 末节点通过仅置"已批准（已下达）"并留痕，不自动外发供应商（无邮件/EDI/门户集成）；</li>
 *   <li>偏差 D7 —— 不做超时自动升级/催办（规格未对 PO 审批规定超时条款），仅节点任务 + 批次日志。</li>
 * </ul>
 */
@Slf4j
@Service
public class PoApprovalServiceImpl implements PoApprovalService {

    public static final String M = "PURCHASE_MANAGER";
    public static final String D = "PURCHASE_DIRECTOR";
    public static final String VP = "VICE_PRESIDENT";

    private final PoApprovalTaskDao taskDao;
    private final PoApprovalLogDao logDao;
    private final PurchaseOrderDao poDao;
    private final PurchaseOrderLineDao lineDao;
    private final ProcBudgetService budgetService;
    private final MdmItemDao itemDao;
    private final SysUserDao sysUserDao;

    public PoApprovalServiceImpl(PoApprovalTaskDao taskDao,
                                 PoApprovalLogDao logDao,
                                 PurchaseOrderDao poDao,
                                 PurchaseOrderLineDao lineDao,
                                 ProcBudgetService budgetService,
                                 MdmItemDao itemDao,
                                 SysUserDao sysUserDao) {
        this.taskDao = taskDao;
        this.logDao = logDao;
        this.poDao = poDao;
        this.lineDao = lineDao;
        this.budgetService = budgetService;
        this.itemDao = itemDao;
        this.sysUserDao = sysUserDao;
    }

    // ---------- 判级与建链（FR-4.2-3-2 / design D1） ----------

    @Override
    @Transactional
    public List<Map<String, Object>> createTasks(PurchaseOrder po) {
        int batch = po.getApprovalBatch() == null ? 1 : po.getApprovalBatch();
        List<String[]> chain = new ArrayList<>();   // [role, label, escalateFlag, escalateReason]

        List<PurchaseOrderLine> lines = lineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                .eq(PurchaseOrderLine::getPoId, po.getId()));

        if ("SPECIAL".equals(po.getPoType())) {
            // 特殊 PO（紧急、关联交易）：采购总监 → 分管副总裁
            chain.add(new String[]{D, "采购总监", "0", null});
            chain.add(new String[]{VP, "分管副总裁", "0", null});
        } else if (isOverBudget(po, lines)) {
            // 超预算：采购经理 → 采购总监（C-4.2-03 / BR-4.2-17）
            chain.add(new String[]{M, "采购经理", "0", null});
            chain.add(new String[]{D, "采购总监", "0", "预算阈值升级（BR-4.2-17）"});
        } else {
            // 常规：采购经理单节点
            chain.add(new String[]{M, "采购经理", "0", null});
        }

        // 价控命中强制加签（BR-4.2-15：超协议价容差 → 采购总监）
        String escReason = priceEscalateReason(lines);
        if (escReason != null) {
            boolean hasD = chain.stream().anyMatch(c -> D.equals(c[0]));
            if (!hasD) {
                chain.add(new String[]{D, "采购总监", "1", escReason});
            } else {
                for (String[] c : chain) {
                    if (D.equals(c[0])) {
                        c[2] = "1";
                        c[3] = escReason;
                    }
                }
            }
        }

        List<Map<String, Object>> out = new ArrayList<>();
        int no = 1;
        for (String[] c : chain) {
            PoApprovalTask t = new PoApprovalTask();
            t.setPoId(po.getId());
            t.setSubmitBatch(batch);
            t.setNodeNo(no);
            t.setNodeRole(c[0]);
            t.setNodeLabel(c[1]);
            t.setStatus(no == 1 ? "ACTIVE" : "WAITING");
            t.setEscalateFlag(c[2]);
            t.setEscalateReason(c[3]);
            t.setCreateBy(SecurityUtils.getCurrentUserId());
            taskDao.insert(t);
            if ("1".equals(c[2])) {
                writeLog(po, batch, no, c[0], "ESCALATE", c[3]);
            }
            out.add(toTaskRow(t));
            no++;
        }
        writeLog(po, batch, 0, "SYSTEM", "SUBMIT",
                "提交审批，判级档位：" + tierName(po, chain) + "，节点 " + chain.size() + " 个");
        log.info("PO {} approval batch={} chain={} overBudget={} escalate={}",
                po.getPoNo(), batch, chain.size(), isOverBudget(po, lines), escReason);
        return out;
    }

    // ---------- 节点动作 ----------

    @Override
    @Transactional
    public List<Map<String, Object>> pass(String taskId) {
        return advance(taskId, "PASS", null, null);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> passConditional(String taskId, String conditionText) {
        if (conditionText == null || conditionText.trim().length() < 2) {
            throw new ServiceException(422, "条件批准须填写附加条件（不少于 2 字）");
        }
        return advance(taskId, "CONDITIONAL", conditionText.trim(), null);
    }

    @Override
    @Transactional
    public List<Map<String, Object>> reject(String taskId, String reason) {
        PoApprovalTask t = requireActive(taskId);
        if (reason == null || reason.trim().length() < 2) {
            throw new ServiceException(422, "驳回原因必填（不少于 2 字）");
        }
        PurchaseOrder po = requirePo(t.getPoId());
        LocalDateTime now = LocalDateTime.now();
        t.setStatus("REJECTED");
        t.setAction("REJECT");
        t.setActionReason(reason.trim());
        t.setActedBy(SecurityUtils.getCurrentUserId());
        t.setActedByName(userName(t.getActedBy()));
        t.setActionDate(now);
        taskDao.updateById(t);
        // 同批次余节点作废
        for (PoApprovalTask rest : taskDao.selectList(new LambdaQueryWrapper<PoApprovalTask>()
                .eq(PoApprovalTask::getPoId, po.getId())
                .eq(PoApprovalTask::getSubmitBatch, t.getSubmitBatch())
                .eq(PoApprovalTask::getStatus, "WAITING"))) {
            rest.setStatus("SUPERSEDED");
            taskDao.updateById(rest);
        }
        writeLog(po, t.getSubmitBatch(), t.getNodeNo(), t.getNodeRole(), "REJECT", reason.trim());
        // PO 回草稿（可改后重提，批次 +1 由 submit 负责）
        po.setStatus("DRAFT");
        po.setUpdateBy(t.getActedBy());
        poDao.updateById(po);
        log.info("PO {} approval batch={} node={} rejected by {}: {}",
                po.getPoNo(), t.getSubmitBatch(), t.getNodeNo(), t.getActedBy(), reason.trim());
        return logs(po.getId());
    }

    // ---------- 待办与日志 ----------

    @Override
    public List<Map<String, Object>> todo() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PoApprovalTask t : taskDao.selectList(new LambdaQueryWrapper<PoApprovalTask>()
                .eq(PoApprovalTask::getStatus, "ACTIVE")
                .orderByDesc(PoApprovalTask::getCreateDate))) {
            PurchaseOrder po = poDao.selectById(t.getPoId());
            if (po == null || "CLOSED".equals(po.getStatus())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("taskId", t.getId());
            row.put("poId", po.getId());
            row.put("poNo", po.getPoNo());
            row.put("poType", po.getPoType());
            row.put("source", po.getSource());
            row.put("supplierName", po.getSupplierName());
            row.put("totalAmt", po.getTotalAmt());
            row.put("status", po.getStatus());
            row.put("submitBatch", t.getSubmitBatch());
            row.put("nodeNo", t.getNodeNo());
            row.put("nodeRole", t.getNodeRole());
            row.put("nodeLabel", t.getNodeLabel());
            row.put("escalateFlag", t.getEscalateFlag());
            row.put("escalateReason", t.getEscalateReason());
            row.put("createDate", po.getCreateDate());
            // 路由链（本批次全节点）
            List<Map<String, Object>> chain = new ArrayList<>();
            for (PoApprovalTask c : taskDao.selectList(new LambdaQueryWrapper<PoApprovalTask>()
                    .eq(PoApprovalTask::getPoId, po.getId())
                    .eq(PoApprovalTask::getSubmitBatch, t.getSubmitBatch())
                    .orderByAsc(PoApprovalTask::getNodeNo))) {
                chain.add(toTaskRow(c));
            }
            row.put("chain", chain);
            // 价控结果摘要（spec：审批展示价控校验结果；绩效/库存水位为占位，偏差 D3）
            List<Map<String, Object>> pc = new ArrayList<>();
            for (PurchaseOrderLine l : lineDao.selectList(new LambdaQueryWrapper<PurchaseOrderLine>()
                    .eq(PurchaseOrderLine::getPoId, po.getId())
                    .orderByAsc(PurchaseOrderLine::getLineNo))) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("lineNo", l.getLineNo());
                p.put("itemCode", l.getItemCode());
                p.put("unitPrice", l.getUnitPrice());
                p.put("priceCtrlResult", l.getPriceCtrlResult());
                pc.add(p);
            }
            row.put("priceControl", pc);
            row.put("performancePlaceholder", "模块未上线");   // 偏差 D3
            row.put("stockPlaceholder", "模块未上线");          // 偏差 D3
            out.add(row);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> logs(String poId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PoApprovalLog l : logDao.selectList(new LambdaQueryWrapper<PoApprovalLog>()
                .eq(PoApprovalLog::getPoId, poId)
                .orderByDesc(PoApprovalLog::getActionDate)
                .orderByDesc(PoApprovalLog::getId))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", l.getId());
            row.put("submitBatch", l.getSubmitBatch());
            row.put("nodeNo", l.getNodeNo());
            row.put("nodeRole", l.getNodeRole());
            row.put("action", l.getAction());
            row.put("actionReason", l.getActionReason());
            row.put("actedBy", l.getActedBy());
            row.put("actedByName", l.getActedByName());
            row.put("actionDate", l.getActionDate());
            out.add(row);
        }
        return out;
    }

    // ---------- 私有 ----------

    private List<Map<String, Object>> advance(String taskId, String action,
                                              String condition, String unused) {
        PoApprovalTask t = requireActive(taskId);
        PurchaseOrder po = requirePo(t.getPoId());
        LocalDateTime now = LocalDateTime.now();
        t.setStatus("APPROVED");
        t.setAction(action);
        t.setConditionText(condition);
        t.setActedBy(SecurityUtils.getCurrentUserId());
        t.setActedByName(userName(t.getActedBy()));
        t.setActionDate(now);
        taskDao.updateById(t);
        writeLog(po, t.getSubmitBatch(), t.getNodeNo(), t.getNodeRole(),
                "CONDITIONAL".equals(action) ? "CONDITIONAL" : "PASS",
                condition != null ? "条件：" + condition : null);

        PoApprovalTask next = taskDao.selectOne(new LambdaQueryWrapper<PoApprovalTask>()
                .eq(PoApprovalTask::getPoId, po.getId())
                .eq(PoApprovalTask::getSubmitBatch, t.getSubmitBatch())
                .eq(PoApprovalTask::getStatus, "WAITING")
                .orderByAsc(PoApprovalTask::getNodeNo)
                .last("LIMIT 1"));
        if (next != null) {
            next.setStatus("ACTIVE");
            taskDao.updateById(next);
            log.info("PO {} batch={} node {} passed, next node {} ({})",
                    po.getPoNo(), t.getSubmitBatch(), t.getNodeNo(),
                    next.getNodeNo(), next.getNodeRole());
        } else {
            // 末节点 → 已批准（已下达）
            po.setStatus("APPROVED");
            po.setUpdateBy(t.getActedBy());
            poDao.updateById(po);
            log.info("PO {} approved (batch={}, all nodes passed)", po.getPoNo(), t.getSubmitBatch());
        }
        return logs(po.getId());
    }

    private PoApprovalTask requireActive(String taskId) {
        PoApprovalTask t = taskDao.selectById(taskId);
        if (t == null) {
            throw new ServiceException(404, "审批任务不存在");
        }
        if (!"ACTIVE".equals(t.getStatus())) {
            throw new ServiceException(422, "该节点不在待办状态（当前 " + t.getStatus() + "），可能已被处理");
        }
        return t;
    }

    private PurchaseOrder requirePo(String id) {
        PurchaseOrder po = poDao.selectById(id);
        if (po == null) {
            throw new ServiceException(404, "采购订单不存在");
        }
        return po;
    }

    /** 超预算判定（BR-4.2-17）：任一涉及科目 本年累计 + 本次 > 预算 × trigger */
    private boolean isOverBudget(PurchaseOrder po, List<PurchaseOrderLine> lines) {
        int year = java.time.LocalDate.now().getYear();
        for (String category : categoriesOf(lines)) {
            Map<String, Object> budget = budgetService.budgetOf(category, year);
            if (budget == null) {
                continue;   // NO_BUDGET：未设预算不判超（spec 场景「未设预算放行」）
            }
            BigDecimal used = (BigDecimal) budget.get("usedAmt");
            BigDecimal trigger = (BigDecimal) budget.get("triggerAmt");
            if (used != null && trigger != null && po.getTotalAmt() != null
                    && used.add(po.getTotalAmt()).compareTo(trigger) > 0) {
                return true;
            }
        }
        return false;
    }

    /** 价控第 1 级命中（BR-4.2-15）：任一行结果 ESCALATE → 加签原因 */
    private String priceEscalateReason(List<PurchaseOrderLine> lines) {
        for (PurchaseOrderLine l : lines) {
            if ("ESCALATE".equals(l.getPriceCtrlResult())) {
                return "价控命中：行 " + l.getLineNo() + " 物料 " + l.getItemCode()
                        + " 超协议价容差（BR-4.2-15）";
            }
        }
        return null;
    }

    private List<String> categoriesOf(List<PurchaseOrderLine> lines) {
        List<String> out = new ArrayList<>();
        for (PurchaseOrderLine l : lines) {
            MdmItem item = itemDao.selectOne(new LambdaQueryWrapper<MdmItem>()
                    .eq(MdmItem::getItemCode, l.getItemCode()).last("LIMIT 1"));
            if (item != null && item.getCategoryCode() != null && !out.contains(item.getCategoryCode())) {
                out.add(item.getCategoryCode());
            }
        }
        return out;
    }

    private String tierName(PurchaseOrder po, List<String[]> chain) {
        if ("SPECIAL".equals(po.getPoType())) {
            return "特殊 PO（采购总监 → 分管副总裁）";
        }
        if (chain.size() >= 2) {
            return "超预算 PO（采购经理 → 采购总监）";
        }
        return "常规 PO（采购经理）";
    }

    private void writeLog(PurchaseOrder po, int batch, int nodeNo, String role,
                          String action, String reason) {
        PoApprovalLog l = new PoApprovalLog();
        l.setPoId(po.getId());
        l.setPoNo(po.getPoNo());
        l.setSubmitBatch(batch);
        l.setNodeNo(nodeNo);
        l.setNodeRole(role);
        l.setAction(action);
        l.setActionReason(reason);
        l.setActedBy(SecurityUtils.getCurrentUserId());
        l.setActedByName(userName(l.getActedBy()));
        l.setActionDate(LocalDateTime.now());
        logDao.insert(l);
    }

    private Map<String, Object> toTaskRow(PoApprovalTask t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("taskId", t.getId());
        row.put("nodeNo", t.getNodeNo());
        row.put("nodeRole", t.getNodeRole());
        row.put("nodeLabel", t.getNodeLabel());
        row.put("status", t.getStatus());
        row.put("action", t.getAction());
        row.put("conditionText", t.getConditionText());
        row.put("escalateFlag", t.getEscalateFlag());
        row.put("escalateReason", t.getEscalateReason());
        row.put("actedByName", t.getActedByName());
        row.put("actionDate", t.getActionDate());
        return row;
    }

    private String userName(String userId) {
        if (userId == null) {
            return null;
        }
        SysUser u = sysUserDao.selectById(userId);
        return u == null ? userId
                : (u.getNickName() == null || u.getNickName().isEmpty()) ? u.getUsername() : u.getNickName();
    }
}
