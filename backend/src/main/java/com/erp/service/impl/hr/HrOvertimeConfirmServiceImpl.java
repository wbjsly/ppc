package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrCompTimeBatchDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrHolidayCalendarDao;
import com.erp.dao.hr.HrLeaveBalanceBatchDao;
import com.erp.dao.hr.HrOvertimeApplyDao;
import com.erp.dao.hr.HrOvertimeConfirmDao;
import com.erp.dao.hr.HrPunchRecordDao;
import com.erp.entity.hr.HrCompTimeBatch;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrHolidayCalendar;
import com.erp.entity.hr.HrLeaveBalanceBatch;
import com.erp.entity.hr.HrOvertimeApply;
import com.erp.entity.hr.HrOvertimeConfirm;
import com.erp.entity.hr.HrPunchRecord;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrOvertimeConfirmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 加班确认实现（D4：MIN 认定/EXCESS 追认/分流固化/调休批次双写/试算不落库）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrOvertimeConfirmServiceImpl implements HrOvertimeConfirmService {

    private final HrOvertimeApplyDao applyDao;
    private final HrOvertimeConfirmDao confirmDao;
    private final HrPunchRecordDao punchDao;
    private final HrEmployeeDao empDao;
    private final HrHolidayCalendarDao holidayDao;
    private final HrCompTimeBatchDao compTimeDao;
    private final HrLeaveBalanceBatchDao balanceDao;
    private final SysParamService sysParamService;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ================================================================
    // 4.2 确认扫描
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int confirmScan() {
        LocalDate today = LocalDate.now();
        List<HrOvertimeApply> applies = applyDao.selectList(new LambdaQueryWrapper<HrOvertimeApply>()
                .eq(HrOvertimeApply::getStatus, HrOvertimeApply.ST_APPROVED)
                .le(HrOvertimeApply::getOtDate, today)
                .eq(HrOvertimeApply::getDelFlag, "0"));
        int created = 0;
        for (HrOvertimeApply a : applies) {
            created += confirmOne(a);
        }
        log.info("overtime confirm scan: {} applies, {} confirms", applies.size(), created);
        return created;
    }

    private int confirmOne(HrOvertimeApply a) {
        // 幂等：已有确认单跳过
        long existing = confirmDao.selectCount(new LambdaQueryWrapper<HrOvertimeConfirm>()
                .eq(HrOvertimeConfirm::getApplyId, a.getId())
                .eq(HrOvertimeConfirm::getDelFlag, "0"));
        if (existing > 0) {
            return 0;
        }
        // 打卡时长（上班→下班跨度，取当日最早 IN 与最晚 OUT）
        BigDecimal punched = punchHours(a.getEmpId(), a.getOtDate(), a.getStartTime(), a.getEndTime());
        BigDecimal applied = a.getHours();
        BigDecimal valid = punched.min(applied); // MIN(申请, 打卡)
        if (valid.signum() <= 0) {
            // 打卡缺失 → 走考勤异常申诉流程（不产生确认单）；申请保持 APPROVED 待补卡
            log.info("overtime {} no punch, awaiting makeup", a.getOtNo());
            return 0;
        }
        int excessMin = intParam("OT_EXCESS_TOLERANCE_MIN", 30);
        boolean excess = punched.subtract(applied)
                .multiply(BigDecimal.valueOf(60)).intValue() > excessMin;
        HrOvertimeConfirm c = new HrOvertimeConfirm();
        c.setId(uuid());
        c.setConfirmNo(nextConfirmNo(a.getOtDate()));
        c.setApplyId(a.getId());
        c.setEmpId(a.getEmpId());
        c.setEmpNo(a.getEmpNo());
        c.setOtDate(a.getOtDate());
        c.setAppliedHours(applied);
        c.setPunchedHours(punched);
        c.setValidHours(valid);
        c.setExcessFlag(excess ? "1" : "0");
        // 倍数消费假日日历
        BigDecimal mult = multiplierOf(a.getOtDate());
        c.setOtType(otTypeOf(a.getOtDate(), mult));
        c.setMultiplier(mult);
        c.setStatus(excess ? HrOvertimeConfirm.ST_EXCESS_PENDING : HrOvertimeConfirm.ST_PENDING);
        c.setCreateBy("scan");
        c.setCreateDate(LocalDateTime.now());
        confirmDao.insert(c);
        // 申请置 CONFIRMED
        a.setStatus(HrOvertimeApply.ST_CONFIRMED);
        a.setUpdateBy("scan");
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        return 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrOvertimeConfirm resolveExcess(String confirmId, Map<String, Object> payload) {
        HrOvertimeConfirm c = require(confirmId);
        if (!HrOvertimeConfirm.ST_EXCESS_PENDING.equals(c.getStatus())) {
            throw new ServiceException(422, "仅超时待追认确认单可处理");
        }
        boolean approve = "1".equals(String.valueOf(payload.get("approveExcess")));
        if (approve) {
            // 批准按实际打卡时长（封顶申请+容差内已认定，此处认可超出部分）
            c.setValidHours(c.getPunchedHours());
            c.setExcessFlag("0");
        }
        c.setStatus(HrOvertimeConfirm.ST_PENDING);
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        confirmDao.updateById(c);
        return c;
    }

    // ================================================================
    // 4.3 分流与金额固化
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrOvertimeConfirm split(String confirmId, Map<String, Object> payload) {
        HrOvertimeConfirm c = require(confirmId);
        if (!HrOvertimeConfirm.ST_PENDING.equals(c.getStatus())) {
            throw new ServiceException(422, "仅待分流确认单可分流（当前：" + c.getStatus() + "）");
        }
        String choice = str(payload.get("choice"));
        if (choice == null || choice.isBlank()) {
            // 默认：周末→调休；平日/法定假→工资
            choice = HrOvertimeConfirm.TYPE_WEEKEND.equals(c.getOtType())
                    ? HrOvertimeConfirm.SPLIT_COMP_TIME : HrOvertimeConfirm.SPLIT_WAGE;
        }
        // 法定假强制工资不得调休（FR-4.7-6-4）
        if (HrOvertimeConfirm.TYPE_HOLIDAY.equals(c.getOtType())
                && HrOvertimeConfirm.SPLIT_COMP_TIME.equals(choice)) {
            throw new ServiceException(422, "法定节假日加班不得选择调休，必须支付 3 倍工资");
        }
        if (!HrOvertimeConfirm.SPLIT_WAGE.equals(choice)
                && !HrOvertimeConfirm.SPLIT_COMP_TIME.equals(choice)) {
            throw new ServiceException(422, "分流选项非法（WAGE/COMP_TIME）");
        }
        c.setSplitChoice(choice);
        c.setSplitBy(currentUser());
        c.setSplitAt(LocalDateTime.now());
        if (HrOvertimeConfirm.SPLIT_WAGE.equals(choice)) {
            HrEmployee e = empDao.selectById(c.getEmpId());
            if (e == null || e.getBaseSalary() == null || e.getBaseSalary().signum() <= 0) {
                throw new ServiceException(422, "员工主档缺月薪基数（BASE_SALARY），请先在档案补录");
            }
            // 固化：小时基数 = 月薪/21.75/8；金额 = 基数×倍数×有效时长
            BigDecimal base = e.getBaseSalary()
                    .divide(BigDecimal.valueOf(21.75), 4, RoundingMode.HALF_UP)
                    .divide(BigDecimal.valueOf(8), 4, RoundingMode.HALF_UP);
            BigDecimal amount = base.multiply(c.getMultiplier()).multiply(c.getValidHours())
                    .setScale(2, RoundingMode.HALF_UP);
            c.setHourlyBase(base);
            c.setWageAmount(amount);
        } else {
            // 调休：批次双写（溯源锚 + 余额池作业）
            createCompTimeBatches(c);
        }
        c.setStatus(HrOvertimeConfirm.ST_SPLITTED);
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        confirmDao.updateById(c);
        log.info("overtime {} split to {} wage={}", c.getConfirmNo(), choice, c.getWageAmount());
        return c;
    }

    /** 调休批次双写：comp_time_batch 溯源 + leave_balance_batch FEFO 作业池（同一事务）。 */
    private void createCompTimeBatches(HrOvertimeConfirm c) {
        LocalDate now = LocalDate.now();
        int validMonths = intParam("COMP_TIME_VALID_MONTHS", 6);
        LocalDate expired = now.plusMonths(validMonths);
        HrCompTimeBatch ct = new HrCompTimeBatch();
        ct.setId(uuid());
        ct.setBatchNo(nextBatchNo("CT", compTimeDao::selectNosByPrefix));
        ct.setEmpId(c.getEmpId());
        ct.setHours(c.getValidHours());
        ct.setRemain(c.getValidHours());
        ct.setGeneratedAt(LocalDateTime.now());
        ct.setExpiredAt(expired);
        ct.setSourceConfirmId(c.getId());
        ct.setStatus(HrCompTimeBatch.ST_ACTIVE);
        ct.setCreateBy(currentUser());
        ct.setCreateDate(LocalDateTime.now());
        compTimeDao.insert(ct);
        HrLeaveBalanceBatch lb = new HrLeaveBalanceBatch();
        lb.setId(uuid());
        lb.setBatchNo(nextBatchNo("LBB", balanceDao::selectNosByPrefix));
        lb.setEmpId(c.getEmpId());
        lb.setTypeCode("COMP_TIME");
        lb.setQty(c.getValidHours());
        lb.setRemain(c.getValidHours());
        lb.setQtyUnit(HrLeaveBalanceBatch.UNIT_HOUR);
        lb.setSource(HrLeaveBalanceBatch.SRC_OT);
        lb.setSourceBatchId(ct.getId());
        lb.setGeneratedAt(LocalDateTime.now());
        lb.setExpiredAt(expired);
        lb.setStatus(HrLeaveBalanceBatch.ST_ACTIVE);
        lb.setCreateBy(currentUser());
        lb.setCreateDate(LocalDateTime.now());
        balanceDao.insert(lb);
    }

    // ================================================================
    // 试算（不落库）
    // ================================================================

    @Override
    public Map<String, Object> trial(String confirmId) {
        HrOvertimeConfirm c = require(confirmId);
        HrEmployee e = empDao.selectById(c.getEmpId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("validHours", c.getValidHours());
        out.put("otType", c.getOtType());
        out.put("multiplier", c.getMultiplier());
        if (e != null && e.getBaseSalary() != null && e.getBaseSalary().signum() > 0) {
            BigDecimal base = e.getBaseSalary()
                    .divide(BigDecimal.valueOf(21.75), 4, RoundingMode.HALF_UP)
                    .divide(BigDecimal.valueOf(8), 4, RoundingMode.HALF_UP);
            BigDecimal wage = base.multiply(c.getMultiplier()).multiply(c.getValidHours())
                    .setScale(2, RoundingMode.HALF_UP);
            out.put("wageEstimate", wage);
        } else {
            out.put("wageEstimate", null);
            out.put("wageNote", "主档缺月薪基数，无法试算工资");
        }
        if (HrOvertimeConfirm.TYPE_WEEKEND.equals(c.getOtType())) {
            int validMonths = intParam("COMP_TIME_VALID_MONTHS", 6);
            out.put("compTimeHours", c.getValidHours());
            out.put("compTimeExpireAt", LocalDate.now().plusMonths(validMonths).toString());
        } else {
            out.put("compTimeNote", "平日/法定假不可选调休");
        }
        return out; // 试算不落库——分流确认时固化
    }

    @Override
    public List<HrOvertimeConfirm> list(String empId, String status) {
        LambdaQueryWrapper<HrOvertimeConfirm> qw = new LambdaQueryWrapper<HrOvertimeConfirm>()
                .eq(HrOvertimeConfirm::getDelFlag, "0")
                .orderByDesc(HrOvertimeConfirm::getOtDate);
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrOvertimeConfirm::getEmpId, empId);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrOvertimeConfirm::getStatus, status);
        }
        return confirmDao.selectList(qw);
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 打卡时长：当日覆盖申请时段的 IN→OUT 跨度（小时，2 位小数）。 */
    private BigDecimal punchHours(String empId, LocalDate date, String reqStart, String reqEnd) {
        List<HrPunchRecord> punches = punchDao.selectList(new LambdaQueryWrapper<HrPunchRecord>()
                .eq(HrPunchRecord::getEmpId, empId)
                .eq(HrPunchRecord::getPunchDate, date)
                .eq(HrPunchRecord::getDelFlag, "0"));
        LocalTime in = null;
        LocalTime out = null;
        for (HrPunchRecord p : punches) {
            try {
                LocalTime t = LocalTime.parse(p.getPunchTime().length() > 5
                        ? p.getPunchTime().substring(0, 5) : p.getPunchTime(), HM);
                if (HrPunchRecord.DIR_IN.equals(p.getDir()) && (in == null || t.isBefore(in))) {
                    in = t;
                }
                if (HrPunchRecord.DIR_OUT.equals(p.getDir()) && (out == null || t.isAfter(out))) {
                    out = t;
                }
            } catch (Exception ignored) {
                // skip malformed
            }
        }
        if (in == null || out == null) {
            return BigDecimal.ZERO;
        }
        // 分钟差（LocalTime.plusHours 会回绕——同班次工时修复先例）
        long inMin = in.getHour() * 60L + in.getMinute();
        long outMin = out.getHour() * 60L + out.getMinute();
        long mins = outMin >= inMin ? outMin - inMin : (24 * 60 - inMin) + outMin;
        return BigDecimal.valueOf(mins).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    /** 倍数：法定假 3.0 / 周末 2.0 / 工作日 1.5（消费假日日历）。 */
    private BigDecimal multiplierOf(LocalDate date) {
        HrHolidayCalendar hd = holidayDao.selectByDate(date);
        if (hd != null && HrHolidayCalendar.TYPE_STATUTORY.equals(hd.getType())) {
            return new BigDecimal("3.0");
        }
        if (isRestDay(date, hd)) {
            return new BigDecimal("2.0");
        }
        return new BigDecimal("1.5");
    }

    private String otTypeOf(LocalDate date, BigDecimal mult) {
        if (new BigDecimal("3.0").compareTo(mult) == 0) {
            return HrOvertimeConfirm.TYPE_HOLIDAY;
        }
        if (new BigDecimal("2.0").compareTo(mult) == 0) {
            return HrOvertimeConfirm.TYPE_WEEKEND;
        }
        return HrOvertimeConfirm.TYPE_WEEKDAY;
    }

    private boolean isRestDay(LocalDate date, HrHolidayCalendar hd) {
        if (hd != null && HrHolidayCalendar.TYPE_WORKDAY.equals(hd.getType())) {
            return false;
        }
        if (hd != null && HrHolidayCalendar.TYPE_RESTDAY.equals(hd.getType())) {
            return true;
        }
        return date.getDayOfWeek().getValue() >= 6;
    }

    private String nextConfirmNo(LocalDate date) {
        return nextBatchNo("OTC" + date.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-",
                confirmDao::selectNosByPrefix);
    }

    private String nextBatchNo(String prefix, java.util.function.Function<String, List<String>> nosFn) {
        List<String> existing = nosFn.apply(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                String tail = no.substring(no.lastIndexOf('-') + 1).replaceAll("[^0-9]", "");
                if (!tail.isEmpty()) {
                    max = Math.max(max, Integer.parseInt(tail));
                }
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private HrOvertimeConfirm require(String id) {
        HrOvertimeConfirm c = confirmDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "加班确认单不存在：" + id);
        }
        return c;
    }

    private int intParam(String key, int def) {
        String v = sysParamService.getValue(key);
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
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
