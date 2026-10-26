package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrScheduleDao;
import com.erp.dao.hr.HrShiftDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrSchedule;
import com.erp.entity.hr.HrShift;
import com.erp.service.hr.HrCertService;
import com.erp.service.hr.HrShiftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** HR 班次与排班日历实现（M10-3-1）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrShiftServiceImpl implements HrShiftService {

    private final HrShiftDao shiftDao;
    private final HrScheduleDao scheduleDao;
    private final HrEmployeeDao empDao;
    private final HrCertService certService;

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    // ================================================================
    // 班次
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrShift create(Map<String, Object> payload) {
        String name = str(payload.get("name"));
        require(name, "班次名称");
        String start = str(payload.get("startTime"));
        String end = str(payload.get("endTime"));
        require(start, "上班时间");
        require(end, "下班时间");

        HrShift s = new HrShift();
        s.setId(uuid());
        s.setShiftNo(nextNo());
        s.setName(name);
        s.setStartTime(start);
        s.setEndTime(end);
        boolean cross = computeCrossDay(start, end);
        s.setCrossDay(cross ? "1" : "0");
        BigDecimal hours = dec(payload.get("hours"));
        if (hours == null) {
            hours = computeHours(start, end, cross);
        }
        s.setHours(hours);
        s.setOvertimeEligible(payload.get("overtimeEligible") == null ? "1"
                : String.valueOf(payload.get("overtimeEligible")));
        Integer tol = intOrNull(payload.get("toleranceMin"));
        s.setToleranceMin(tol == null ? 10 : tol);
        s.setStatus("1");
        s.setCreateBy(currentUser());
        s.setCreateDate(LocalDateTime.now());
        shiftDao.insert(s);
        log.info("hr shift created: {} {} {}-{}", s.getShiftNo(), name, start, end);
        return s;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrShift update(String shiftId, Map<String, Object> payload) {
        HrShift s = require(shiftId);
        if (payload.containsKey("name")) {
            s.setName(str(payload.get("name")));
        }
        if (payload.containsKey("toleranceMin")) {
            Integer tol = intOrNull(payload.get("toleranceMin"));
            if (tol != null) {
                s.setToleranceMin(tol);
            }
        }
        if (payload.containsKey("overtimeEligible")) {
            s.setOvertimeEligible(String.valueOf(payload.get("overtimeEligible")));
        }
        if (payload.containsKey("startTime") || payload.containsKey("endTime")) {
            String start = payload.containsKey("startTime") ? str(payload.get("startTime")) : s.getStartTime();
            String end = payload.containsKey("endTime") ? str(payload.get("endTime")) : s.getEndTime();
            boolean cross = computeCrossDay(start, end);
            s.setCrossDay(cross ? "1" : "0");
            BigDecimal hours = dec(payload.get("hours"));
            s.setHours(hours == null ? computeHours(start, end, cross) : hours);
            s.setStartTime(start);
            s.setEndTime(end);
        }
        s.setUpdateBy(currentUser());
        s.setUpdateDate(LocalDateTime.now());
        shiftDao.updateById(s);
        return s;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrShift toggleStatus(String shiftId, String status) {
        HrShift s = require(shiftId);
        if (!"0".equals(status) && !"1".equals(status)) {
            throw new ServiceException(422, "状态非法（0/1）");
        }
        s.setStatus(status);
        s.setUpdateBy(currentUser());
        s.setUpdateDate(LocalDateTime.now());
        shiftDao.updateById(s);
        return s;
    }

    @Override
    public List<HrShift> list() {
        return shiftDao.selectList(new LambdaQueryWrapper<HrShift>()
                .eq(HrShift::getDelFlag, "0").orderByAsc(HrShift::getShiftNo));
    }

    // ================================================================
    // 排班日历
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSchedule schedule(Map<String, Object> payload) {
        String empId = str(payload.get("empId"));
        LocalDate date = date(payload.get("workDate"));
        require(empId, "员工");
        if (date == null) {
            throw new ServiceException(422, "排班日期必填");
        }
        boolean rest = "1".equals(String.valueOf(payload.get("isRest")));
        String shiftId = str(payload.get("shiftId"));
        if (!rest) {
            require(shiftId, "班次");
            HrShift shift = shiftDao.selectById(shiftId);
            if (shift == null || !"1".equals(shift.getStatus())) {
                throw new ServiceException(422, "班次不存在或已停用");
            }
            // 认证失效门禁（attendance-core 新需求，BR-4.7-06）：关键类别 EXPIRED 拦截上岗排班；
            // 休息日不拦（无上岗语义）；打卡不受影响（门禁只挂排班写入口）
            gateCertScheduling(empId);
        }
        // 同人同日重排：以最后一次为准（更新既有行留痕）
        HrSchedule existing = one(scheduleDao.selectList(new LambdaQueryWrapper<HrSchedule>()
                .eq(HrSchedule::getEmpId, empId)
                .eq(HrSchedule::getWorkDate, date)
                .eq(HrSchedule::getDelFlag, "0")));
        HrSchedule s = existing == null ? new HrSchedule() : existing;
        boolean reschedule = existing != null;
        if (!reschedule) {
            s.setId(uuid());
            s.setCreateBy(currentUser());
            s.setCreateDate(LocalDateTime.now());
        }
        s.setEmpId(empId);
        s.setWorkDate(date);
        s.setShiftId(rest ? null : shiftId);
        s.setIsRest(rest ? "1" : "0");
        s.setSource(str(payload.get("source")) == null ? HrSchedule.SRC_SINGLE : str(payload.get("source")));
        s.setRemark(str(payload.get("remark")));
        s.setUpdateBy(currentUser());
        s.setUpdateDate(LocalDateTime.now());
        if (reschedule) {
            scheduleDao.updateById(s);
            log.info("schedule re-arranged (last-write-wins): {} {} -> {}", empId, date, s.getShiftId());
        } else {
            scheduleDao.insert(s);
        }
        return s;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> bulkSchedule(Map<String, Object> payload) {
        String deptId = str(payload.get("deptId"));
        LocalDate from = date(payload.get("fromDate"));
        LocalDate to = date(payload.get("toDate"));
        String shiftId = str(payload.get("shiftId"));
        boolean rest = "1".equals(String.valueOf(payload.get("isRest")));
        require(deptId, "部门");
        if (from == null || to == null) {
            throw new ServiceException(422, "日期范围必填");
        }
        if (to.isBefore(from)) {
            throw new ServiceException(422, "结束日期不可早于开始日期");
        }
        if (!rest) {
            require(shiftId, "班次");
        }
        List<HrEmployee> emps = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDeptId, deptId)
                .eq(HrEmployee::getDelFlag, "0")
                .in(HrEmployee::getEmpStatus, "PROBATION", "REGULAR"));
        int rows = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (HrEmployee e : emps) {
            LocalDate d = from;
            while (!d.isAfter(to)) {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("empId", e.getId());
                p.put("workDate", d.toString());
                p.put("shiftId", shiftId);
                p.put("isRest", rest ? "1" : "0");
                p.put("source", HrSchedule.SRC_BULK);
                try {
                    schedule(p);
                    rows++;
                } catch (ServiceException ex) {
                    // 门禁拦截等单人失败不中断整批（attendance-core 门禁：成功/失败人数反馈）
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("empId", e.getId());
                    f.put("empName", e.getName());
                    f.put("workDate", d.toString());
                    f.put("reason", ex.getMessage());
                    failed.add(f);
                }
                d = d.plusDays(1);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("empCount", emps.size());
        out.put("rows", rows);
        out.put("failedCount", failed.size());
        out.put("failed", failed);
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> copyMonth(String fromPeriod, String toPeriod) {
        LocalDate fromStart = periodStart(fromPeriod);
        LocalDate toStart = periodStart(toPeriod);
        int days = fromStart.lengthOfMonth();
        int copied = 0;
        int conflicts = 0;
        List<Map<String, Object>> failed = new ArrayList<>();
        for (int day = 1; day <= days; day++) {
            LocalDate fd = fromStart.withDayOfMonth(day);
            LocalDate td = toStart.withDayOfMonth(day > toStart.lengthOfMonth() ? toStart.lengthOfMonth() : day);
            List<HrSchedule> srcs = scheduleDao.selectList(new LambdaQueryWrapper<HrSchedule>()
                    .eq(HrSchedule::getWorkDate, fd)
                    .eq(HrSchedule::getDelFlag, "0"));
            for (HrSchedule src : srcs) {
                boolean exists = scheduleDao.selectCount(new LambdaQueryWrapper<HrSchedule>()
                        .eq(HrSchedule::getEmpId, src.getEmpId())
                        .eq(HrSchedule::getWorkDate, td)
                        .eq(HrSchedule::getDelFlag, "0")) > 0;
                if (exists) {
                    conflicts++;
                }
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("empId", src.getEmpId());
                p.put("workDate", td.toString());
                p.put("shiftId", src.getShiftId());
                p.put("isRest", src.getIsRest());
                p.put("source", HrSchedule.SRC_COPY);
                p.put("remark", "复制自 " + fromPeriod);
                try {
                    schedule(p);
                    copied++;
                } catch (ServiceException ex) {
                    // 认证失效门禁：复制整体不中断，失败计入明细（attendance-core 门禁）
                    HrEmployee e = empDao.selectById(src.getEmpId());
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("empId", src.getEmpId());
                    f.put("empName", e == null ? null : e.getName());
                    f.put("workDate", td.toString());
                    f.put("reason", ex.getMessage());
                    failed.add(f);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("copied", copied);
        out.put("conflicts", conflicts); // L4：同日已有排班被覆盖数
        out.put("failedCount", failed.size());
        out.put("failed", failed);
        return out;
    }

    @Override
    public List<HrSchedule> monthSchedules(String empId, String period) {
        LocalDate start = periodStart(period);
        return scheduleDao.selectList(new LambdaQueryWrapper<HrSchedule>()
                .eq(HrSchedule::getEmpId, empId)
                .ge(HrSchedule::getWorkDate, start)
                .le(HrSchedule::getWorkDate, start.withDayOfMonth(start.lengthOfMonth()))
                .eq(HrSchedule::getDelFlag, "0")
                .orderByAsc(HrSchedule::getWorkDate));
    }

    @Override
    public List<Map<String, Object>> deptMonthMatrix(String deptId, String period) {
        LocalDate start = periodStart(period);
        LocalDate end = start.withDayOfMonth(start.lengthOfMonth());
        List<HrEmployee> emps = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .in(HrEmployee::getEmpStatus, "PROBATION", "REGULAR")
                .orderByAsc(HrEmployee::getEmpNo));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrEmployee e : emps) {
            if (deptId != null && !deptId.isBlank() && !deptId.equals(e.getDeptId())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("empId", e.getId());
            row.put("empNo", e.getEmpNo());
            row.put("name", e.getName());
            row.put("schedules", monthSchedules(e.getId(), period));
            out.add(row);
        }
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 认证失效门禁（BR-4.7-06）：持关键类别 EXPIRED 证书员工 L1 拒绝；无证书/非关键放行 */
    private void gateCertScheduling(String empId) {
        Set<String> blocked = certService.expiredKeyCertEmpIds(List.of(empId));
        if (blocked.contains(empId)) {
            Map<String, Object> desc = certService.expiredKeyCertDesc(empId);
            throw new ServiceException(422,
                    "认证失效限制排班上岗（BR-4.7-06），恢复须重新认证：" + desc.get("desc"));
        }
    }

    private static boolean computeCrossDay(String start, String end) {
        return java.time.LocalTime.parse(end, HM).isBefore(java.time.LocalTime.parse(start, HM));
    }

    private static BigDecimal computeHours(String start, String end, boolean cross) {
        java.time.LocalTime st = java.time.LocalTime.parse(start, HM);
        java.time.LocalTime et = java.time.LocalTime.parse(end, HM);
        long stMin = st.getHour() * 60L + st.getMinute();
        long etMin = et.getHour() * 60L + et.getMinute();
        // 跨夜：24h 制分钟差（LocalTime.plusHours 会回绕，不可用 Duration.between）
        long minutes = cross ? (24 * 60 - stMin) + etMin : etMin - stMin;
        return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
    }

    private static LocalDate periodStart(String period) {
        try {
            return LocalDate.of(Integer.parseInt(period.substring(0, 4)),
                    Integer.parseInt(period.substring(4, 6)), 1);
        } catch (Exception e) {
            throw new ServiceException(422, "期间格式非法（yyyyMM）：" + period);
        }
    }

    private HrShift require(String shiftId) {
        HrShift s = shiftDao.selectById(shiftId);
        if (s == null) {
            throw new ServiceException(404, "班次不存在：" + shiftId);
        }
        return s;
    }

    private String nextNo() {
        List<String> existing = shiftDao.selectNosByPrefix("SHIFT%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.replaceAll("[^0-9]", "")));
            } catch (Exception ignored) {
                // skip
            }
        }
        return String.format("SHIFT%04d", max + 1);
    }

    private static HrSchedule one(List<HrSchedule> list) {
        return list.isEmpty() ? null : list.get(0);
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
            throw new ServiceException(422, "日期格式非法（yyyy-MM-dd）：" + o);
        }
    }

    private static Integer intOrNull(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "整数格式非法：" + o);
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
