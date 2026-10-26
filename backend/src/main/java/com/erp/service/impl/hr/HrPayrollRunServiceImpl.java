package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrIitCumulativeDao;
import com.erp.dao.hr.HrPayAdjustmentDao;
import com.erp.dao.hr.HrPayCorrectionDao;
import com.erp.dao.hr.HrPayrollRunDao;
import com.erp.dao.hr.HrPayrollRunItemDao;
import com.erp.dao.hr.HrPayslipDao;
import com.erp.dao.hr.HrPerfResultDao;
import com.erp.dao.hr.PayAuditLogDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPayCorrection;
import com.erp.entity.hr.HrPayAdjustment;
import com.erp.entity.hr.HrPayrollRun;
import com.erp.entity.hr.HrPayrollRunItem;
import com.erp.entity.hr.HrPerfResult;
import com.erp.entity.hr.HrPayslip;
import com.erp.entity.hr.PayAuditLog;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrAttendanceService;
import com.erp.service.hr.HrIitService;
import com.erp.service.hr.HrPayrollRunService;
import com.erp.service.hr.HrSocialInsuranceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 薪资核算引擎（数据准备→公式→批次冻结→审批锁定→工资条；负数 L1/波动 L4/挂起暂停）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrPayrollRunServiceImpl implements HrPayrollRunService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HrPayrollRunDao runDao;
    private final HrPayrollRunItemDao itemDao;
    private final HrPayAdjustmentDao adjustDao;
    private final HrPayCorrectionDao correctionDao;
    private final HrPayslipDao payslipDao;
    private final HrEmployeeDao empDao;
    private final HrPerfResultDao perfResultDao;
    private final HrIitCumulativeDao iitCumulativeDao;
    private final PayAuditLogDao auditDao;
    private final HrAttendanceService attendanceService;
    private final HrSocialInsuranceService socialService;
    private final HrIitService iitService;
    private final SysParamService sysParamService;
    private final com.erp.service.hr.HrPayVoucherService payVoucherService;

    // ================================================================
    // 补扣项
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayAdjustment saveAdjustment(Map<String, Object> payload) {
        String period = str(payload.get("period"));
        String empId = str(payload.get("empId"));
        require(period, "核算期");
        require(empId, "员工");
        // 已有批次（非作废）→ 补扣项锁定（BR：生成 PayrollRun 后锁）
        if (hasActiveRun(period)) {
            throw new ServiceException(422, "该核算期已生成 PayrollRun，补扣项锁定（作废重建后可改）");
        }
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null || amount.signum() == 0) {
            throw new ServiceException(422, "金额必填且不可为 0");
        }
        String itemId = str(payload.get("id"));
        HrPayAdjustment a;
        boolean isNew = itemId == null || itemId.isBlank();
        if (!isNew) {
            a = adjustDao.selectById(itemId);
            if (a == null) {
                throw new ServiceException(404, "补扣项不存在：" + itemId);
            }
            if ("1".equals(a.getLocked())) {
                throw new ServiceException(422, "补扣项已锁定");
            }
            a.setUpdateBy(currentUser());
            a.setUpdateDate(LocalDateTime.now());
        } else {
            a = new HrPayAdjustment();
            a.setId(uuid());
            a.setPeriod(period);
            a.setEmpId(empId);
            a.setCreateBy(currentUser());
            a.setCreateDate(LocalDateTime.now());
        }
        a.setItemType(str(payload.get("itemType")));
        if (a.getItemType() == null || a.getItemType().isBlank()) {
            a.setItemType(HrPayAdjustment.TYPE_OTHER);
        }
        a.setAmount(amount);
        a.setReason(str(payload.get("reason")));
        if (isNew) {
            adjustDao.insert(a);
        } else {
            adjustDao.updateById(a);
        }
        audit(PayAuditLog.ACT_CALC, "ADJUSTMENT", a.getId(), period, "补扣项录入 " + amount);
        return a;
    }

    @Override
    public List<HrPayAdjustment> adjustmentList(String period, String empId) {
        LambdaQueryWrapper<HrPayAdjustment> qw = new LambdaQueryWrapper<HrPayAdjustment>()
                .eq(HrPayAdjustment::getDelFlag, "0")
                .orderByDesc(HrPayAdjustment::getCreateDate);
        if (notBlank(period)) {
            qw.eq(HrPayAdjustment::getPeriod, period);
        }
        if (notBlank(empId)) {
            qw.eq(HrPayAdjustment::getEmpId, empId);
        }
        return adjustDao.selectList(qw);
    }

    // ================================================================
    // 核算与批次
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayrollRun generate(String period, Map<String, Object> payload) {
        require(period, "核算期（yyyyMM）");
        if (period.length() != 6) {
            throw new ServiceException(422, "核算期格式 yyyyMM");
        }
        if (hasActiveRun(period)) {
            throw new ServiceException(422, "该核算期已有非作废批次，须先作废重建");
        }
        String perfPeriod = payload != null && notBlank(str(payload.get("perfPeriod")))
                ? str(payload.get("perfPeriod")) : period;
        int year = Integer.parseInt(period.substring(0, 4));
        int month = Integer.parseInt(period.substring(4));
        int monthNum = month;

        List<Map<String, Object>> summary = attendanceService.monthlySummary(period);
        Map<String, HrPerfResult> perfMap = new LinkedHashMap<>();
        for (HrPerfResult r : perfResultDao.selectList(new LambdaQueryWrapper<HrPerfResult>()
                .eq(HrPerfResult::getPeriod, perfPeriod)
                .eq(HrPerfResult::getDelFlag, "0"))) {
            perfMap.put(r.getEmpId(), r);
        }
        BigDecimal perfBasePct = decOr(sysParamService.getValue("PERF_BASE_PCT"), new BigDecimal("0.2"));
        BigDecimal volatilePct = decOr(sysParamService.getValue("PAY_VOLATILE_PCT"), new BigDecimal("20"));

        BigDecimal grossTotal = BigDecimal.ZERO;
        BigDecimal deductTotal = BigDecimal.ZERO;
        BigDecimal netTotal = BigDecimal.ZERO;
        List<HrPayrollRunItem> items = new ArrayList<>();
        List<Map<String, Object>> holds = new ArrayList<>();
        List<Map<String, Object>> blocked = new ArrayList<>();

        for (Map<String, Object> row : summary) {
            String empId = str(row.get("empId"));
            // C-4.7-01 挂起员工暂停核算
            if (Boolean.TRUE.equals(row.get("pendingHold"))) {
                holds.add(Map.of("empId", empId, "empName", String.valueOf(row.get("name"))));
                continue;
            }
            HrEmployee e = empDao.selectById(empId);
            if (e == null || e.getBaseSalary() == null) {
                blocked.add(Map.of("empId", empId, "name", String.valueOf(row.get("name")), "reason", "无月薪基数"));
                continue;
            }
            // L1 逐人阻断：属地政策未配置等只拦该员工（C-4.7-02），不炸整批
            // 减员口径（social-insurance-ops 衔接）：DONE 减员单且生效日 ≤ 核算月末 → 社保归零
            Map<String, Object> social;
            try {
                social = socialService.calc(empId, year,
                        java.time.YearMonth.of(year, monthNum).atEndOfMonth());
            } catch (ServiceException ex) {
                blocked.add(Map.of("empId", empId, "name", String.valueOf(row.get("name")), "reason", ex.getMessage()));
                continue;
            }
            BigDecimal baseSalary = e.getBaseSalary();
            BigDecimal leaveDeduct = nvl((BigDecimal) row.get("leaveDeduct"));
            BigDecimal overtimeWage = nvl((BigDecimal) row.get("overtimeWage"));
            // 绩效（结果快照；无结果按系数 1.0 计发并标注）
            HrPerfResult perf = perfMap.get(empId);
            BigDecimal perfCoef = perf != null ? perf.getCoef() : BigDecimal.ONE;
            String perfGrade = perf != null ? perf.getGrade() : null;
            String perfNote = perf == null ? "无绩效结果" : null;
            BigDecimal perfAmount = baseSalary.multiply(perfBasePct).multiply(perfCoef)
                    .setScale(2, RoundingMode.HALF_UP);
            // 补扣项：正=补贴，负=罚款类扣款
            BigDecimal allowance = BigDecimal.ZERO;
            BigDecimal fineDeduct = BigDecimal.ZERO;
            for (HrPayAdjustment adj : adjustDao.selectList(new LambdaQueryWrapper<HrPayAdjustment>()
                    .eq(HrPayAdjustment::getPeriod, period)
                    .eq(HrPayAdjustment::getEmpId, empId)
                    .eq(HrPayAdjustment::getDelFlag, "0"))) {
                if (adj.getAmount().signum() >= 0) {
                    allowance = allowance.add(adj.getAmount());
                } else {
                    fineDeduct = fineDeduct.add(adj.getAmount().negate());
                }
            }
            BigDecimal gross = baseSalary.add(perfAmount).add(allowance).add(overtimeWage);
            BigDecimal socialEeTotal = (BigDecimal) social.get("eeTotal");
            BigDecimal providentEe = BigDecimal.ZERO;
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> perType = (List<Map<String, Object>>) social.get("perInsType");
            for (Map<String, Object> t : perType) {
                if ("PROVIDENT".equals(t.get("insType"))) {
                    providentEe = (BigDecimal) t.get("eeAmt");
                }
            }
            BigDecimal socialEe = socialEeTotal.subtract(providentEe);
            // 个税累计预扣（读-算-写回）
            BigDecimal special = iitService.monthlySpecialTotal(empId);
            Map<String, Object> iit = iitService.calcMonthly(empId, year, monthNum,
                    gross, socialEeTotal, special);
            BigDecimal iitTax = (BigDecimal) iit.get("tax");
            // 其他扣款 = 罚款 + 请假日工资扣减
            BigDecimal otherDeduct = fineDeduct.add(leaveDeduct);
            BigDecimal net = gross.subtract(socialEe).subtract(providentEe)
                    .subtract(iitTax).subtract(otherDeduct).setScale(2, RoundingMode.HALF_UP);

            HrPayrollRunItem item = new HrPayrollRunItem();
            item.setId(uuid());
            item.setPeriod(period);
            item.setEmpId(empId);
            item.setEmpNo(e.getEmpNo());
            item.setEmpName(e.getName());
            item.setDeptId(e.getDeptId());
            item.setCostCenter(e.getCostCenter());
            item.setBaseSalary(baseSalary);
            item.setPerfAmount(perfAmount);
            item.setPerfGrade(perfGrade);
            item.setPerfCoef(perfCoef);
            item.setPerfNote(perfNote);
            item.setAllowance(allowance);
            item.setOvertimeWage(overtimeWage);
            item.setGross(gross.setScale(2, RoundingMode.HALF_UP));
            item.setSocialEe(socialEe);
            item.setProvidentEe(providentEe);
            if (Boolean.TRUE.equals(social.get("reduced"))) {
                item.setSocialNote("已减员");
            }
            item.setIit(iitTax);
            item.setOtherDeduct(otherDeduct.setScale(2, RoundingMode.HALF_UP));
            item.setLeaveDeduct(leaveDeduct);
            item.setNet(net);
            boolean neg = net.signum() < 0;
            item.setNegFlag(neg ? "1" : "0");
            // 环比波动：与该员工最近一期已审批批次实发比
            BigDecimal prevNet = previousNet(empId, period);
            boolean volatileNet = prevNet != null && prevNet.signum() > 0
                    && net.subtract(prevNet).abs()
                            .multiply(BigDecimal.valueOf(100))
                            .divide(prevNet, 2, RoundingMode.HALF_UP)
                            .compareTo(volatilePct) > 0;
            item.setVolatileFlag(volatileNet ? "1" : "0");
            item.setStatus(neg ? HrPayrollRunItem.ST_NEGATIVE
                    : (volatileNet ? HrPayrollRunItem.ST_VOLATILE : HrPayrollRunItem.ST_NORMAL));
            item.setCreateBy(currentUser());
            item.setCreateDate(LocalDateTime.now());
            items.add(item);
            grossTotal = grossTotal.add(item.getGross());
            deductTotal = deductTotal.add(socialEe).add(providentEe).add(iitTax).add(otherDeduct);
            netTotal = netTotal.add(net);
        }
        if (items.isEmpty()) {
            throw new ServiceException(422, "无可核算员工（全部挂起/阻断或无数据"
                    + (blocked.isEmpty() ? "" : "；阻断 " + blocked.size() + " 人：" + blocked) + "）");
        }
        HrPayrollRun run = new HrPayrollRun();
        run.setId(uuid());
        run.setRunNo(nextRunNo());
        run.setPeriod(period);
        run.setEmpCount(items.size());
        run.setGrossTotal(grossTotal.setScale(2, RoundingMode.HALF_UP));
        run.setDeductTotal(deductTotal.setScale(2, RoundingMode.HALF_UP));
        run.setNetTotal(netTotal.setScale(2, RoundingMode.HALF_UP));
        run.setStatus(HrPayrollRun.ST_DRAFT);
        run.setRunType(HrPayrollRun.TYPE_NORMAL);
        run.setCreateBy(currentUser());
        run.setCreateDate(LocalDateTime.now());
        runDao.insert(run);
        for (HrPayrollRunItem item : items) {
            item.setRunId(run.getId());
            itemDao.insert(item);
        }
        // 补扣项随批次锁定
        adjustDao.selectList(new LambdaQueryWrapper<HrPayAdjustment>()
                        .eq(HrPayAdjustment::getPeriod, period)
                        .eq(HrPayAdjustment::getDelFlag, "0"))
                .forEach(adj -> {
                    adj.setLocked("1");
                    adj.setUpdateBy(currentUser());
                    adj.setUpdateDate(LocalDateTime.now());
                    adjustDao.updateById(adj);
                });
        audit(PayAuditLog.ACT_CALC, "PAYROLL_RUN", run.getId(), period,
                "生成批次 " + run.getRunNo() + "，" + items.size() + " 人，实发合计 " + run.getNetTotal()
                        + (blocked.isEmpty() ? "" : "；L1 阻断跳过 " + blocked.size() + " 人 " + blocked)
                        + (holds.isEmpty() ? "" : "；挂起暂停 " + holds.size() + " 人"));
        log.info("payroll run generated: {} period={} emps={} net={}",
                run.getRunNo(), period, items.size(), run.getNetTotal());
        // 负数/波动清单随响应返回（不阻断生成，阻断提交）
        run.setVoidReason(null);
        return run;
    }

    @Override
    public Map<String, Object> preview(String period) {
        List<Map<String, Object>> summary = attendanceService.monthlySummary(period);
        int hold = (int) summary.stream().filter(r -> Boolean.TRUE.equals(r.get("pendingHold"))).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", period);
        out.put("empCount", summary.size());
        out.put("holdCount", hold);
        out.put("hasActiveRun", hasActiveRun(period));
        out.put("summary", summary);
        audit(PayAuditLog.ACT_VIEW, "PAYROLL_PREVIEW", period, period, "核算数据准备预览");
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayrollRun submit(String runId, Map<String, Object> payload) {
        HrPayrollRun run = requireRun(runId);
        if (!HrPayrollRun.ST_DRAFT.equals(run.getStatus())
                && !HrPayrollRun.ST_REJECTED.equals(run.getStatus())) {
            throw new ServiceException(422, "仅 DRAFT/REJECTED 可提交（当前：" + run.getStatus() + "）");
        }
        // NEGATIVE 阻断（L1）；VOLATILE 须核算员确认（L4：confirmVolatile=true）
        long negatives = itemDao.selectCount(new LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getRunId, runId)
                .eq(HrPayrollRunItem::getNegFlag, "1"));
        if (negatives > 0) {
            throw new ServiceException(422, "存在实发为负数员工（" + negatives + " 人，L1 阻断），请逐人核实修正后重建");
        }
        boolean confirmVolatile = payload != null
                && Boolean.parseBoolean(String.valueOf(payload.getOrDefault("confirmVolatile", "false")));
        long volatiles = itemDao.selectCount(new LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getRunId, runId)
                .eq(HrPayrollRunItem::getVolatileFlag, "1"));
        if (volatiles > 0 && !confirmVolatile) {
            throw new ServiceException(422, "存在环比波动>阈值员工（" + volatiles + " 人，L4），核算员确认后带 confirmVolatile=true 重提");
        }
        run.setStatus(HrPayrollRun.ST_SUBMITTING);
        run.setSubmitAt(LocalDateTime.now());
        run.setUpdateBy(currentUser());
        run.setUpdateDate(LocalDateTime.now());
        runDao.updateById(run);
        audit(PayAuditLog.ACT_CALC, "PAYROLL_RUN", runId, run.getPeriod(), "提交审批");
        return run;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayrollRun approve(String runId, Map<String, Object> payload) {
        HrPayrollRun run = requireRun(runId);
        if (!HrPayrollRun.ST_SUBMITTING.equals(run.getStatus())) {
            throw new ServiceException(422, "仅 SUBMITTING 可审批");
        }
        boolean approve = !"false".equals(String.valueOf(payload.getOrDefault("approve", "true")));
        run.setApproveBy(currentUser());
        run.setApproveAt(LocalDateTime.now());
        run.setApproveOpinion(str(payload.get("opinion")));
        if (approve) {
            run.setStatus(HrPayrollRun.ST_APPROVED); // 审批过锁定（BR-4.7-13）
            // 电子工资条生成推送（4.6）
            for (HrPayrollRunItem item : itemDao.selectList(new LambdaQueryWrapper<HrPayrollRunItem>()
                    .eq(HrPayrollRunItem::getRunId, runId)
                    .eq(HrPayrollRunItem::getDelFlag, "0"))) {
                HrPayslip slip = new HrPayslip();
                slip.setId(uuid());
                slip.setRunId(runId);
                slip.setItemId(item.getId());
                slip.setPeriod(run.getPeriod());
                slip.setEmpId(item.getEmpId());
                try {
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("baseSalary", item.getBaseSalary());
                    detail.put("perfAmount", item.getPerfAmount());
                    detail.put("perfGrade", item.getPerfGrade());
                    detail.put("allowance", item.getAllowance());
                    detail.put("overtimeWage", item.getOvertimeWage());
                    detail.put("gross", item.getGross());
                    detail.put("socialEe", item.getSocialEe());
                    detail.put("providentEe", item.getProvidentEe());
                    detail.put("iit", item.getIit());
                    detail.put("otherDeduct", item.getOtherDeduct());
                    detail.put("leaveDeduct", item.getLeaveDeduct());
                    detail.put("net", item.getNet());
                    slip.setDetailJson(MAPPER.writeValueAsString(detail));
                } catch (Exception ex) {
                    throw new ServiceException(500, "工资条快照序列化失败");
                }
                slip.setCreateBy(currentUser());
                slip.setCreateDate(LocalDateTime.now());
                payslipDao.insert(slip);
            }
        } else {
            run.setStatus(HrPayrollRun.ST_REJECTED);
        }
        run.setUpdateBy(currentUser());
        run.setUpdateDate(LocalDateTime.now());
        runDao.updateById(run);
        audit(PayAuditLog.ACT_APPROVE, "PAYROLL_RUN", runId, run.getPeriod(),
                approve ? "审批通过锁定" : "审批驳回");
        // 审批过 → 自动生成计提凭证（payroll-disbursement 组 6；失败不阻断发薪，仅告警）
        if (approve) {
            try {
                payVoucherService.accrual(runId);
            } catch (Exception e) {
                log.error("工资计提凭证生成失败 run={}：{}", runId, e.getMessage());
            }
        }
        return run;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayrollRun voidRun(String runId, String reason) {
        HrPayrollRun run = requireRun(runId);
        if (HrPayrollRun.ST_APPROVED.equals(run.getStatus())
                || HrPayrollRun.ST_PAID.equals(run.getStatus())
                || HrPayrollRun.ST_RECONCILED.equals(run.getStatus())
                || HrPayrollRun.ST_ARCHIVED.equals(run.getStatus())) {
            // 已进入发放链的批次：更正须走薪酬更正申请；此处仅允许未发放阶段作废
            if (!HrPayrollRun.ST_APPROVED.equals(run.getStatus())) {
                throw new ServiceException(422, "已进入发放链，不可直接作废（走薪酬更正申请）");
            }
        }
        require(reason, "作废原因");
        run.setStatus(HrPayrollRun.ST_VOID);
        run.setVoidReason(reason);
        run.setUpdateBy(currentUser());
        run.setUpdateDate(LocalDateTime.now());
        runDao.updateById(run);
        // 补扣项解锁（允许修正后重建）
        adjustDao.selectList(new LambdaQueryWrapper<HrPayAdjustment>()
                        .eq(HrPayAdjustment::getPeriod, run.getPeriod())
                        .eq(HrPayAdjustment::getDelFlag, "0"))
                .forEach(adj -> {
                    adj.setLocked("0");
                    adj.setUpdateBy(currentUser());
                    adj.setUpdateDate(LocalDateTime.now());
                    adjustDao.updateById(adj);
                });
        audit(PayAuditLog.ACT_CALC, "PAYROLL_RUN", runId, run.getPeriod(), "作废：" + reason);
        // 已生成计提/付款凭证 → 同步红字冲销留痕
        try {
            payVoucherService.reverseByRun(runId, "批次作废冲销：" + reason);
        } catch (Exception e) {
            log.error("工资凭证冲销失败 run={}：{}", runId, e.getMessage());
        }
        return run;
    }

    @Override
    public HrPayrollRun runById(String runId) {
        return requireRun(runId);
    }

    @Override
    public List<HrPayrollRun> runList(String period, String status) {
        LambdaQueryWrapper<HrPayrollRun> qw = new LambdaQueryWrapper<HrPayrollRun>()
                .eq(HrPayrollRun::getDelFlag, "0")
                .orderByDesc(HrPayrollRun::getPeriod);
        if (notBlank(period)) {
            qw.eq(HrPayrollRun::getPeriod, period);
        }
        if (notBlank(status)) {
            qw.eq(HrPayrollRun::getStatus, status);
        }
        return runDao.selectList(qw);
    }

    @Override
    public List<HrPayrollRunItem> runItems(String runId) {
        return itemDao.selectList(new LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getRunId, runId)
                .eq(HrPayrollRunItem::getDelFlag, "0")
                .orderByAsc(HrPayrollRunItem::getEmpNo));
    }

    // ================================================================
    // 薪酬更正（L2）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayCorrection submitCorrection(Map<String, Object> payload) {
        String runId = str(payload.get("runId"));
        HrPayrollRun run = requireRun(runId);
        if (!HrPayrollRun.ST_APPROVED.equals(run.getStatus())) {
            throw new ServiceException(422, "仅审批锁定批次可发起更正");
        }
        String empId = str(payload.get("empId"));
        BigDecimal amount = dec(payload.get("adjustAmount"));
        require(empId, "员工");
        if (amount == null || amount.signum() == 0) {
            throw new ServiceException(422, "调整金额必填且不可为 0");
        }
        HrPayCorrection c = new HrPayCorrection();
        c.setId(uuid());
        c.setCorrNo(nextCorrNo());
        c.setPeriod(run.getPeriod());
        c.setRunId(runId);
        c.setEmpId(empId);
        c.setAdjustItem(str(payload.get("adjustItem")));
        if (c.getAdjustItem() == null || c.getAdjustItem().isBlank()) {
            c.setAdjustItem("OTHER");
        }
        c.setAdjustAmount(amount);
        c.setReason(str(payload.get("reason")));
        require(c.getReason(), "更正原因");
        c.setStatus(HrPayCorrection.ST_SUBMITTED);
        c.setCreateBy(currentUser());
        c.setCreateDate(LocalDateTime.now());
        correctionDao.insert(c);
        audit(PayAuditLog.ACT_CORRECT, "PAY_CORRECTION", c.getId(), run.getPeriod(),
                "更正申请 " + c.getCorrNo() + " 金额 " + amount);
        return c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrPayCorrection approveCorrection(String corrId, Map<String, Object> payload) {
        HrPayCorrection c = correctionDao.selectById(corrId);
        if (c == null) {
            throw new ServiceException(404, "更正申请不存在：" + corrId);
        }
        if (!HrPayCorrection.ST_SUBMITTED.equals(c.getStatus())) {
            throw new ServiceException(422, "仅 SUBMITTED 可审批");
        }
        boolean approve = !"false".equals(String.valueOf(payload.getOrDefault("approve", "true")));
        c.setApprBy(currentUser());
        c.setApprAt(LocalDateTime.now());
        c.setApprOpinion(str(payload.get("opinion")));
        if (approve) {
            c.setStatus(HrPayCorrection.ST_APPROVED);
            // 生成更正批次（CORRECTION 类型，关联原批次，单员工差额明细）
            HrPayrollRun orig = requireRun(c.getRunId());
            HrPayrollRunItem origItem = itemDao.selectOne(new LambdaQueryWrapper<HrPayrollRunItem>()
                    .eq(HrPayrollRunItem::getRunId, c.getRunId())
                    .eq(HrPayrollRunItem::getEmpId, c.getEmpId())
                    .eq(HrPayrollRunItem::getDelFlag, "0"));
            if (origItem == null) {
                throw new ServiceException(422, "原批次无该员工明细");
            }
            BigDecimal delta = c.getAdjustAmount();
            HrPayrollRun corr = new HrPayrollRun();
            corr.setId(uuid());
            corr.setRunNo(nextRunNo());
            corr.setPeriod(orig.getPeriod());
            corr.setEmpCount(1);
            corr.setGrossTotal(delta.setScale(2, RoundingMode.HALF_UP));
            corr.setDeductTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            corr.setNetTotal(delta.setScale(2, RoundingMode.HALF_UP));
            corr.setStatus(HrPayrollRun.ST_DRAFT);
            corr.setRunType(HrPayrollRun.TYPE_CORRECTION);
            corr.setParentRunId(orig.getId());
            corr.setCreateBy(currentUser());
            corr.setCreateDate(LocalDateTime.now());
            runDao.insert(corr);
            // 更正明细：调整项差额单列（不重算社保/个税——差额发放口径）
            HrPayrollRunItem item = new HrPayrollRunItem();
            item.setId(uuid());
            item.setRunId(corr.getId());
            item.setPeriod(orig.getPeriod());
            item.setEmpId(origItem.getEmpId());
            item.setEmpNo(origItem.getEmpNo());
            item.setEmpName(origItem.getEmpName());
            item.setDeptId(origItem.getDeptId());
            item.setCostCenter(origItem.getCostCenter());
            item.setBaseSalary(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setPerfAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setAllowance(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setOvertimeWage(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setGross(delta.setScale(2, RoundingMode.HALF_UP));
            item.setSocialEe(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setProvidentEe(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setIit(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setOtherDeduct(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setLeaveDeduct(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            item.setNet(delta.setScale(2, RoundingMode.HALF_UP));
            item.setNegFlag("0");
            item.setVolatileFlag("0");
            item.setStatus(HrPayrollRunItem.ST_NORMAL);
            item.setPerfNote("更正差额（" + c.getAdjustItem() + "）：" + c.getReason());
            item.setCreateBy(currentUser());
            item.setCreateDate(LocalDateTime.now());
            itemDao.insert(item);
            c.setCorrectionRunId(corr.getId());
        } else {
            c.setStatus(HrPayCorrection.ST_REJECTED);
        }
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        correctionDao.updateById(c);
        audit(PayAuditLog.ACT_CORRECT, "PAY_CORRECTION", corrId, c.getPeriod(),
                approve ? "更正审批通过（生成更正批次）" : "更正审批驳回");
        return c;
    }

    @Override
    public List<HrPayCorrection> correctionList(String period, String runId) {
        LambdaQueryWrapper<HrPayCorrection> qw = new LambdaQueryWrapper<HrPayCorrection>()
                .eq(HrPayCorrection::getDelFlag, "0")
                .orderByDesc(HrPayCorrection::getCreateDate);
        if (notBlank(period)) {
            qw.eq(HrPayCorrection::getPeriod, period);
        }
        if (notBlank(runId)) {
            qw.eq(HrPayCorrection::getRunId, runId);
        }
        return correctionDao.selectList(qw);
    }

    // ================================================================
    // 工资条（自助端）
    // ================================================================

    @Override
    public List<Map<String, Object>> myPayslips(String empId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrPayslip slip : payslipDao.selectList(new LambdaQueryWrapper<HrPayslip>()
                .eq(HrPayslip::getEmpId, empId)
                .eq(HrPayslip::getDelFlag, "0")
                .orderByDesc(HrPayslip::getPeriod))) {
            HrPayrollRun run = runDao.selectById(slip.getRunId());
            if (run != null && !HrPayrollRun.ST_APPROVED.equals(run.getStatus())
                    && !HrPayrollRun.ST_PAID.equals(run.getStatus())
                    && !HrPayrollRun.ST_RECONCILED.equals(run.getStatus())
                    && !HrPayrollRun.ST_ARCHIVED.equals(run.getStatus())) {
                continue; // 仅审批通过批次可见
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", slip.getId());
            m.put("period", slip.getPeriod());
            m.put("detail", slip.getDetailJson());
            m.put("confirmed", slip.getConfirmedAt() != null);
            m.put("disputeContent", slip.getDisputeContent());
            m.put("replyContent", slip.getReplyContent());
            out.add(m);
            audit(PayAuditLog.ACT_SLIP_VIEW, "PAYSLIP", slip.getId(), slip.getPeriod(), "员工查看工资条");
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> confirmPayslip(String payslipId, String empId) {
        HrPayslip slip = requirePayslip(payslipId, empId);
        if (slip.getConfirmedAt() != null) {
            throw new ServiceException(422, "已确认");
        }
        slip.setConfirmedAt(LocalDateTime.now());
        slip.setUpdateBy(empId);
        slip.setUpdateDate(LocalDateTime.now());
        payslipDao.updateById(slip);
        return Map.of("confirmed", true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> disputePayslip(String payslipId, String empId, String content) {
        HrPayslip slip = requirePayslip(payslipId, empId);
        require(content, "异议内容");
        if (slip.getDisputeAt() != null && slip.getReplyAt() == null) {
            throw new ServiceException(422, "异议已在处理中");
        }
        slip.setDisputeContent(content);
        slip.setDisputeAt(LocalDateTime.now());
        slip.setUpdateBy(empId);
        slip.setUpdateDate(LocalDateTime.now());
        payslipDao.updateById(slip);
        return Map.of("disputed", true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> replyDispute(String payslipId, String content) {
        HrPayslip slip = payslipDao.selectById(payslipId);
        if (slip == null) {
            throw new ServiceException(404, "工资条不存在");
        }
        if (slip.getDisputeAt() == null) {
            throw new ServiceException(422, "无异议记录");
        }
        require(content, "回复内容");
        slip.setReplyContent(content);
        slip.setReplyAt(LocalDateTime.now());
        slip.setReplyBy(currentUser());
        slip.setUpdateBy(currentUser());
        slip.setUpdateDate(LocalDateTime.now());
        payslipDao.updateById(slip);
        return Map.of("replied", true);
    }

    // ================================================================
    // helpers
    // ================================================================

    private boolean hasActiveRun(String period) {
        return runDao.selectCount(new LambdaQueryWrapper<HrPayrollRun>()
                .eq(HrPayrollRun::getPeriod, period)
                .eq(HrPayrollRun::getRunType, HrPayrollRun.TYPE_NORMAL)
                .eq(HrPayrollRun::getDelFlag, "0")
                .ne(HrPayrollRun::getStatus, HrPayrollRun.ST_VOID)) > 0;
    }

    /** 该员工最近一期已审批 NORMAL 批次实发（环比基准）。 */
    private BigDecimal previousNet(String empId, String period) {
        List<HrPayrollRunItem> prev = itemDao.selectList(new LambdaQueryWrapper<HrPayrollRunItem>()
                .eq(HrPayrollRunItem::getEmpId, empId)
                .eq(HrPayrollRunItem::getDelFlag, "0")
                .lt(HrPayrollRunItem::getPeriod, period)
                .last("ORDER BY PERIOD DESC LIMIT 5"));
        for (HrPayrollRunItem p : prev) {
            HrPayrollRun r = runDao.selectById(p.getRunId());
            if (r != null && HrPayrollRun.ST_APPROVED.equals(r.getStatus())
                    && HrPayrollRun.TYPE_NORMAL.equals(r.getRunType())) {
                return p.getNet();
            }
        }
        return null;
    }

    private HrPayrollRun requireRun(String id) {
        HrPayrollRun run = runDao.selectById(id);
        if (run == null) {
            throw new ServiceException(404, "PayrollRun 不存在：" + id);
        }
        return run;
    }

    private HrPayslip requirePayslip(String id, String empId) {
        HrPayslip slip = payslipDao.selectById(id);
        if (slip == null || !slip.getEmpId().equals(empId)) {
            throw new ServiceException(404, "工资条不存在");
        }
        return slip;
    }

    private String nextRunNo() {
        String prefix = "PR" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        List<String> existing = runDao.selectList(new LambdaQueryWrapper<HrPayrollRun>()
                        .likeRight(HrPayrollRun::getRunNo, prefix)
                        .select(HrPayrollRun::getRunNo))
                .stream().map(HrPayrollRun::getRunNo).toList();
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(no.lastIndexOf('-') + 1)));
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String nextCorrNo() {
        String prefix = "PC" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        int max = correctionDao.selectList(new LambdaQueryWrapper<HrPayCorrection>()
                        .likeRight(HrPayCorrection::getCorrNo, prefix)
                        .select(HrPayCorrection::getCorrNo))
                .stream().map(HrPayCorrection::getCorrNo)
                .map(s -> {
                    try {
                        return Integer.parseInt(s.substring(s.lastIndexOf('-') + 1));
                    } catch (Exception e) {
                        return 0;
                    }
                }).max(Integer::compareTo).orElse(0);
        return prefix + String.format("%04d", max + 1);
    }

    /** 审计留痕（BR-4.7-15 L5，只插不改）。 */
    private void audit(String action, String targetType, String targetId, String period, String detail) {
        try {
            PayAuditLog l = new PayAuditLog();
            l.setId(uuid());
            l.setActor(currentUser());
            l.setAction(action);
            l.setTargetType(targetType);
            l.setTargetId(targetId);
            l.setPeriod(period);
            l.setDetail(detail);
            l.setOpAt(LocalDateTime.now());
            l.setCreateBy(currentUser());
            l.setCreateDate(LocalDateTime.now());
            auditDao.insert(l);
        } catch (Exception e) {
            log.warn("pay audit log write failed: {}", e.getMessage());
        }
    }

    private static void throws422(String msg) {
        throw new ServiceException(422, msg);
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static boolean notBlank(String v) {
        return v != null && !v.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "数值格式非法：" + o);
        }
    }

    private static BigDecimal decOr(String v, BigDecimal def) {
        try {
            return v == null || v.isBlank() ? def : new BigDecimal(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
