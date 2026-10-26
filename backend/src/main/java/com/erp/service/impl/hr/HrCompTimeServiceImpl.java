package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrCompTimeBatchDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrLeaveBalanceBatchDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.entity.hr.HrCompTimeBatch;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrLeaveBalanceBatch;
import com.erp.entity.hr.HrRemind;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrCompTimeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 调休额度生命周期（D5：双写批次的溯源锚在此管理；FEFO 扣减走统一余额池 leave_balance_batch）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrCompTimeServiceImpl implements HrCompTimeService {

    private final HrCompTimeBatchDao compTimeDao;
    private final HrLeaveBalanceBatchDao balanceDao;
    private final HrEmployeeDao empDao;
    private final HrRemindDao remindDao;
    private final SysParamService sysParamService;

    @Override
    public List<HrCompTimeBatch> list(String empId) {
        LambdaQueryWrapper<HrCompTimeBatch> qw = new LambdaQueryWrapper<HrCompTimeBatch>()
                .eq(HrCompTimeBatch::getDelFlag, "0")
                .orderByAsc(HrCompTimeBatch::getExpiredAt);
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrCompTimeBatch::getEmpId, empId);
        }
        return compTimeDao.selectList(qw);
    }

    @Override
    public Map<String, Object> balance(String empId) {
        List<HrLeaveBalanceBatch> active = balanceDao.selectList(new LambdaQueryWrapper<HrLeaveBalanceBatch>()
                .eq(HrLeaveBalanceBatch::getEmpId, empId)
                .eq(HrLeaveBalanceBatch::getTypeCode, "COMP_TIME")
                .eq(HrLeaveBalanceBatch::getStatus, HrLeaveBalanceBatch.ST_ACTIVE)
                .eq(HrLeaveBalanceBatch::getDelFlag, "0")
                .orderByAsc(HrLeaveBalanceBatch::getExpiredAt)); // FEFO 序
        BigDecimal total = active.stream().map(HrLeaveBalanceBatch::getRemain)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalHours", total);
        out.put("batches", active); // 到期日升序（先到期先用）
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> expiryScan() {
        LocalDate today = LocalDate.now();
        int remindDays = intParam("COMP_TIME_REMIND_DAYS", 15);
        LocalDate remindCutoff = today.plusDays(remindDays);
        int reminded = 0;
        int expired = 0;
        // ① 到期前提醒（ACTIVE 且到期日 ≤ cutoff）
        List<HrLeaveBalanceBatch> nearExpiry = balanceDao.selectList(new LambdaQueryWrapper<HrLeaveBalanceBatch>()
                .eq(HrLeaveBalanceBatch::getTypeCode, "COMP_TIME")
                .eq(HrLeaveBalanceBatch::getStatus, HrLeaveBalanceBatch.ST_ACTIVE)
                .le(HrLeaveBalanceBatch::getExpiredAt, remindCutoff)
                .eq(HrLeaveBalanceBatch::getDelFlag, "0"));
        for (HrLeaveBalanceBatch b : nearExpiry) {
            if (b.getExpiredAt().isBefore(today)) {
                continue; // 已过期走清零
            }
            // 同批同日防重（hr_remind 按 EMP+日期+类型）
            long sameDay = remindDao.selectCount(new LambdaQueryWrapper<HrRemind>()
                    .eq(HrRemind::getEmpId, b.getEmpId())
                    .eq(HrRemind::getRemindType, "COMP_TIME_EXPIRY")
                    .ge(HrRemind::getRemindDate, today)
                    .lt(HrRemind::getRemindDate, today.plusDays(1)));
            if (sameDay > 0) {
                continue;
            }
            HrEmployee e = empDao.selectById(b.getEmpId());
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType("COMP_TIME_EXPIRY");
            r.setEmpId(b.getEmpId());
            r.setEmpName(e == null ? null : e.getName());
            r.setRemindDate(today);
            r.setDetail("调休额度 " + b.getRemain().stripTrailingZeros().toPlainString()
                    + " 小时将于 " + b.getExpiredAt() + " 到期");
            r.setCreateBy("scan");
            r.setCreateDate(LocalDateTime.now());
            remindDao.insert(r);
            reminded++;
        }
        // ② 过期批次自动清零留痕（双写同步：balance_batch + comp_time_batch）
        List<HrLeaveBalanceBatch> overdue = balanceDao.selectList(new LambdaQueryWrapper<HrLeaveBalanceBatch>()
                .eq(HrLeaveBalanceBatch::getTypeCode, "COMP_TIME")
                .eq(HrLeaveBalanceBatch::getStatus, HrLeaveBalanceBatch.ST_ACTIVE)
                .lt(HrLeaveBalanceBatch::getExpiredAt, today)
                .eq(HrLeaveBalanceBatch::getDelFlag, "0"));
        for (HrLeaveBalanceBatch b : overdue) {
            HrEmployee e = empDao.selectById(b.getEmpId());
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType("COMP_TIME_CLEARED");
            r.setEmpId(b.getEmpId());
            r.setEmpName(e == null ? null : e.getName());
            r.setRemindDate(today);
            r.setDetail("调休额度批次 " + b.getBatchNo() + " 已到期，"
                    + b.getRemain().stripTrailingZeros().toPlainString() + " 小时自动清零（留痕）");
            r.setCreateBy("scan");
            r.setCreateDate(LocalDateTime.now());
            remindDao.insert(r);
            b.setRemain(BigDecimal.ZERO);
            b.setStatus(HrLeaveBalanceBatch.ST_EXPIRED);
            b.setUpdateBy("scan");
            b.setUpdateDate(LocalDateTime.now());
            balanceDao.updateById(b);
            // 同步溯源锚
            if (b.getSourceBatchId() != null) {
                HrCompTimeBatch ct = compTimeDao.selectById(b.getSourceBatchId());
                if (ct != null && HrCompTimeBatch.ST_ACTIVE.equals(ct.getStatus())) {
                    ct.setRemain(BigDecimal.ZERO);
                    ct.setStatus(HrCompTimeBatch.ST_EXPIRED);
                    ct.setUpdateBy("scan");
                    ct.setUpdateDate(LocalDateTime.now());
                    compTimeDao.updateById(ct);
                }
            }
            expired++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reminded", reminded);
        out.put("expired", expired);
        if (reminded > 0 || expired > 0) {
            log.info("comp time expiry scan: reminded={} expired={}", reminded, expired);
        }
        return out;
    }

    @Override
    public Map<String, Object> resignSettlement(String empId) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工不存在：" + empId);
        }
        Map<String, Object> bal = balance(empId);
        @SuppressWarnings("unchecked")
        BigDecimal totalHours = (BigDecimal) bal.get("totalHours");
        BigDecimal rate = dec(sysParamService.getValue("COMP_TIME_RESIGN_RATE"));
        if (rate == null) {
            rate = BigDecimal.valueOf(2);
        }
        BigDecimal hourlyBase = null;
        BigDecimal amount = null;
        if (e.getBaseSalary() != null && e.getBaseSalary().signum() > 0) {
            hourlyBase = e.getBaseSalary()
                    .divide(BigDecimal.valueOf(21.75), 4, RoundingMode.HALF_UP)
                    .divide(BigDecimal.valueOf(8), 4, RoundingMode.HALF_UP);
            amount = hourlyBase.multiply(rate).multiply(totalHours).setScale(2, RoundingMode.HALF_UP);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("empNo", e.getEmpNo());
        out.put("unusedHours", totalHours);
        out.put("rate", rate);
        out.put("hourlyBase", hourlyBase);
        out.put("settleAmount", amount); // 离职结算登记留桩（薪酬域未实施，供发放参考）
        out.put("note", "离线登记留桩：随离职薪酬结算发放");
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    private int intParam(String key, int def) {
        String v = sysParamService.getValue(key);
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
