package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.qms.NcrDao;
import com.erp.dao.qms.ScarDao;
import com.erp.dao.qms.ScarDeductionDao;
import com.erp.entity.qms.Ncr;
import com.erp.entity.qms.Scar;
import com.erp.entity.qms.ScarDeduction;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.qms.ScarService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SCAR 实现（spec supplier-quality-claim，tasks 10.1~10.5）。
 * NCR 退货/挑选自动带出草稿；重大（索赔 ≥1 万）质量经理审批后 SENT；
 * 回复时限 5 工作日（跳周末），超期升采购经理 + 每 3 天提醒 + 扣分标记（幂等）；
 * SQE 代录浅层根因退回计数；扣款单四态 + 货款抵扣由付款单挂接（D3 已落地，add-payment-management）。
 */
@Slf4j
@Service
public class ScarServiceImpl implements ScarService {

    private static final String[] SHALLOW_KEYS = {"疏忽", "不小心", "操作失误", "未注意", "大意", "失误"};
    /** 重大 SCAR 索赔阈值（质量经理审批） */
    private static final BigDecimal MAJOR_AMOUNT = new BigDecimal("10000");

    @Value("${app.qms.scar-reply-days:5}")
    private int replyDays;
    @Value("${app.qms.scar-remind-days:3}")
    private int remindDays;

    private final ScarDao scarDao;
    private final ScarDeductionDao deductionDao;
    private final NcrDao ncrDao;
    private final ApprovalEngine approvalEngine;

    public ScarServiceImpl(ScarDao scarDao, ScarDeductionDao deductionDao, NcrDao ncrDao,
                           ApprovalEngine approvalEngine) {
        this.scarDao = scarDao;
        this.deductionDao = deductionDao;
        this.ncrDao = ncrDao;
        this.approvalEngine = approvalEngine;
    }

    // ================= 10.1 触发草稿 =================

    @Override
    @Transactional
    public Scar createFromNcr(Ncr ncr, String triggerType) {
        if (ncr == null) {
            return null;
        }
        // 幂等：同 NCR 已有未作废 SCAR
        Scar existed = scarDao.selectOne(new LambdaQueryWrapper<Scar>()
                .eq(Scar::getNcrId, ncr.getId())
                .notIn(Scar::getStatus, "CLOSED")
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }
        Scar s = new Scar();
        s.setScarNo(nextScarNo());
        s.setNcrId(ncr.getId());
        s.setNcrNo(ncr.getNcrNo());
        s.setSupplierId(ncr.getSupplierId());
        s.setSupplierName(ncr.getSupplierName());
        s.setTriggerType(triggerType);
        s.setTitle("SCAR：" + ncr.getSupplierName() + " / " + ncr.getNcrNo()
                + (hasText(ncr.getDefectItem()) ? "（" + ncr.getDefectItem() + "）" : ""));
        s.setDefectDesc(ncr.getDefectDesc());
        // 索赔明细：退货数量按 PO 单价、挑选报废按不合格量估——留空由 SQE 补录
        s.setClaimAmount(BigDecimal.ZERO);
        s.setStatus("DRAFT");
        s.setRejectCount(0);
        s.setReplyRemindCount(0);
        s.setEscalatedFlag("0");
        s.setCreateBy(SecurityUtils.getCurrentUserId());
        scarDao.insert(s);
        log.info("SCAR {} draft from NCR {} (trigger={})", s.getScarNo(), ncr.getNcrNo(), triggerType);
        return s;
    }

    @Override
    @Transactional
    public Scar createDraft(Map<String, Object> body) {
        requireRole("发起 SCAR", "ROLE_SQE", "ROLE_QUALITY_MGR");
        String triggerType = str(body.get("triggerType"));
        if (!hasText(triggerType) || !List.of("NCR_RETURN", "REPEAT", "STOPPAGE").contains(triggerType)) {
            throw new ServiceException(422, "触发类型必填（NCR_RETURN 退货挑选 / REPEAT 重复不合格 / STOPPAGE 停线投诉）");
        }
        if (!hasText(str(body.get("supplierId")))) {
            throw new ServiceException(422, "供应商必填");
        }
        if (!hasText(str(body.get("title")))) {
            throw new ServiceException(422, "SCAR 标题必填");
        }
        Scar s = new Scar();
        s.setScarNo(nextScarNo());
        String ncrId = str(body.get("ncrId"));
        if (hasText(ncrId)) {
            Ncr ncr = ncrDao.selectById(ncrId);
            if (ncr != null) {
                s.setNcrId(ncr.getId());
                s.setNcrNo(ncr.getNcrNo());
                if (!hasText(s.getDefectDesc())) {
                    s.setDefectDesc(ncr.getDefectDesc());
                }
            }
        }
        s.setSupplierId(str(body.get("supplierId")));
        s.setSupplierName(str(body.get("supplierName")));
        s.setTriggerType(triggerType);
        s.setTitle(str(body.get("title")));
        s.setDefectDesc(str(body.get("defectDesc")));
        s.setClaimItems(str(body.get("claimItems")));
        BigDecimal claim;
        try {
            claim = new BigDecimal(String.valueOf(body.get("claimAmount") == null ? "0" : body.get("claimAmount")));
        } catch (Exception e) {
            throw new ServiceException(422, "索赔金额必须为数字");
        }
        s.setClaimAmount(claim == null ? BigDecimal.ZERO : claim);
        s.setStatus("DRAFT");
        s.setRejectCount(0);
        s.setReplyRemindCount(0);
        s.setEscalatedFlag("0");
        s.setCreateBy(SecurityUtils.getCurrentUserId());
        scarDao.insert(s);
        return s;
    }

    // ================= 发出（重大走审批） =================

    @Override
    @Transactional
    public Scar submit(String id) {
        requireRole("发出 SCAR", "ROLE_SQE", "ROLE_QUALITY_MGR");
        Scar s = require(id);
        if ("SENT".equals(s.getStatus()) || "REPLYING".equals(s.getStatus())
                || "VERIFYING".equals(s.getStatus())) {
            throw new ServiceException(422, "已发出，不可重复提交");
        }
        if (!"DRAFT".equals(s.getStatus())) {
            throw new ServiceException(422, "当前状态不可发出：" + s.getStatus());
        }
        if (!hasText(s.getTitle()) || !hasText(s.getSupplierId())) {
            throw new ServiceException(422, "标题与供应商必填");
        }
        boolean major = s.getClaimAmount() != null && s.getClaimAmount().compareTo(MAJOR_AMOUNT) >= 0;
        if (major) {
            // 重大 SCAR：质量经理审批后 SENT
            List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                    ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "重大 SCAR 审批")));
            var inst = approvalEngine.submit("ScarMajor", s.getId(),
                    "重大 SCAR 发出审批：" + s.getScarNo() + " / 索赔 " + s.getClaimAmount(),
                    "ROLE_QUALITY_DIRECTOR", chain);
            s.setApprovalId(inst.getId());
            scarDao.updateById(s);
            return s;
        }
        return doSend(s);
    }

    /** 发出：SENT + 5 工作日回复时限 */
    private Scar doSend(Scar s) {
        s.setStatus("SENT");
        s.setSentDate(LocalDateTime.now());
        s.setReplyDueDate(addWorkdays(LocalDate.now(), replyDays));
        s.setEscalatedFlag("0");
        if (scarDao.updateById(s) == 0) {
            throw new ServiceException(409, "SCAR 状态更新冲突");
        }
        log.info("SCAR {} sent, reply due {}", s.getScarNo(), s.getReplyDueDate());
        return s;
    }

    // ================= 10.4 回复（SQE 代录，浅层退回） =================

    @Override
    // 不加 @Transactional：浅层根因退回须先落 REJECT_COUNT 再抛 422（事务回滚会吃掉计数）
    public Scar recordReply(String id, String replyText) {
        // 偏差 D7（add-quality-collaboration）——供应商门户未建，8D 回复由 SQE 代录（spec supplier-quality-claim）
        requireRole("代录 SCAR 回复", "ROLE_SQE", "ROLE_QUALITY_MGR");
        Scar s = require(id);
        if (!"SENT".equals(s.getStatus()) && !"VERIFYING".equals(s.getStatus())) {
            throw new ServiceException(422, "当前状态不可录入回复：" + s.getStatus());
        }
        if (!hasText(replyText) || replyText.trim().length() < 10) {
            // 浅层回复也计退回
            s.setRejectCount((s.getRejectCount() == null ? 0 : s.getRejectCount()) + 1);
            scarDao.updateById(s);
            throw new ServiceException(422, "8D 回复过简（至少 10 字），已记退回（第 "
                    + s.getRejectCount() + " 次）");
        }
        for (String k : SHALLOW_KEYS) {
            if (replyText.contains(k) && !replyText.contains("为什么") && !replyText.contains("5Why")) {
                s.setRejectCount((s.getRejectCount() == null ? 0 : s.getRejectCount()) + 1);
                scarDao.updateById(s);
                throw new ServiceException(422, "回复含浅层根因「" + k + "」退回（BR-4.12-45，第 "
                        + s.getRejectCount() + " 次）：" + replyText.trim().length() + " 字须补 5Why");
            }
        }
        s.setReplyText(replyText);
        s.setReplyBy(SecurityUtils.getCurrentUserId());
        s.setReplyDate(LocalDateTime.now());
        s.setStatus("VERIFYING");
        if (scarDao.updateById(s) == 0) {
            throw new ServiceException(409, "SCAR 状态更新冲突");
        }
        return s;
    }

    @Override
    @Transactional
    public Scar verify(String id, boolean pass, String conclusion) {
        requireRole("验证 SCAR 回复", "ROLE_SQE", "ROLE_QUALITY_MGR");
        Scar s = require(id);
        if (!"VERIFYING".equals(s.getStatus())) {
            throw new ServiceException(422, "仅回复待验证状态可操作：" + s.getStatus());
        }
        if (!hasText(conclusion) || conclusion.trim().length() < 2) {
            throw new ServiceException(422, "验证结论必填（至少 2 字）");
        }
        s.setVerifyResult(pass ? "PASS" : "FAIL");
        s.setVerifyBy(SecurityUtils.getCurrentUserId());
        s.setVerifyDate(LocalDateTime.now());
        if (pass) {
            s.setStatus("CLOSED");
            s.setClosedDate(LocalDateTime.now());
            s.setRemark(conclusion);
        } else {
            // 退回：清回复、时限重置（响应超期扣分标记保持）
            s.setStatus("SENT");
            s.setReplyText(null);
            s.setReplyDate(null);
            s.setReplyDueDate(addWorkdays(LocalDate.now(), replyDays));
            s.setRemark("验证退回：" + conclusion);
        }
        if (scarDao.updateById(s) == 0) {
            throw new ServiceException(409, "SCAR 状态更新冲突");
        }
        return s;
    }

    // ================= 10.3 回复超期扫描（幂等） =================

    @Override
    public int sweepReplyOverdue() {
        List<Scar> over = scarDao.selectList(new LambdaQueryWrapper<Scar>()
                .in(Scar::getStatus, "SENT", "VERIFYING")
                .isNotNull(Scar::getReplyDueDate)
                .lt(Scar::getReplyDueDate, LocalDate.now())
                .last("LIMIT 200"));
        int handled = 0;
        LocalDateTime now = LocalDateTime.now();
        for (Scar s : over) {
            if (!"1".equals(s.getEscalatedFlag())) {
                // 首次超期：升级采购经理 + 响应及时性扣分标记（评分数据源）
                s.setEscalatedFlag("1");
                s.setReplyRemindCount(1);
                s.setLastRemindTime(now);
                s.setRemark((hasText(s.getRemark()) ? s.getRemark() + "；" : "")
                        + "回复超期升级采购经理（BR-4.12-45），响应及时性扣分");
                scarDao.updateById(s);
                log.warn("SCAR {} reply overdue → escalate PM + response score flag", s.getScarNo());
                handled++;
            } else {
                LocalDateTime last = s.getLastRemindTime();
                if (last != null && !last.plusDays(remindDays).isAfter(now)) {
                    s.setReplyRemindCount((s.getReplyRemindCount() == null ? 1 : s.getReplyRemindCount()) + 1);
                    s.setLastRemindTime(now);
                    scarDao.updateById(s);
                    log.warn("SCAR {} overdue reminder #{}", s.getScarNo(), s.getReplyRemindCount());
                    handled++;
                }
            }
        }
        return handled;
    }

    // ================= 10.5 扣款单 =================

    @Override
    @Transactional
    public ScarDeduction createDeduction(String scarId, BigDecimal amount, String remark) {
        requireRole("生成扣款单", "ROLE_SQE", "ROLE_QUALITY_MGR");
        Scar s = require(scarId);
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "扣款金额必须大于 0");
        }
        if (s.getClaimAmount() != null && amount.compareTo(s.getClaimAmount()) > 0) {
            throw new ServiceException(422, "扣款金额超过索赔额（索赔 " + s.getClaimAmount() + "）");
        }
        ScarDeduction d = new ScarDeduction();
        d.setDeductNo(nextDeductNo());
        d.setScarId(s.getId());
        d.setScarNo(s.getScarNo());
        d.setSupplierId(s.getSupplierId());
        d.setSupplierName(s.getSupplierName());
        d.setAmount(amount);
        d.setStatus("PENDING_FINANCE");
        d.setRemark(remark);
        d.setCreateBy(SecurityUtils.getCurrentUserId());
        deductionDao.insert(d);
        return d;
    }

    @Override
    @Transactional
    public ScarDeduction submitFinance(String deductionId) {
        requireRole("扣款财务确认", "ROLE_ADMIN", "ROLE_QUALITY_MGR");
        ScarDeduction d = requireDeduction(deductionId);
        if ("CONFIRMED".equals(d.getStatus()) || "TO_DEDUCT".equals(d.getStatus())) {
            throw new ServiceException(422, "已确认，不可重复提交");
        }
        if (!"PENDING_FINANCE".equals(d.getStatus())) {
            throw new ServiceException(422, "状态不可确认：" + d.getStatus());
        }
        List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                ApprovalNodeSpec.sign("ROLE_ADMIN", "扣款财务确认（ADMIN 代）")));
        var inst = approvalEngine.submit("ScarFinance", d.getId(),
                "SCAR 扣款确认：" + d.getDeductNo() + " / " + d.getAmount(), null, chain);
        d.setApprovalId(inst.getId());
        if (deductionDao.updateById(d) == 0) {
            throw new ServiceException(409, "扣款单状态更新冲突");
        }
        return d;
    }

    @Override
    @Transactional
    public ScarDeduction markToDeduct(String deductionId) {
        // D3（add-quality-collaboration 留桩 → add-payment-management 落地）——抵扣动作在付款执行时完成
        requireRole("推送待抵扣", "ROLE_ADMIN", "ROLE_QUALITY_MGR", "ROLE_SQE");
        ScarDeduction d = requireDeduction(deductionId);
        if ("TO_DEDUCT".equals(d.getStatus())) {
            return d; // 幂等
        }
        if (!"CONFIRMED".equals(d.getStatus())) {
            throw new ServiceException(422, "仅已确认扣款可推送抵扣：" + d.getStatus());
        }
        d.setStatus("TO_DEDUCT");
        if (deductionDao.updateById(d) == 0) {
            throw new ServiceException(409, "扣款单状态更新冲突");
        }
        // 货款实际抵扣由付款单挂接完成（D3 已落地，spec payment-execution；原 TODO-NOTIFY 桩移除）
        log.info("deduction {} 推送待抵扣，等待付款单挂接抵扣", d.getDeductNo());
        return d;
    }

    @Override
    @Transactional
    public ScarDeduction dispute(String deductionId, String reason) {
        requireRole("扣款争议", "ROLE_SQE", "ROLE_QUALITY_MGR", "ROLE_ADMIN");
        ScarDeduction d = requireDeduction(deductionId);
        if ("TO_DEDUCT".equals(d.getStatus())) {
            d.setStatus("DISPUTED");
            d.setDisputeReason(reason);
        } else if ("DISPUTED".equals(d.getStatus())) {
            d.setStatus("CONFIRMED"); // 争议解除回已确认
            d.setDisputeReason((d.getDisputeReason() == null ? "" : d.getDisputeReason() + "；")
                    + "争议解除：" + reason);
        } else {
            throw new ServiceException(422, "仅待抵扣/争议中可操作争议：" + d.getStatus());
        }
        if (deductionDao.updateById(d) == 0) {
            throw new ServiceException(409, "扣款单状态更新冲突");
        }
        return d;
    }

    @Override
    public Map<String, Object> deductionSummary(String supplierId) {
        List<ScarDeduction> list = deductionDao.selectList(new LambdaQueryWrapper<ScarDeduction>()
                .eq(hasText(supplierId), ScarDeduction::getSupplierId, supplierId)
                .notIn(ScarDeduction::getStatus, "CANCELLED"));
        BigDecimal confirmed = BigDecimal.ZERO;
        BigDecimal toDeduct = BigDecimal.ZERO;
        BigDecimal disputed = BigDecimal.ZERO;
        int n = 0;
        for (ScarDeduction d : list) {
            n++;
            if ("CONFIRMED".equals(d.getStatus()) || "TO_DEDUCT".equals(d.getStatus())) {
                confirmed = confirmed.add(d.getAmount());
            }
            if ("TO_DEDUCT".equals(d.getStatus())) {
                toDeduct = toDeduct.add(d.getAmount());
            }
            if ("DISPUTED".equals(d.getStatus())) {
                disputed = disputed.add(d.getAmount());
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supplierId", supplierId);
        out.put("deductionCount", n);
        out.put("confirmedAmount", confirmed);
        out.put("toDeductAmount", toDeduct);
        out.put("disputedAmount", disputed);
        out.put("scoreSource", confirmed); // 质量评分的成本维度数据源（BR-4.12-42）
        return out;
    }

    // ================= 查询 =================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String status,
                                          String supplierId) {
        LambdaQueryWrapper<Scar> qw = new LambdaQueryWrapper<Scar>()
                .eq(hasText(status), Scar::getStatus, status)
                .eq(hasText(supplierId), Scar::getSupplierId, supplierId)
                .and(hasText(keyword), w -> w.like(Scar::getScarNo, keyword)
                        .or().like(Scar::getTitle, keyword)
                        .or().like(Scar::getSupplierName, keyword)
                        .or().like(Scar::getNcrNo, keyword))
                .orderByDesc(Scar::getCreateDate);
        Page<Scar> raw = scarDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Scar s : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("scarNo", s.getScarNo());
            m.put("ncrNo", s.getNcrNo());
            m.put("supplierName", s.getSupplierName());
            m.put("triggerType", s.getTriggerType());
            m.put("title", s.getTitle());
            m.put("claimAmount", s.getClaimAmount());
            m.put("status", s.getStatus());
            m.put("replyDueDate", s.getReplyDueDate());
            m.put("replyOverdue", "SENT".equals(s.getStatus()) && s.getReplyDueDate() != null
                    && s.getReplyDueDate().isBefore(today));
            m.put("escalatedFlag", s.getEscalatedFlag());
            m.put("replyRemindCount", s.getReplyRemindCount());
            m.put("rejectCount", s.getRejectCount());
            m.put("verifyResult", s.getVerifyResult());
            m.put("sentDate", s.getSentDate());
            m.put("createDate", s.getCreateDate());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        Scar s = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scar", s);
        if (hasText(s.getNcrId())) {
            out.put("ncr", ncrDao.selectById(s.getNcrId()));
        }
        out.put("deductions", deductions(id));
        if (hasText(s.getApprovalId())) {
            out.put("approval", approvalEngine.getInstance(s.getApprovalId()));
        }
        return out;
    }

    @Override
    public List<ScarDeduction> deductions(String scarId) {
        return deductionDao.selectList(new LambdaQueryWrapper<ScarDeduction>()
                .eq(ScarDeduction::getScarId, scarId)
                .orderByDesc(ScarDeduction::getCreateDate));
    }

    // ================= 内部 =================

    private Scar require(String id) {
        Scar s = scarDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "SCAR 不存在：" + id);
        }
        return s;
    }

    private ScarDeduction requireDeduction(String id) {
        ScarDeduction d = deductionDao.selectById(id);
        if (d == null) {
            throw new ServiceException(404, "扣款单不存在：" + id);
        }
        return d;
    }

    /** 加工作日（跳周末，5 工作日回复时限） */
    private LocalDate addWorkdays(LocalDate from, int days) {
        LocalDate d = from;
        int added = 0;
        while (added < days) {
            d = d.plusDays(1);
            if (d.getDayOfWeek().getValue() < 6) {
                added++;
            }
        }
        return d;
    }

    private String nextScarNo() {
        String prefix = "SC" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = scarDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private String nextDeductNo() {
        String prefix = "SD" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = deductionDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
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

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
