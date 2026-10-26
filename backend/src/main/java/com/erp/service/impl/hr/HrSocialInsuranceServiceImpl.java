package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.hr.HrSocialPolicyDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.hr.HrSocialPolicy;
import com.erp.service.hr.HrSocialInsuranceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 社保公积金扣缴实现（属地匹配封顶保底；未配置 L1；7 月基数调整提醒）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrSocialInsuranceServiceImpl implements HrSocialInsuranceService {

    private static final String[] ALL_TYPES = {
            HrSocialPolicy.INS_PENSION, HrSocialPolicy.INS_MEDICAL, HrSocialPolicy.INS_UNEMPLOY,
            HrSocialPolicy.INS_INJURY, HrSocialPolicy.INS_MATERNITY, HrSocialPolicy.INS_PROVIDENT
    };
    /** DEMO 占位城市不参与基数调整提醒（演示数据） */
    private static final String DEMO_CITY = "DEMO市";

    private final HrSocialPolicyDao policyDao;
    private final HrEmployeeDao empDao;
    private final HrRemindDao remindDao;
    private final com.erp.service.hr.HrSocialChangeService socialChangeService;

    @Override
    public Map<String, Object> calc(String empId, int year, java.time.LocalDate asOfMonthEnd) {
        // 减员生效口径（social-insurance-ops）：DONE 减员单且生效日 ≤ 月末 → 扣缴归零
        if (asOfMonthEnd != null && socialChangeService.hasDoneChange(empId,
                com.erp.entity.hr.HrSocialChange.TYPE_REMOVE, asOfMonthEnd)) {
            HrEmployee e = empDao.selectById(empId);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("city", e == null ? null : e.getSocialPlace());
            out.put("year", year);
            out.put("empTotal", BigDecimal.ZERO);
            out.put("eeTotal", BigDecimal.ZERO);
            List<Map<String, Object>> perType = new ArrayList<>();
            for (String t : ALL_TYPES) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("insType", t);
                row.put("base", BigDecimal.ZERO);
                row.put("empAmt", BigDecimal.ZERO);
                row.put("eeAmt", BigDecimal.ZERO);
                perType.add(row);
            }
            out.put("perInsType", perType);
            out.put("reduced", Boolean.TRUE);
            return out;
        }
        return calc(empId, year);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSocialPolicy savePolicy(Map<String, Object> payload) {
        String city = str(payload.get("city"));
        Integer year = intOf(payload.get("year"));
        String insType = str(payload.get("insType"));
        require(city, "城市");
        if (year == null) {
            throw new ServiceException(422, "年度必填");
        }
        if (insType == null || !List.of(ALL_TYPES).contains(insType)) {
            throw new ServiceException(422, "险种非法：" + insType);
        }
        BigDecimal baseMin = dec(payload.get("baseMin"));
        BigDecimal baseMax = dec(payload.get("baseMax"));
        BigDecimal empRate = dec(payload.get("empRate"));
        BigDecimal eeRate = dec(payload.get("eeRate"));
        if (baseMin == null || baseMax == null || empRate == null || eeRate == null) {
            throw new ServiceException(422, "基数上下限与比例必填");
        }
        if (baseMin.compareTo(baseMax) > 0) {
            throw new ServiceException(422, "基数下限不可大于上限");
        }
        HrSocialPolicy p = policyDao.selectOne(new LambdaQueryWrapper<HrSocialPolicy>()
                .eq(HrSocialPolicy::getCity, city)
                .eq(HrSocialPolicy::getYear, year)
                .eq(HrSocialPolicy::getInsType, insType)
                .eq(HrSocialPolicy::getDelFlag, "0"));
        boolean isNew = p == null;
        if (isNew) {
            p = new HrSocialPolicy();
            p.setId(uuid());
            p.setCity(city);
            p.setYear(year);
            p.setInsType(insType);
            p.setCreateBy(currentUser());
            p.setCreateDate(LocalDateTime.now());
        } else {
            p.setUpdateBy(currentUser());
            p.setUpdateDate(LocalDateTime.now());
        }
        p.setBaseMin(baseMin);
        p.setBaseMax(baseMax);
        p.setEmpRate(empRate);
        p.setEeRate(eeRate);
        if (isNew) {
            policyDao.insert(p);
        } else {
            policyDao.updateById(p);
        }
        return p;
    }

    @Override
    public List<HrSocialPolicy> policyList(String city, Integer year) {
        LambdaQueryWrapper<HrSocialPolicy> qw = new LambdaQueryWrapper<HrSocialPolicy>()
                .eq(HrSocialPolicy::getDelFlag, "0")
                .orderByAsc(HrSocialPolicy::getCity).orderByAsc(HrSocialPolicy::getInsType);
        if (city != null && !city.isBlank()) {
            qw.eq(HrSocialPolicy::getCity, city);
        }
        if (year != null) {
            qw.eq(HrSocialPolicy::getYear, year);
        }
        return policyDao.selectList(qw);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> copyYear(Integer fromYear, Integer toYear) {
        if (fromYear == null || toYear == null) {
            throw new ServiceException(422, "源/目标年度必填");
        }
        List<HrSocialPolicy> src = policyList(null, fromYear);
        int copied = 0;
        for (HrSocialPolicy s : src) {
            boolean exists = policyDao.selectCount(new LambdaQueryWrapper<HrSocialPolicy>()
                    .eq(HrSocialPolicy::getCity, s.getCity())
                    .eq(HrSocialPolicy::getYear, toYear)
                    .eq(HrSocialPolicy::getInsType, s.getInsType())
                    .eq(HrSocialPolicy::getDelFlag, "0")) > 0;
            if (exists) {
                continue;
            }
            HrSocialPolicy n = new HrSocialPolicy();
            n.setId(uuid());
            n.setCity(s.getCity());
            n.setYear(toYear);
            n.setInsType(s.getInsType());
            n.setBaseMin(s.getBaseMin());
            n.setBaseMax(s.getBaseMax());
            n.setEmpRate(s.getEmpRate());
            n.setEeRate(s.getEeRate());
            n.setCreateBy(currentUser());
            n.setCreateDate(LocalDateTime.now());
            policyDao.insert(n);
            copied++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("copied", copied);
        return out;
    }

    @Override
    public Map<String, Object> calc(String empId, int year) {
        HrEmployee e = empId == null ? null : empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工不存在：" + empId);
        }
        if (e.getBaseSalary() == null) {
            throw new ServiceException(422, "员工无月薪基数（BASE_SALARY），请先补录档案");
        }
        String city = e.getSocialPlace();
        if (city == null || city.isBlank()) {
            throw new ServiceException(422, "员工未维护社保缴纳地（L1，C-4.7-02）");
        }
        List<HrSocialPolicy> policies = policyDao.selectList(new LambdaQueryWrapper<HrSocialPolicy>()
                .eq(HrSocialPolicy::getCity, city)
                .eq(HrSocialPolicy::getYear, year)
                .eq(HrSocialPolicy::getDelFlag, "0"));
        // 任一险种未配置 → L1 硬阻断该员工核算
        for (String t : ALL_TYPES) {
            boolean found = policies.stream().anyMatch(p -> t.equals(p.getInsType()));
            if (!found) {
                throw new ServiceException(422, "属地政策未配置：" + city + " " + year + " 年 " + t + "（L1 硬阻断 C-4.7-02）");
            }
        }
        BigDecimal salary = e.getBaseSalary();
        BigDecimal empTotal = BigDecimal.ZERO;
        BigDecimal eeTotal = BigDecimal.ZERO;
        List<Map<String, Object>> perType = new ArrayList<>();
        for (HrSocialPolicy p : policies) {
            // 基数 = MIN(MAX(月薪, 下限), 上限)
            BigDecimal base = salary.max(p.getBaseMin()).min(p.getBaseMax());
            BigDecimal empAmt = base.multiply(p.getEmpRate()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal eeAmt = base.multiply(p.getEeRate()).setScale(2, RoundingMode.HALF_UP);
            empTotal = empTotal.add(empAmt);
            eeTotal = eeTotal.add(eeAmt);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("insType", p.getInsType());
            row.put("base", base);
            row.put("empAmt", empAmt);
            row.put("eeAmt", eeAmt);
            perType.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("city", city);
        out.put("year", year);
        out.put("empTotal", empTotal);
        out.put("eeTotal", eeTotal);
        out.put("perInsType", perType);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int julyBaseAdjustScan() {
        LocalDate today = LocalDate.now();
        // 仅 6 月触发（每年 7 月调整前提示）
        if (today.getMonthValue() != 6) {
            return 0;
        }
        int nextYear = today.getYear() + 1;
        // 已有员工缴纳地集合
        List<String> places = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                        .eq(HrEmployee::getDelFlag, "0")
                        .isNotNull(HrEmployee::getSocialPlace))
                .stream().map(HrEmployee::getSocialPlace).distinct()
                .filter(c -> c != null && !c.isBlank() && !DEMO_CITY.equals(c))
                .toList();
        int reminded = 0;
        for (String city : places) {
            long rows = policyDao.selectCount(new LambdaQueryWrapper<HrSocialPolicy>()
                    .eq(HrSocialPolicy::getCity, city)
                    .eq(HrSocialPolicy::getYear, nextYear)
                    .eq(HrSocialPolicy::getDelFlag, "0"));
            if (rows > 0) {
                continue;
            }
            boolean exists = remindDao.exists(new LambdaQueryWrapper<HrRemind>()
                    .eq(HrRemind::getRemindType, "SOCIAL_BASE_ADJUST")
                    .eq(HrRemind::getEmpId, city)
                    .eq(HrRemind::getRemindDate, today));
            if (exists) {
                continue;
            }
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType("SOCIAL_BASE_ADJUST");
            r.setEmpId(city);
            r.setEmpName(city);
            r.setRemindDate(today);
            r.setDetail("社保基数 7 月年度调整：" + city + " " + nextYear + " 年政策未维护，请及时更新基数上下限与比例");
            r.setCreateBy("scan");
            r.setCreateDate(LocalDateTime.now());
            remindDao.insert(r);
            reminded++;
        }
        return reminded;
    }

    // ================================================================
    // helpers
    // ================================================================

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static Integer intOf(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(o).trim()).intValue();
        } catch (Exception e) {
            throw new ServiceException(422, "数值格式非法：" + o);
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
