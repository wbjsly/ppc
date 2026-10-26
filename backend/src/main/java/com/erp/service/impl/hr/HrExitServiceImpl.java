package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.hr.HrChangeLogDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrExitDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrExit;
import com.erp.entity.system.SysUser;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.hr.HrAccountLinkService;
import com.erp.service.hr.HrExitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.util.UUID;

/**
 * 离职管理实现（C-4.7-04 四类交接全齐才放行离场凭证）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrExitServiceImpl implements HrExitService {

    private final HrExitDao exitDao;
    private final HrEmployeeDao empDao;
    private final com.erp.dao.hr.HrLeaveBalanceBatchDao leaveBalanceDao;
    private final HrChangeLogDao logDao;
    private final EamAssetCardDao cardDao;
    private final SysUserDao userDao;
    private final SysParamService sysParamService;
    private final ApprovalEngine approvalEngine;
    private final HrAccountLinkService accountLinkService;
    private final com.erp.service.hr.HrSocialChangeService socialChangeService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String BIZ_EXIT = "HrExit";

    // ================================================================
    // 申请与审批
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit apply(Map<String, Object> payload) {
        HrEmployee e = empDao.selectById(str(payload.get("empId")));
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在");
        }
        if (HrEmployee.ST_RESIGNED.equals(e.getEmpStatus())) {
            throw new ServiceException(422, "员工已离职");
        }
        HrExit existing = exitDao.selectOne(new LambdaQueryWrapper<HrExit>()
                .eq(HrExit::getEmpId, e.getId())
                .in(HrExit::getStatus, HrExit.ST_APPLYING, HrExit.ST_APPROVING, HrExit.ST_HANDOVER)
                .last("LIMIT 1"));
        if (existing != null) {
            throw new ServiceException(422, "已有进行中的离职单：" + existing.getExitNo());
        }
        LocalDate quitDate = date(payload.get("quitDate"));
        if (quitDate == null) {
            throw new ServiceException(422, "拟离职日期必填");
        }
        // 通知期校验（L4 提示留痕不阻断）
        boolean probation = HrEmployee.ST_PROBATION.equals(e.getEmpStatus());
        int noticeDays = intParam(probation ? "HR_EXIT_NOTICE_DAYS_PROBATION" : "HR_EXIT_NOTICE_DAYS_REGULAR",
                probation ? 3 : 30);
        long notified = e.getHireDate() == null ? noticeDays
                : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), quitDate);
        boolean noticeOk = notified >= noticeDays;

        HrExit x = new HrExit();
        x.setId(uuid());
        x.setExitNo(nextNo("EXT", exitDao::selectNosByPrefix));
        x.setEmpId(e.getId());
        x.setEmpName(e.getName());
        x.setEmpNo(e.getEmpNo());
        x.setQuitDate(quitDate);
        x.setReason(str(payload.get("reason")));
        x.setNoticeOk(noticeOk ? "1" : "0");
        // 申请来源（FR-4.7-4-11）：SELF=员工自助端提交 / HR=代录（缺省）
        x.setSource("SELF".equalsIgnoreCase(str(payload.get("source"))) ? "SELF" : "HR");
        x.setStatus(HrExit.ST_APPLYING);
        x.setAssetDone("0");
        x.setAccountDone("0");
        x.setDocDone("0");
        x.setWorkDone("0");
        x.setCreateBy(currentUser());
        exitDao.insert(x);
        log.info("exit apply: {} {} noticeOk={}", x.getExitNo(), e.getName(), noticeOk);
        return x;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit startApproval(String exitId) {
        HrExit x = requireExit(exitId);
        if (!HrExit.ST_APPLYING.equals(x.getStatus())) {
            throw new ServiceException(422, "仅申请态可提交审批");
        }
        var inst = approvalEngine.submit(BIZ_EXIT, x.getId(),
                "离职审批：" + x.getEmpName() + "（" + x.getQuitDate() + "）", "ROLE_HR_MGR",
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_HR", "HR 专员初审")),
                        List.of(ApprovalNodeSpec.sign("ROLE_HR_MGR", "HR 经理终审"))));
        x.setApprId(inst.getId());
        x.setStatus(HrExit.ST_APPROVING);
        touch(x);
        return x;
    }

    /** 审批通过回调 → 进入交接阶段 */
    @Transactional(rollbackFor = Exception.class)
    public void onApproved(String exitId) {
        HrExit x = exitDao.selectById(exitId);
        if (x == null || HrExit.ST_EXITED.equals(x.getStatus())) {
            return;
        }
        x.setStatus(HrExit.ST_HANDOVER);
        touch(x);
        log.info("exit approved → handover: {}", x.getExitNo());
    }

    /** 审批驳回回调 */
    @Transactional(rollbackFor = Exception.class)
    public void onRejected(String exitId) {
        HrExit x = exitDao.selectById(exitId);
        if (x == null) {
            return;
        }
        x.setStatus(HrExit.ST_REJECTED);
        touch(x);
    }

    // ================================================================
    // 交接（C-4.7-04）
    // ================================================================

    @Override
    public Map<String, Object> handoverChecklist(String exitId) {
        HrExit x = requireExit(exitId);
        HrEmployee e = empDao.selectById(x.getEmpId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exitNo", x.getExitNo());
        out.put("status", x.getStatus());
        // ①资产：EAM 卡片按持有人列示
        List<EamAssetCard> cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getHolderEmpNo, e.getEmpNo())
                .eq(EamAssetCard::getDelFlag, "0"));
        out.put("assets", cards);
        out.put("assetDone", "1".equals(x.getAssetDone()));
        // ②账号
        SysUser u = e.getUserId() == null ? null : userDao.selectById(e.getUserId());
        out.put("account", u == null ? null : Map.of("username", u.getUsername(), "status", u.getStatus()));
        out.put("accountDone", "1".equals(x.getAccountDone()));
        // ③④手工登记项
        Map<String, Object> handover = parseJson(x.getHandoverJson());
        out.put("docItems", handover.get("doc"));
        out.put("workItems", handover.get("work"));
        out.put("docDone", "1".equals(x.getDocDone()));
        out.put("workDone", "1".equals(x.getWorkDone()));
        boolean all = "1".equals(x.getAssetDone()) && "1".equals(x.getAccountDone())
                && "1".equals(x.getDocDone()) && "1".equals(x.getWorkDone());
        out.put("canIssueCert", all);
        if (!all) {
            List<String> missing = new ArrayList<>();
            if (!"1".equals(x.getAssetDone())) {
                missing.add("资产归还（待确认 " + cards.size() + " 张）");
            }
            if (!"1".equals(x.getAccountDone())) {
                missing.add("权限移交");
            }
            if (!"1".equals(x.getDocDone())) {
                missing.add("知识文档交接");
            }
            if (!"1".equals(x.getWorkDone())) {
                missing.add("在办工作交接");
            }
            out.put("missing", missing);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit confirmAssetReturn(String exitId, List<String> cardIds) {
        HrExit x = requireExit(exitId);
        HrEmployee e = empDao.selectById(x.getEmpId());
        long owned = cardDao.selectCount(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getHolderEmpNo, e.getEmpNo())
                .eq(EamAssetCard::getDelFlag, "0"));
        if (cardIds == null || cardIds.isEmpty()) {
            // 零资产员工：无须选择卡片，直接完成资产项
            if (owned > 0) {
                throw new ServiceException(422, "名下尚有 " + owned + " 张资产卡片，须逐一确认归还");
            }
            x.setAssetDone("1");
            touch(x);
            return x;
        }
        List<String> returned = new ArrayList<>();
        for (String cardId : cardIds) {
            EamAssetCard card = cardDao.selectById(cardId);
            if (card == null) {
                throw new ServiceException(404, "资产卡片不存在：" + cardId);
            }
            // updateById 忽略 null 字段——显式 set 清空持有人（同红冲重开先例）
            var uw = new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<EamAssetCard>();
            uw.eq("ID", cardId)
                    .set("HOLDER_EMP_NO", null)
                    .set("HOLDER_NAME", null)
                    .set("UPDATE_BY", currentUser())
                    .set("UPDATE_DATE", LocalDateTime.now());
            cardDao.update(null, uw);
            returned.add(card.getCardNo());
        }
        // 若名下已无持有人卡片 → 资产项完成
        long remaining = cardDao.selectCount(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getHolderEmpNo, e.getEmpNo())
                .eq(EamAssetCard::getDelFlag, "0"));
        if (remaining == 0) {
            x.setAssetDone("1");
        }
        appendHandover(x, "assetReturn", List.of(Map.of("cards", returned, "by", currentUser(),
                "at", String.valueOf(LocalDateTime.now()))));
        touch(x);
        log.info("asset return confirmed: {} cards={} remaining={}", x.getExitNo(), returned, remaining);
        return x;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit confirmAccountHandover(String exitId) {
        HrExit x = requireExit(exitId);
        x.setAccountDone("1");
        appendHandover(x, "account", List.of(Map.of("by", currentUser(),
                "at", String.valueOf(LocalDateTime.now()))));
        touch(x);
        return x;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit registerHandoverItems(String exitId, String category, List<Map<String, Object>> items) {
        HrExit x = requireExit(exitId);
        if (!"doc".equals(category) && !"work".equals(category)) {
            throw new ServiceException(422, "交接类别非法（doc/work）");
        }
        for (Map<String, Object> item : items) {
            if (item.get("receiver") == null || String.valueOf(item.get("receiver")).isBlank()) {
                throw new ServiceException(422, "交接项须有接收人确认");
            }
        }
        appendHandover(x, category, items);
        if ("doc".equals(category)) {
            x.setDocDone("1");
        } else {
            x.setWorkDone("1");
        }
        touch(x);
        return x;
    }

    // ================================================================
    // 结算与离场凭证
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit settle(String exitId, Map<String, Object> payload) {
        HrExit x = requireExit(exitId);
        if (!HrExit.ST_HANDOVER.equals(x.getStatus()) && !HrExit.ST_SETTLED.equals(x.getStatus())) {
            throw new ServiceException(422, "交接阶段方可结算登记");
        }
        // social-insurance-ops 触点②：进入结算 → 自动开社保减员单（DRAFT，防重；失败仅告警不阻断结算）
        try {
            socialChangeService.autoOpen(x.getEmpId(), com.erp.entity.hr.HrSocialChange.TYPE_REMOVE,
                    com.erp.entity.hr.HrSocialChange.SRC_EXIT, x.getId(), null,
                    x.getQuitDate() == null ? LocalDate.now() : x.getQuitDate());
        } catch (Exception ex) {
            log.warn("social REMOVE auto-open failed for {}: {}", x.getExitNo(), ex.getMessage());
        }
        x.setSettleWage(dec(payload.get("settleWage")));
        // 缝合 add-attendance-management：缺省时从余额池自动折算（调休 2x 小时工资 / 年假日工资）
        HrEmployee settleEmp = empDao.selectById(x.getEmpId());
        java.math.BigDecimal hourlyBase = null;
        java.math.BigDecimal dailyBase = null;
        if (settleEmp != null && settleEmp.getBaseSalary() != null
                && settleEmp.getBaseSalary().signum() > 0) {
            hourlyBase = settleEmp.getBaseSalary()
                    .divide(new java.math.BigDecimal("21.75"), 4, java.math.RoundingMode.HALF_UP)
                    .divide(new java.math.BigDecimal("8"), 4, java.math.RoundingMode.HALF_UP);
            dailyBase = settleEmp.getBaseSalary()
                    .divide(new java.math.BigDecimal("21.75"), 2, java.math.RoundingMode.HALF_UP);
        }
        java.math.BigDecimal compAmount = dec(payload.get("settleComp"));
        if (compAmount == null && hourlyBase != null) {
            java.math.BigDecimal compHours = unusedLeaveHours(x.getEmpId(), "COMP_TIME");
            java.math.BigDecimal rate = dec(sysParamService.getValue("COMP_TIME_RESIGN_RATE"));
            if (rate == null) {
                rate = new java.math.BigDecimal("2");
            }
            compAmount = hourlyBase.multiply(rate).multiply(compHours)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            if (compHours.signum() > 0) {
                x.setSettleNote((x.getSettleNote() == null ? "" : x.getSettleNote() + "；")
                        + "未休调休 " + compHours.stripTrailingZeros().toPlainString()
                        + " 小时按 " + rate.stripTrailingZeros().toPlainString()
                        + "x 折算（自动留桩）");
            }
        }
        java.math.BigDecimal annualAmount = dec(payload.get("settleAnnual"));
        if (annualAmount == null && dailyBase != null) {
            java.math.BigDecimal annualDays = unusedLeaveHours(x.getEmpId(), "ANNUAL");
            annualAmount = dailyBase.multiply(annualDays)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            if (annualDays.signum() > 0) {
                x.setSettleNote((x.getSettleNote() == null ? "" : x.getSettleNote() + "；")
                        + "未休年假 " + annualDays.stripTrailingZeros().toPlainString()
                        + " 天按日工资折算（自动留桩）");
            }
        }
        x.setSettleAnnual(annualAmount);
        x.setSettleComp(compAmount);
        x.setSettleNoncomp(dec(payload.get("settleNoncomp")));
        // 经济补偿金（FR-4.7-4-14）：N 法定口径自动试算（年限折算 × 月薪）预填，HR 可覆盖；
        // 系统给基线不做法务裁决（主动/协商/违纪辞退情形由 HR 判断）
        // 注意：本类 dec() 对空值返回 ZERO——显式传值才算覆盖，未传才走自动试算
        Object rawEcomp = payload.get("settleEcomp");
        java.math.BigDecimal ecompAmount = (rawEcomp == null || String.valueOf(rawEcomp).isBlank())
                ? null : dec(rawEcomp);
        if (ecompAmount == null && settleEmp != null && settleEmp.getBaseSalary() != null
                && settleEmp.getHireDate() != null) {
            long months = java.time.temporal.ChronoUnit.MONTHS.between(settleEmp.getHireDate(),
                    x.getQuitDate() == null ? LocalDate.now() : x.getQuitDate());
            if (months > 0) {
                long years = months / 12;
                long rem = months % 12;
                java.math.BigDecimal n = java.math.BigDecimal.valueOf(years)
                        .add(rem >= 6 ? java.math.BigDecimal.ONE
                                : rem >= 1 ? new java.math.BigDecimal("0.5") : java.math.BigDecimal.ZERO);
                ecompAmount = settleEmp.getBaseSalary().multiply(n)
                        .setScale(2, java.math.RoundingMode.HALF_UP);
                x.setSettleNote((x.getSettleNote() == null ? "" : x.getSettleNote() + "；")
                        + "经济补偿金自动试算 " + n.stripTrailingZeros().toPlainString()
                        + " × 月薪（N 口径基线，可修改）");
            }
        }
        x.setSettleEcomp(ecompAmount);
        // 合并自动留桩备注与人工备注（原实现人工备注会覆盖自动留痕，并集保双方）
        String userNote = str(payload.get("note"));
        x.setSettleNote(userNote == null ? x.getSettleNote()
                : (x.getSettleNote() == null ? userNote : x.getSettleNote() + "；" + userNote));
        x.setSettledBy(currentUser());
        x.setSettledAt(LocalDateTime.now());
        x.setReduceDate(date(payload.get("reduceDate")));
        x.setReduceNote(str(payload.get("reduceNote")));
        x.setStatus(HrExit.ST_SETTLED);
        touch(x);
        log.info("exit settled: {} wage={} comp={}", x.getExitNo(), x.getSettleWage(), x.getSettleComp());
        return x;
    }

    /** 未休余额（ACTIVE 批次 remain 合计，按类型）。 */
    private java.math.BigDecimal unusedLeaveHours(String empId, String typeCode) {
        return leaveBalanceDao.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.hr.HrLeaveBalanceBatch>()
                .eq(com.erp.entity.hr.HrLeaveBalanceBatch::getEmpId, empId)
                .eq(com.erp.entity.hr.HrLeaveBalanceBatch::getTypeCode, typeCode)
                .eq(com.erp.entity.hr.HrLeaveBalanceBatch::getStatus, "ACTIVE")
                .eq(com.erp.entity.hr.HrLeaveBalanceBatch::getDelFlag, "0"))
                .stream().map(com.erp.entity.hr.HrLeaveBalanceBatch::getRemain)
                .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit issueExitCert(String exitId) {
        HrExit x = requireExit(exitId);
        // C-4.7-04 四类交接未齐 → L1 硬阻断
        List<String> missing = new ArrayList<>();
        if (!"1".equals(x.getAssetDone())) {
            missing.add("资产归还");
        }
        if (!"1".equals(x.getAccountDone())) {
            missing.add("权限移交");
        }
        if (!"1".equals(x.getDocDone())) {
            missing.add("知识文档交接");
        }
        if (!"1".equals(x.getWorkDone())) {
            missing.add("在办工作交接");
        }
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "C-4.7-04 交接未全部完成，不可生成离场凭证，未完成：" + missing);
        }
        if (!HrExit.ST_SETTLED.equals(x.getStatus())) {
            throw new ServiceException(422, "薪酬结算与社保减员须先登记");
        }
        // 社保减员确认（FR-4.7-4-15 升级）：存在 DONE 减员单（social-insurance-ops）或手工豁免留痕
        if (!"1".equals(x.getReduceWaived()) && !socialChangeService.hasDoneChange(x.getEmpId(),
                com.erp.entity.hr.HrSocialChange.TYPE_REMOVE, null)) {
            throw new ServiceException(422,
                    "社保减员未完成：请先在社保增减员页完成减员申报，或手工豁免留痕（记录豁免人与原因）");
        }
        // 离场凭证
        x.setExitCertNo(nextCertNo());
        x.setExitCertAt(LocalDateTime.now());
        x.setStatus(HrExit.ST_EXITED);
        // 主档置已离职 + 禁用账号（D3 触点③，失败降级不回滚）
        HrEmployee e = empDao.selectForUpdate(x.getEmpId());
        if (e != null) {
            String old = e.getEmpStatus();
            e.setEmpStatus(HrEmployee.ST_RESIGNED);
            e.setResignedDate(x.getQuitDate() == null ? LocalDate.now() : x.getQuitDate());
            e.setUpdateBy("exit");
            e.setUpdateDate(LocalDateTime.now());
            empDao.updateById(e);
            logChange(e, "员工状态", old, HrEmployee.ST_RESIGNED, "离场凭证 " + x.getExitCertNo());
            try {
                accountLinkService.disableAccount(e.getId());
            } catch (Exception ex) {
                log.warn("disable account failed for {}: {}", e.getEmpNo(), ex.getMessage());
            }
        }
        touch(x);
        log.info("exit cert issued: {} {}", x.getExitCertNo(), x.getEmpName());
        return x;
    }

    @Override
    public List<HrExit> list(String status) {
        LambdaQueryWrapper<HrExit> qw = new LambdaQueryWrapper<HrExit>()
                .eq(HrExit::getDelFlag, "0").orderByDesc(HrExit::getCreateDate);
        if (status != null && !status.isBlank()) {
            qw.eq(HrExit::getStatus, status);
        }
        return exitDao.selectList(qw);
    }

    @Override
    public Map<String, Object> detail(String exitId) {
        HrExit x = requireExit(exitId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exit", x);
        out.put("employee", empDao.selectById(x.getEmpId()));
        out.put("checklist", handoverChecklist(exitId));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit retainNote(String exitId, String note) {
        HrExit x = requireExit(exitId);
        if (note == null || note.isBlank()) {
            throw new ServiceException(422, "挽留意见内容必填");
        }
        String stamped = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                + " " + currentUser() + "] " + note;
        x.setRetainNote(x.getRetainNote() == null ? stamped : x.getRetainNote() + "\n" + stamped);
        touch(x);
        return x;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrExit waiveReduce(String exitId, String reason) {
        HrExit x = requireExit(exitId);
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "豁免原因必填");
        }
        if (HrExit.ST_EXITED.equals(x.getStatus()) || HrExit.ST_REJECTED.equals(x.getStatus())) {
            throw new ServiceException(422, "已离场/已驳回单据不可豁免");
        }
        x.setReduceWaived("1");
        x.setReduceWaiveNote("[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                + " " + currentUser() + "] " + reason);
        touch(x);
        log.info("exit reduce waived: {} by {}", x.getExitNo(), currentUser());
        return x;
    }

    @Override
    public List<HrExit> listByEmp(String empId) {
        return exitDao.selectList(new LambdaQueryWrapper<HrExit>()
                .eq(HrExit::getDelFlag, "0")
                .eq(HrExit::getEmpId, empId)
                .orderByDesc(HrExit::getCreateDate));
    }

    // ================================================================
    // 内部
    // ================================================================

    @SuppressWarnings("unchecked")
    private void appendHandover(HrExit x, String key, Object value) {
        Map<String, Object> handover = parseJson(x.getHandoverJson());
        Object existing = handover.get(key);
        if (existing instanceof List) {
            ((List<Object>) existing).addAll((List<Object>) value);
        } else {
            handover.put(key, value);
        }
        try {
            x.setHandoverJson(MAPPER.writeValueAsString(handover));
        } catch (Exception e) {
            throw new ServiceException(500, "交接明细序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(jsonStr,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void logChange(HrEmployee e, String field, String oldV, String newV, String reason) {
        HrChangeLog l = new HrChangeLog();
        l.setId(uuid());
        l.setEmpId(e.getId());
        l.setFieldName(field);
        l.setOldValue(oldV);
        l.setNewValue(newV);
        l.setReason(reason);
        l.setChangeBy(currentUser());
        l.setChangeAt(LocalDateTime.now());
        logDao.insert(l);
    }

    private String nextCertNo() {
        List<String> existing = exitDao.selectCertNosByPrefix("EXC%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.replaceAll("\\D", "")));
            } catch (Exception ignored) {
                // skip
            }
        }
        return "EXC" + String.format("%04d", max + 1);
    }

    private String nextNo(String prefix, java.util.function.Function<String, List<String>> nosFn) {
        List<String> existing = nosFn.apply(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                String tail = no.length() > prefix.length() ? no.substring(prefix.length()) : "";
                max = Math.max(max, Integer.parseInt(tail.replaceAll("\\D", "")));
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + "-" + String.format("%04d", max + 1);
    }

    private void touch(HrExit x) {
        x.setUpdateBy(currentUser());
        x.setUpdateDate(LocalDateTime.now());
        exitDao.updateById(x);
    }

    private HrExit requireExit(String id) {
        HrExit x = exitDao.selectById(id);
        if (x == null) {
            throw new ServiceException(404, "离职单不存在：" + id);
        }
        return x;
    }

    private int intParam(String key, int dflt) {
        String v = sysParamService.getValue(key);
        try {
            return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "金额格式非法：" + o);
        }
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
