package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrLeaveApplyDao;
import com.erp.dao.hr.HrLeaveTypeDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrLeaveApply;
import com.erp.entity.hr.HrLeaveType;
import com.erp.service.hr.HrLeaveApplyService;
import com.erp.service.hr.HrLeaveBalanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 请假单实现（余额类提交时校验 L1；审批通过 FEFO 扣减+固化扣款；驳回/取消回滚）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrLeaveApplyServiceImpl implements HrLeaveApplyService {

    private final HrLeaveApplyDao applyDao;
    private final HrLeaveTypeDao typeDao;
    private final HrEmployeeDao empDao;
    private final HrLeaveBalanceService balanceService;
    private final com.erp.service.SysParamService sysParamService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrLeaveApply submit(Map<String, Object> payload) {
        String empId = str(payload.get("empId"));
        HrEmployee e = empId == null ? null : empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工不存在：" + empId);
        }
        String typeCode = str(payload.get("typeCode"));
        require(typeCode, "假期类型");
        HrLeaveType type = requireType(typeCode);
        LocalDate start = date(payload.get("startDate"));
        LocalDate end = date(payload.get("endDate"));
        if (start == null || end == null) {
            throw new ServiceException(422, "起止日期必填");
        }
        if (end.isBefore(start)) {
            throw new ServiceException(422, "结束日期不可早于开始日期");
        }
        BigDecimal duration = dec(payload.get("duration"));
        if (duration == null || duration.signum() <= 0) {
            // 缺省按自然日天数
            duration = BigDecimal.valueOf(java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1);
        }
        // 法定天数上限校验（婚/产/陪产/丧假）
        if (type.getStatutoryDays() != null && duration.compareTo(type.getStatutoryDays()) > 0) {
            throw new ServiceException(422, type.getName() + "法定上限 "
                    + type.getStatutoryDays().stripTrailingZeros().toPlainString() + " 天");
        }
        String reason = str(payload.get("reason"));
        require(reason, "请假事由");
        // 余额类假期：提交时校验余额充足 L1（扣减在审批通过时执行）
        if ("Y".equals(type.getNeedBalance())) {
            Map<String, Object> bal = balanceService.balances(empId);
            @SuppressWarnings("unchecked")
            Map<String, BigDecimal> totals = (Map<String, BigDecimal>) bal.get("totals");
            BigDecimal have = totals.getOrDefault(typeCode, BigDecimal.ZERO);
            if (have.compareTo(duration) < 0) {
                throw new ServiceException(422, type.getName() + "余额不足（可申请 "
                        + have.stripTrailingZeros().toPlainString() + "，L1 硬阻断）");
            }
        }
        HrLeaveApply a = new HrLeaveApply();
        a.setId(uuid());
        a.setLvNo(nextNo());
        a.setEmpId(empId);
        a.setEmpNo(e.getEmpNo());
        a.setEmpName(e.getName());
        a.setDeptId(e.getDeptId());
        a.setTypeCode(typeCode);
        a.setStartDate(start);
        a.setEndDate(end);
        a.setDuration(duration);
        a.setDurationUnit("DAY");
        a.setReason(reason);
        a.setDelegate(str(payload.get("delegate")));
        a.setStatus(HrLeaveApply.ST_DRAFT);
        // 日工资基数快照 + 扣款测算（病假 SICK_LEAVE_RATE 等）
        if (e.getBaseSalary() != null && e.getBaseSalary().signum() > 0) {
            BigDecimal dailyBase = e.getBaseSalary().divide(BigDecimal.valueOf(21.75), 2, RoundingMode.HALF_UP);
            a.setDailyBase(dailyBase);
            BigDecimal rate = type.getDailyDeductionRate();
            a.setDeductAmount(dailyBase.multiply(rate).multiply(duration).setScale(2, RoundingMode.HALF_UP));
        }
        a.setCreateBy(currentUser());
        a.setCreateDate(LocalDateTime.now());
        applyDao.insert(a);
        log.info("leave apply submitted: {} {} {} {}d", a.getLvNo(), e.getEmpNo(), typeCode, duration);
        return a;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrLeaveApply approve(String applyId, Map<String, Object> payload) {
        HrLeaveApply a = require(applyId);
        if (!HrLeaveApply.ST_DRAFT.equals(a.getStatus())
                && !HrLeaveApply.ST_APPROVING.equals(a.getStatus())) {
            throw new ServiceException(422, "请假单状态不可审批（当前：" + a.getStatus() + "）");
        }
        boolean approve = !"false".equals(String.valueOf(payload.get("approve")));
        if (!approve) {
            a.setStatus(HrLeaveApply.ST_REJECTED);
            a.setUpdateBy(currentUser());
            a.setUpdateDate(LocalDateTime.now());
            applyDao.updateById(a);
            return a;
        }
        HrLeaveType type = requireType(a.getTypeCode());
        // 余额类：FEFO 扣减 + 留痕 JSON
        if ("Y".equals(type.getNeedBalance())) {
            List<Map<String, Object>> trace = balanceService.consume(a.getEmpId(), a.getTypeCode(), a.getDuration());
            try {
                a.setBalanceConsumeJson(objectMapper.writeValueAsString(trace));
            } catch (Exception ex) {
                a.setBalanceConsumeJson("[]");
            }
        }
        a.setStatus(HrLeaveApply.ST_APPROVED);
        a.setUpdateBy(currentUser());
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("leave apply approved: {} consume={}", a.getLvNo(),
                a.getBalanceConsumeJson() != null);
        return a;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrLeaveApply cancel(String applyId, String reason) {
        HrLeaveApply a = require(applyId);
        if (!HrLeaveApply.ST_APPROVED.equals(a.getStatus())) {
            throw new ServiceException(422, "仅已批准请假可取消（更正驳回）");
        }
        require(reason, "取消原因");
        // 扣减回滚（FEFO 还原批次）
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> trace = a.getBalanceConsumeJson() == null ? List.of()
                    : objectMapper.readValue(a.getBalanceConsumeJson(), List.class);
            balanceService.rollback(a.getEmpId(), a.getTypeCode(), trace);
        } catch (Exception ex) {
            log.warn("leave rollback parse failed: {}", ex.getMessage());
        }
        a.setStatus(HrLeaveApply.ST_CANCELLED);
        a.setReason(a.getReason() + "（取消：" + reason + "）");
        a.setUpdateBy(currentUser());
        a.setUpdateDate(LocalDateTime.now());
        applyDao.updateById(a);
        log.info("leave apply cancelled: {} rollback", a.getLvNo());
        return a;
    }

    @Override
    public List<HrLeaveApply> list(String empId, String status) {
        LambdaQueryWrapper<HrLeaveApply> qw = new LambdaQueryWrapper<HrLeaveApply>()
                .eq(HrLeaveApply::getDelFlag, "0")
                .orderByDesc(HrLeaveApply::getStartDate);
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrLeaveApply::getEmpId, empId);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrLeaveApply::getStatus, status);
        }
        return applyDao.selectList(qw);
    }

    // ================================================================
    // helpers
    // ================================================================

    private HrLeaveType requireType(String typeCode) {
        HrLeaveType t = typeDao.selectOne(new LambdaQueryWrapper<HrLeaveType>()
                .eq(HrLeaveType::getTypeCode, typeCode)
                .eq(HrLeaveType::getDelFlag, "0"));
        if (t == null) {
            throw new ServiceException(404, "假期类型不存在：" + typeCode);
        }
        return t;
    }

    private String nextNo() {
        String prefix = "LV" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        List<String> existing = applyDao.selectNosByPrefix(prefix + "%");
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

    private HrLeaveApply require(String id) {
        HrLeaveApply a = applyDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "请假单不存在：" + id);
        }
        return a;
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法：" + o);
        }
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
