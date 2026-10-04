package com.erp.service.impl.proc;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.proc.EmergencyChannelDao;
import com.erp.dao.proc.EmergencyRequestDao;
import com.erp.dao.proc.ProcRequisitionDao;
import com.erp.entity.proc.EmergencyChannel;
import com.erp.entity.proc.EmergencyRequest;
import com.erp.entity.proc.ProcRequisition;
import com.erp.procurement.EmergencyStateMachine;
import com.erp.procurement.RequisitionStateMachine;
import com.erp.service.proc.EmergencyService;
import com.erp.service.proc.EmergencySupport;
import com.erp.util.SecurityUtils;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 紧急采购实现（design add-emergency-procurement D3/D4/D5）。
 * 懒 sweep 于查询前执行：特批超时双标记（独立 if）+ 逾期例外联动通道阻断（L1321）。
 */
@Slf4j
@Service
public class EmergencyServiceImpl implements EmergencyService {

    private final EmergencyRequestDao eaDao;
    private final EmergencyChannelDao channelDao;
    private final ProcRequisitionDao prDao;
    private final EmergencySupport support;

    /** 补齐时限（EMERGENCY_FILL_DAYS 参数，L291 默认 5 个工作日） */
    @Value("${app.proc.emergency-fill-days:5}")
    private int emergencyFillDays;

    public EmergencyServiceImpl(EmergencyRequestDao eaDao,
                                EmergencyChannelDao channelDao,
                                ProcRequisitionDao prDao,
                                EmergencySupport support) {
        this.eaDao = eaDao;
        this.channelDao = channelDao;
        this.prDao = prDao;
        this.support = support;
    }

    // ---------- 分页（sweep 先行） ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String status, String keyword) {
        sweep();
        LambdaQueryWrapper<EmergencyRequest> qw = new LambdaQueryWrapper<EmergencyRequest>()
                .eq(isNotBlank(status), EmergencyRequest::getStatus, status)
                .and(isNotBlank(keyword), w -> w
                        .like(EmergencyRequest::getEaNo, isNotBlank(keyword) ? keyword.trim() : null)
                        .or().like(EmergencyRequest::getApplicant,
                                isNotBlank(keyword) ? keyword.trim() : null))
                .orderByDesc(EmergencyRequest::getCreateDate);
        Page<EmergencyRequest> raw = eaDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        Map<String, String> prNos = prNosOf(raw.getRecords());
        List<Map<String, Object>> records = new ArrayList<>();
        for (EmergencyRequest ea : raw.getRecords()) {
            records.add(toRow(ea, prNos.get(ea.getPrId())));
        }
        result.setRecords(records);
        return result;
    }

    @Override
    public Map<String, Object> detail(String id) {
        sweep();
        EmergencyRequest ea = support.requireEa(id);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ea", toRow(ea, prNo(ea.getPrId())));
        ProcRequisition pr = prDao.selectById(ea.getPrId());
        if (pr != null) {
            Map<String, Object> prMap = new LinkedHashMap<>();
            prMap.put("prNo", pr.getPrNo());
            prMap.put("status", pr.getStatus());
            prMap.put("approvalAmount", pr.getApprovalAmount());
            prMap.put("sourceType", pr.getSourceType());
            result.put("pr", prMap);
        }
        result.put("fillDays", emergencyFillDays);
        return result;
    }

    @Override
    public Map<String, Object> prCandidates(String keyword) {
        LambdaQueryWrapper<ProcRequisition> qw = new LambdaQueryWrapper<ProcRequisition>()
                .in(ProcRequisition::getStatus,
                        RequisitionStateMachine.APPROVED, RequisitionStateMachine.PENDING_RFQ)
                .eq(ProcRequisition::getDelFlag, "0")
                .like(isNotBlank(keyword), ProcRequisition::getPrNo,
                        isNotBlank(keyword) ? keyword.trim() : null)
                .orderByDesc(ProcRequisition::getCreateDate);
        List<ProcRequisition> prs = prDao.selectList(qw);
        // 排除已有未关闭 EA 的 PR（防重前置提示）
        Set<String> busyPrIds = new LinkedHashSet<>();
        for (EmergencyRequest ea : eaDao.selectList(new LambdaQueryWrapper<EmergencyRequest>()
                .notIn(EmergencyRequest::getStatus,
                        EmergencyStateMachine.REJECTED, EmergencyStateMachine.CLOSED))) {
            busyPrIds.add(ea.getPrId());
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ProcRequisition pr : prs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prId", pr.getId());
            m.put("prNo", pr.getPrNo());
            m.put("status", pr.getStatus());
            m.put("approvalAmount", pr.getApprovalAmount());
            m.put("hasOpenEa", busyPrIds.contains(pr.getId()));
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("candidates", items);
        result.put("total", items.size());
        if (items.isEmpty()) {
            result.put("hint", "暂无可发起的 PR（须为 已批准/待询价 状态）");
        }
        return result;
    }

    // ---------- 发起（D4 校验顺序：通道 → PR → 防重） ----------

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String applicant = SecurityUtils.getCurrentUserId();
        // ① 通道阻断（L1321，先校验不产生单据）
        EmergencyChannel blocked = channelDao.selectOne(new LambdaQueryWrapper<EmergencyChannel>()
                .eq(EmergencyChannel::getAccount, applicant)
                .eq(EmergencyChannel::getStatus, "BLOCKED"));
        if (blocked != null) {
            throw new ServiceException(422, "紧急采购通道已阻断（L1321）："
                    + blocked.getBlockReason() + "；恢复路径：补齐比价资料并经采购总监复核恢复");
        }
        // ② PR 状态
        String prId = str(payload.get("prId"));
        if (!isNotBlank(prId)) {
            throw new ServiceException(422, "须选择已批准/待询价的 PR");
        }
        ProcRequisition pr = prDao.selectById(prId);
        if (pr == null) {
            throw new ServiceException(404, "PR 不存在：" + prId);
        }
        if (!RequisitionStateMachine.APPROVED.equals(pr.getStatus())
                && !RequisitionStateMachine.PENDING_RFQ.equals(pr.getStatus())) {
            throw new ServiceException(422, "仅可对 已批准/待询价 的 PR 发起紧急申请，当前 PR 状态 "
                    + pr.getStatus());
        }
        // ③ 同 PR 未关闭 EA 防重
        Long open = eaDao.selectCount(new LambdaQueryWrapper<EmergencyRequest>()
                .eq(EmergencyRequest::getPrId, prId)
                .notIn(EmergencyRequest::getStatus,
                        EmergencyStateMachine.REJECTED, EmergencyStateMachine.CLOSED));
        if (open != null && open > 0) {
            throw new ServiceException(422, "该 PR 已存在未关闭的紧急申请，请勿重复发起");
        }
        // ④ 字段
        String reasonType = str(payload.get("reasonType"));
        if (!"STOP_LINE".equals(reasonType) && !"CUSTOMER_RUSH".equals(reasonType)
                && !"OTHER".equals(reasonType)) {
            throw new ServiceException(422, "紧急事由须为 产线停线/客户紧急订单/其他 之一");
        }
        String reasonDesc = str(payload.get("reasonDesc"));
        if (reasonDesc == null || reasonDesc.trim().length() < 2) {
            throw new ServiceException(422, "事由说明必填（至少 2 字）");
        }
        EmergencyRequest ea = new EmergencyRequest();
        ea.setEaNo(support.nextEaNo());
        ea.setPrId(prId);
        ea.setReasonType(reasonType);
        ea.setReasonDesc(reasonDesc.trim());
        String expect = str(payload.get("expectArriveDate"));
        if (isNotBlank(expect)) {
            try {
                ea.setExpectArriveDate(LocalDate.parse(expect.trim()));
            } catch (Exception e) {
                throw new ServiceException(422, "期望到货日格式须为 yyyy-MM-dd");
            }
        }
        ea.setStatus(EmergencyStateMachine.PENDING_SPECIAL_APPROVAL);
        ea.setApplicant(applicant);
        ea.setRemindFlag("0");
        ea.setEscalateFlag("0");
        ea.setExceptionFlag("0");
        eaDao.insert(ea);
        support.publishHead(ea, "PROC.EMERGENCY.CREATED",
                "紧急申请 " + ea.getEaNo() + " 关联 " + pr.getPrNo() + " 事由 " + reasonType);
        log.info("EA created {} pr={} type={} applicant={}", ea.getEaNo(), pr.getPrNo(),
                reasonType, applicant);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ea", ea);
        result.put("prNo", pr.getPrNo());
        return result;
    }

    // ---------- 特批 ----------

    @Override
    @Transactional
    public Map<String, Object> approve(String id, String reason) {
        EmergencyRequest ea = support.requireEa(id);
        if (!EmergencyStateMachine.PENDING_SPECIAL_APPROVAL.equals(ea.getStatus())) {
            throw new ServiceException(422, "状态防重：仅「特批中」可通过，当前 " + ea.getStatus());
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "特批原因必填（至少 2 字）");
        }
        LocalDate today = LocalDate.now();
        ea.setSpecialApproveBy(SecurityUtils.getCurrentUserId());
        ea.setSpecialApproveDate(LocalDateTime.now());
        ea.setSpecialReason(reason.trim());
        ea.setClearanceDueDate(RequisitionStateMachine.plusBusinessDays(today, emergencyFillDays));
        support.persist(ea);
        support.transition(ea, EmergencyStateMachine.APPROVED_EMERGENCY, "采购总监特批放行");
        support.publishHead(ea, "PROC.EMERGENCY.SPECIAL_APPROVED",
                "特批放行：截止 " + ea.getClearanceDueDate() + "，原因 " + reason.trim());
        log.info("EA {} special-approved due={} reason={}", ea.getEaNo(),
                ea.getClearanceDueDate(), reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ea", ea);
        result.put("clearanceDueDate", ea.getClearanceDueDate());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> reject(String id, String reason) {
        EmergencyRequest ea = support.requireEa(id);
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "驳回原因必填（至少 2 字）");
        }
        ea.setRejectReason(reason.trim());
        support.persist(ea);
        support.transition(ea, EmergencyStateMachine.REJECTED, "特批驳回：" + reason.trim());
        support.publishHead(ea, "PROC.EMERGENCY.REJECTED", "特批驳回：" + reason.trim());
        return Map.of("ea", ea);
    }

    // ---------- 比价补齐（先迁 FILLING 再 COMPLETED，事件一次） ----------

    @Override
    @Transactional
    public Map<String, Object> fill(String id, Map<String, Object> payload) {
        EmergencyRequest ea = support.requireEa(id);
        String st = ea.getStatus();
        if (!EmergencyStateMachine.APPROVED_EMERGENCY.equals(st)
                && !EmergencyStateMachine.FILLING.equals(st)) {
            throw new ServiceException(422, "当前状态 " + st + " 不可登记补齐"
                    + (EmergencyStateMachine.OVERDUE_EXCEPTION.equals(st)
                    ? "（已逾期例外，须先经总监复核恢复通道）" : ""));
        }
        Object qc = payload.get("quoteCount");
        int quotes;
        try {
            quotes = Integer.parseInt(String.valueOf(qc).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "实际报价家数必填（整数 ≥1）");
        }
        if (quotes < 1) {
            throw new ServiceException(422, "实际报价家数至少 1 家（紧急采购最低报价口径）");
        }
        String docNo = str(payload.get("compareDocNo"));
        if (!isNotBlank(docNo)) {
            throw new ServiceException(422, "比价资料编号必填");
        }
        String note = str(payload.get("fillNote"));
        if (note == null || note.trim().length() < 2) {
            throw new ServiceException(422, "补充说明必填（至少 2 字）；附件上传为桩（MinIO 未落地）");
        }
        if (EmergencyStateMachine.APPROVED_EMERGENCY.equals(st)) {
            support.transition(ea, EmergencyStateMachine.FILLING, "进入比价补齐");
        }
        ea.setQuoteCount(quotes);
        ea.setCompareDocNo(docNo.trim());
        ea.setFillNote(note.trim());
        ea.setFillDate(LocalDateTime.now());
        ea.setExceptionFlag("0");
        support.persist(ea);
        support.transition(ea, EmergencyStateMachine.COMPLETED, "比价资料补齐完成");
        support.publishHead(ea, "PROC.EMERGENCY.FILLED",
                "补齐完成：报价 " + quotes + " 家，资料 " + docNo.trim());
        log.info("EA {} filled quotes={} doc={}", ea.getEaNo(), quotes, docNo);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ea", ea);
        return result;
    }

    @Override
    @Transactional
    public void close(String id, String reason) {
        EmergencyRequest ea = support.requireEa(id);
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "关闭原因必填（至少 2 字）");
        }
        ea.setCloseReason(reason.trim());
        support.persist(ea);
        support.transition(ea, EmergencyStateMachine.CLOSED, reason.trim());
        support.publishHead(ea, "PROC.EMERGENCY.CLOSED", "人工关闭：" + reason.trim());
    }

    // ---------- 通道台账 ----------

    @Override
    public Map<String, Object> channels(long current, long size, String status, String keyword) {
        // 账号全集 = EA 申请人 ∪ 台账行（无行 = 已开通默认）
        Set<String> accounts = new LinkedHashSet<>();
        for (EmergencyRequest ea : eaDao.selectList(
                new LambdaQueryWrapper<EmergencyRequest>().select(EmergencyRequest::getApplicant))) {
            accounts.add(ea.getApplicant());
        }
        Map<String, EmergencyChannel> rows = new LinkedHashMap<>();
        for (EmergencyChannel c : channelDao.selectList(null)) {
            accounts.add(c.getAccount());
            rows.put(c.getAccount(), c);
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (String account : accounts) {
            if (isNotBlank(keyword) && !account.contains(keyword.trim())) {
                continue;
            }
            EmergencyChannel c = rows.get(account);
            boolean blockedFlag = c != null && "BLOCKED".equals(c.getStatus());
            if (isNotBlank(status) && !status.equals(blockedFlag ? "BLOCKED" : "OPEN")) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("account", account);
            m.put("status", blockedFlag ? "BLOCKED" : "OPEN");
            m.put("implicit", c == null);
            m.put("blockReason", c == null ? null : c.getBlockReason());
            m.put("blockDate", c == null ? null : c.getBlockDate());
            m.put("blockEaNo", c == null ? null : c.getBlockEaNo());
            m.put("reviewBy", c == null ? null : c.getReviewBy());
            m.put("reviewDate", c == null ? null : c.getReviewDate());
            m.put("reviewNote", c == null ? null : c.getReviewNote());
            m.put("overdueCount", eaDao.selectCount(new LambdaQueryWrapper<EmergencyRequest>()
                    .eq(EmergencyRequest::getApplicant, account)
                    .eq(EmergencyRequest::getStatus, EmergencyStateMachine.OVERDUE_EXCEPTION)));
            all.add(m);
        }
        all.sort(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("account"))));
        // 手动分页（账号集为内存小集合）
        int from = (int) Math.max(0, (current - 1) * size);
        int to = (int) Math.min(all.size(), from + size);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", from >= all.size() ? new ArrayList<>() : all.subList(from, to));
        result.put("total", all.size());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> restoreChannel(String accountId, String note) {
        if (!isNotBlank(accountId)) {
            throw new ServiceException(422, "账号必填");
        }
        if (!isNotBlank(note) || note.trim().length() < 2) {
            throw new ServiceException(422, "复核说明必填（至少 2 字）");
        }
        EmergencyChannel c = channelDao.selectOne(new LambdaQueryWrapper<EmergencyChannel>()
                .eq(EmergencyChannel::getAccount, accountId.trim()));
        if (c == null || !"BLOCKED".equals(c.getStatus())) {
            throw new ServiceException(422, "该账号当前已是开通状态，无需恢复");
        }
        c.setStatus("OPEN");
        c.setReviewBy(SecurityUtils.getCurrentUserId());
        c.setReviewDate(LocalDateTime.now());
        c.setReviewNote(note.trim());
        channelDao.updateById(c);
        // 关闭该账号全部逾期例外（L1321 恢复路径）
        List<EmergencyRequest> overdue = eaDao.selectList(
                new LambdaQueryWrapper<EmergencyRequest>()
                        .eq(EmergencyRequest::getApplicant, accountId.trim())
                        .eq(EmergencyRequest::getStatus, EmergencyStateMachine.OVERDUE_EXCEPTION));
        for (EmergencyRequest ea : overdue) {
            ea.setCloseReason("总监复核恢复，通道解除（" + note.trim() + "）");
            support.persist(ea);
            support.transition(ea, EmergencyStateMachine.CLOSED, "复核恢复关例外");
            support.publishHead(ea, "PROC.EMERGENCY.CLOSED",
                    "复核恢复关闭例外：" + note.trim());
        }
        log.info("channel restored account={} by={} note={} closedOverdue={}",
                accountId, c.getReviewBy(), note, overdue.size());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("channel", c);
        result.put("closedOverdue", overdue.size());
        return result;
    }

    // ---------- RFQ/PO 放行桩 ----------

    @Override
    public Map<String, Object> clearance(String prNo) {
        if (!isNotBlank(prNo)) {
            throw new ServiceException(422, "prNo 必填");
        }
        ProcRequisition pr = prDao.selectOne(new LambdaQueryWrapper<ProcRequisition>()
                .eq(ProcRequisition::getPrNo, prNo.trim()));
        if (pr == null) {
            throw new ServiceException(404, "PR 不存在：" + prNo);
        }
        EmergencyRequest ea = eaDao.selectOne(new LambdaQueryWrapper<EmergencyRequest>()
                .eq(EmergencyRequest::getPrId, pr.getId())
                .orderByDesc(EmergencyRequest::getCreateDate)
                .last("LIMIT 1"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("prNo", pr.getPrNo());
        if (ea == null) {
            result.put("emergency", false);
            result.put("hint", "该 PR 无紧急放行记录");
            return result;
        }
        boolean cleared = EmergencyStateMachine.APPROVED_EMERGENCY.equals(ea.getStatus())
                || EmergencyStateMachine.FILLING.equals(ea.getStatus())
                || EmergencyStateMachine.COMPLETED.equals(ea.getStatus());
        result.put("emergency", cleared);
        result.put("eaNo", ea.getEaNo());
        result.put("status", ea.getStatus());
        result.put("specialApproveBy", ea.getSpecialApproveBy());
        result.put("specialApproveDate", ea.getSpecialApproveDate());
        result.put("clearanceDueDate", ea.getClearanceDueDate());
        result.put("filled", EmergencyStateMachine.COMPLETED.equals(ea.getStatus()));
        result.put("exceptionFlag", ea.getExceptionFlag());
        if (!cleared) {
            result.put("hint", "特批未通过（" + ea.getStatus() + "），不构成放行");
        }
        return result;
    }

    // ---------- 懒 sweep（D5：特批超时双标记 + 逾期例外联动） ----------

    private void sweep() {
        LocalDateTime now = LocalDateTime.now();
        // ① 特批超时：独立 if（≥1 提醒、≥3 升级，防 else-if 互斥坑）
        for (EmergencyRequest ea : eaDao.selectList(new LambdaQueryWrapper<EmergencyRequest>()
                .eq(EmergencyRequest::getStatus, EmergencyStateMachine.PENDING_SPECIAL_APPROVAL)
                .eq(EmergencyRequest::getRemindFlag, "0"))) {
            long wd = RequisitionStateMachine.businessDaysBetween(ea.getCreateDate().toLocalDate(), now);
            boolean changed = false;
            if (wd >= 1) {
                ea.setRemindFlag("1");
                changed = true;
                log.info("[TODO-NOTIFY] EA {} 特批超1工作日提醒（桩）", ea.getEaNo());
            }
            if (wd >= 3 && !"1".equals(ea.getEscalateFlag())) {
                ea.setEscalateFlag("1");
                changed = true;
                log.info("[TODO-NOTIFY] EA {} 特批超3工作日升级（桩）", ea.getEaNo());
            }
            if (changed) {
                ea.setScanDate(now);
                support.persist(ea);
            }
        }
        // ② 逾期：截止日已含 fillDays，过期 ≥1 工作日即例外（design D5 修正）
        for (EmergencyRequest ea : eaDao.selectList(new LambdaQueryWrapper<EmergencyRequest>()
                .in(EmergencyRequest::getStatus,
                        EmergencyStateMachine.APPROVED_EMERGENCY, EmergencyStateMachine.FILLING)
                .isNotNull(EmergencyRequest::getClearanceDueDate))) {
            long overdueWd = RequisitionStateMachine.businessDaysBetween(
                    ea.getClearanceDueDate(), now);
            if (overdueWd >= 1) {
                ea.setExceptionFlag("1");
                support.persist(ea);
                support.transition(ea, EmergencyStateMachine.OVERDUE_EXCEPTION,
                        "超补齐截止日 " + ea.getClearanceDueDate());
                support.publishHead(ea, "PROC.EMERGENCY.OVERDUE",
                        "逾期例外：截止 " + ea.getClearanceDueDate() + " 未补齐");
                blockChannel(ea);
                log.info("[TODO-COMPLIANCE] EA {} 逾期列入合规例外清单（桩），申请人 {} 通道已阻断",
                        ea.getEaNo(), ea.getApplicant());
            }
        }
    }

    /** L1321：逾期 → 申请人通道 BLOCKED（无行 insert，有行覆盖状态并记因） */
    private void blockChannel(EmergencyRequest ea) {
        EmergencyChannel c = channelDao.selectOne(new LambdaQueryWrapper<EmergencyChannel>()
                .eq(EmergencyChannel::getAccount, ea.getApplicant()));
        if (c == null) {
            c = new EmergencyChannel();
            c.setAccount(ea.getApplicant());
            c.setStatus("BLOCKED");
            c.setBlockReason("EA " + ea.getEaNo() + " 超补齐截止日未补齐比价资料（L1321）");
            c.setBlockDate(LocalDateTime.now());
            c.setBlockEaNo(ea.getEaNo());
            channelDao.insert(c);
        } else if (!"BLOCKED".equals(c.getStatus())) {
            c.setStatus("BLOCKED");
            c.setBlockReason("EA " + ea.getEaNo() + " 超补齐截止日未补齐比价资料（L1321）");
            c.setBlockDate(LocalDateTime.now());
            c.setBlockEaNo(ea.getEaNo());
            channelDao.updateById(c);
        }
    }

    // ---------- 私有 ----------

    private Map<String, Object> toRow(EmergencyRequest ea, String prNo) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", ea.getId());
        row.put("eaNo", ea.getEaNo());
        row.put("prId", ea.getPrId());
        row.put("prNo", prNo);
        row.put("reasonType", ea.getReasonType());
        row.put("reasonDesc", ea.getReasonDesc());
        row.put("expectArriveDate", ea.getExpectArriveDate());
        row.put("status", ea.getStatus());
        row.put("applicant", ea.getApplicant());
        row.put("specialApproveBy", ea.getSpecialApproveBy());
        row.put("specialApproveDate", ea.getSpecialApproveDate());
        row.put("specialReason", ea.getSpecialReason());
        row.put("rejectReason", ea.getRejectReason());
        row.put("clearanceDueDate", ea.getClearanceDueDate());
        row.put("quoteCount", ea.getQuoteCount());
        row.put("compareDocNo", ea.getCompareDocNo());
        row.put("fillDate", ea.getFillDate());
        row.put("remindFlag", ea.getRemindFlag());
        row.put("escalateFlag", ea.getEscalateFlag());
        row.put("exceptionFlag", ea.getExceptionFlag());
        row.put("closeReason", ea.getCloseReason());
        row.put("createDate", ea.getCreateDate());
        // 逾期天数（仅已放行未完成）
        row.put("overdueDays", overdueDays(ea));
        return row;
    }

    private Long overdueDays(EmergencyRequest ea) {
        if (ea.getClearanceDueDate() == null
                || EmergencyStateMachine.COMPLETED.equals(ea.getStatus())
                || EmergencyStateMachine.CLOSED.equals(ea.getStatus())) {
            return null;
        }
        long wd = RequisitionStateMachine.businessDaysBetween(ea.getClearanceDueDate(),
                LocalDateTime.now());
        return wd >= 1 ? wd : null;
    }

    private Map<String, String> prNosOf(List<EmergencyRequest> eas) {
        Map<String, String> out = new LinkedHashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        for (EmergencyRequest ea : eas) {
            ids.add(ea.getPrId());
        }
        if (ids.isEmpty()) {
            return out;
        }
        for (ProcRequisition pr : prDao.selectBatchIds(ids)) {
            out.put(pr.getId(), pr.getPrNo());
        }
        return out;
    }

    private String prNo(String prId) {
        ProcRequisition pr = prDao.selectById(prId);
        return pr == null ? null : pr.getPrNo();
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
