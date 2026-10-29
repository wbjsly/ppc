package com.erp.service.impl.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.WcShiftDao;
import com.erp.dao.system.ShiftTemplateDao;
import com.erp.dao.system.WorkCalendarDao;
import com.erp.entity.mrp.WcShift;
import com.erp.entity.system.ShiftTemplate;
import com.erp.entity.system.WorkCalendar;
import com.erp.service.SysParamService;
import com.erp.service.system.WorkCalendarService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作日历与班次实现（change add-dispatch-management，spec capacity-load ADDED 需求）。
 * 写操作仅 ADMIN：requireRole 不带放行角色 → 非 ADMIN 一律 403（含 PLANNER 只读账户）。
 * 时段展开（isWorkday/slotsOf/availableHours）为排程（D3）与负荷（D8）共用读口径。
 */
@Slf4j
@Service
public class WorkCalendarServiceImpl implements WorkCalendarService {

    /** 无 WC 绑定时的默认班次编码（迁移 122 种子白班） */
    private static final String DEFAULT_SHIFT = "DAY";

    private final WorkCalendarDao calendarDao;
    private final ShiftTemplateDao shiftDao;
    private final WcShiftDao wcShiftDao;
    private final SysParamService sysParam;
    private final com.erp.dao.hr.HrHolidayCalendarDao holidayDao;
    private final com.erp.security.AuditLogWriter auditLogWriter;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public WorkCalendarServiceImpl(WorkCalendarDao calendarDao, ShiftTemplateDao shiftDao,
                                   WcShiftDao wcShiftDao, SysParamService sysParam,
                                   com.erp.dao.hr.HrHolidayCalendarDao holidayDao,
                                   com.erp.security.AuditLogWriter auditLogWriter,
                                   org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.calendarDao = calendarDao;
        this.shiftDao = shiftDao;
        this.wcShiftDao = wcShiftDao;
        this.sysParam = sysParam;
        this.holidayDao = holidayDao;
        this.auditLogWriter = auditLogWriter;
        this.jdbc = jdbc;
    }

    @Override
    public List<WorkCalendar> listDates(LocalDate from, LocalDate to) {
        LambdaQueryWrapper<WorkCalendar> qw = new LambdaQueryWrapper<WorkCalendar>()
                .ge(from != null, WorkCalendar::getCalDate, from)
                .le(to != null, WorkCalendar::getCalDate, to)
                .orderByAsc(WorkCalendar::getCalDate);
        return calendarDao.selectList(qw);
    }

    @Override
    @Transactional
    public WorkCalendar upsertDate(LocalDate date, String isWorkday, String remark) {
        requireAdmin("维护工作日历");
        if (date == null) {
            throw new ServiceException(422, "日历日期必填");
        }
        if (!"0".equals(isWorkday) && !"1".equals(isWorkday)) {
            throw new ServiceException(422, "是否工作日仅接受 0/1：" + isWorkday);
        }
        WorkCalendar exist = calendarDao.selectOne(new LambdaQueryWrapper<WorkCalendar>()
                .eq(WorkCalendar::getCalDate, date));
        String operator = SecurityUtils.getCurrentUserId();
        if (exist != null) {
            exist.setIsWorkday(isWorkday);
            exist.setRemark(remark);
            exist.setUpdateBy(operator);
            calendarDao.updateById(exist); // @Version verNo 自增（留痕）
            log.info("日历更新 {} workday={} by {}", date, isWorkday, operator);
            return exist;
        }
        WorkCalendar row = new WorkCalendar();
        row.setCalDate(date);
        row.setIsWorkday(isWorkday);
        row.setRemark(remark);
        row.setCreateBy(operator);
        row.setUpdateBy(operator);
        calendarDao.insert(row);
        log.info("日历新建 {} workday={} by {}", date, isWorkday, operator);
        return row;
    }

    @Override
    public List<ShiftTemplate> listShifts() {
        return shiftDao.selectList(new LambdaQueryWrapper<ShiftTemplate>()
                .orderByAsc(ShiftTemplate::getShiftCode));
    }

    @Override
    @Transactional
    public ShiftTemplate createShift(ShiftTemplate s) {
        requireAdmin("维护班次模板");
        validate(s, true);
        if (shiftDao.selectCount(new LambdaQueryWrapper<ShiftTemplate>()
                .eq(ShiftTemplate::getShiftCode, s.getShiftCode().trim())) > 0) {
            throw new ServiceException(422, "班次编码已存在：" + s.getShiftCode());
        }
        s.setId(null);
        s.setStatus(s.getStatus() == null || s.getStatus().isEmpty() ? "1" : s.getStatus());
        s.setCreateBy(SecurityUtils.getCurrentUserId());
        s.setUpdateBy(SecurityUtils.getCurrentUserId());
        try {
            shiftDao.insert(s);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 唯一键兜底（194 迁移）：逻辑删行占码时 selectCount（带 DEL_FLAG='0'）查不到，
            // insert 撞 UK_ADMIN_SHIFT_CODE —— 统一转 422 与前置校验同口径
            throw new ServiceException(422, "班次编码已存在：" + s.getShiftCode());
        }
        return s;
    }

    @Override
    @Transactional
    public ShiftTemplate updateShift(ShiftTemplate s) {
        requireAdmin("维护班次模板");
        validate(s, false);
        ShiftTemplate old = shiftDao.selectById(s.getId());
        if (old == null) {
            throw new ServiceException(404, "班次不存在：" + s.getId());
        }
        // 班次编码创建后不可改（主数据治理基线）：忽略入参编码，保留原值
        s.setShiftCode(old.getShiftCode());
        s.setUpdateBy(SecurityUtils.getCurrentUserId());
        shiftDao.updateById(s);
        return shiftDao.selectById(s.getId());
    }

    @Override
    @Transactional
    public void deleteShift(String id) {
        requireAdmin("维护班次模板");
        ShiftTemplate s = shiftDao.selectById(id);
        if (s == null) {
            throw new ServiceException(404, "班次不存在：" + id);
        }
        long refs = wcShiftDao.selectCount(new LambdaQueryWrapper<WcShift>()
                .eq(WcShift::getShiftCode, s.getShiftCode()));
        if (refs > 0) {
            // spec capacity-load：被工作中心引用的班次模板 MUST 禁止删除
            throw new ServiceException(422, "班次模板被 " + refs
                    + " 个工作中心引用，禁止删除，请先解绑");
        }
        shiftDao.deleteById(id);
        log.info("班次删除 {} {} by {}", id, s.getShiftCode(), SecurityUtils.getCurrentUserId());
    }

    @Override
    public List<WcShift> listWcShifts(String wcCode) {
        return wcShiftDao.selectList(new LambdaQueryWrapper<WcShift>()
                .eq(wcCode != null && !wcCode.isEmpty(), WcShift::getWcCode, wcCode)
                .orderByAsc(WcShift::getWcCode));
    }

    @Override
    @Transactional
    public WcShift bindWcShift(String wcCode, String shiftCode) {
        requireAdmin("维护工作中心班次绑定");
        if (wcCode == null || wcCode.isEmpty()) {
            throw new ServiceException(422, "工作中心编码必填");
        }
        ShiftTemplate shift = shiftDao.selectOne(new LambdaQueryWrapper<ShiftTemplate>()
                .eq(ShiftTemplate::getShiftCode, shiftCode));
        if (shift == null) {
            throw new ServiceException(422, "班次模板不存在：" + shiftCode);
        }
        if (!"1".equals(shift.getStatus())) {
            throw new ServiceException(422, "班次模板已停用：" + shiftCode);
        }
        // 首期单班次绑定：替换该 WC 既有绑定（design D3 数据结构支持多班次，交互先单选）
        wcShiftDao.delete(new LambdaQueryWrapper<WcShift>().eq(WcShift::getWcCode, wcCode));
        WcShift row = new WcShift();
        row.setWcCode(wcCode);
        row.setShiftCode(shiftCode);
        row.setCreateBy(SecurityUtils.getCurrentUserId());
        row.setUpdateBy(SecurityUtils.getCurrentUserId());
        wcShiftDao.insert(row);
        return row;
    }

    @Override
    @Transactional
    public void unbindWcShift(String id) {
        requireAdmin("维护工作中心班次绑定");
        wcShiftDao.deleteById(id);
    }

    // ---------- 法定假日同步与源头维护（close-work-calendar-gaps，spec capacity-load ADDED） ----------

    @Override
    @Transactional
    public int syncHolidays() {
        requireAdmin("同步法定假日");
        List<com.erp.entity.hr.HrHolidayCalendar> holidays = holidayDao.selectList(
                new LambdaQueryWrapper<com.erp.entity.hr.HrHolidayCalendar>()
                        .orderByAsc(com.erp.entity.hr.HrHolidayCalendar::getHolidayDate));
        String operator = SecurityUtils.getCurrentUserId();
        int affected = 0;
        for (com.erp.entity.hr.HrHolidayCalendar h : holidays) {
            // STATUTORY/RESTDAY → 非工作日；WORKDAY（调休上班）→ 工作日（design D1 映射）
            boolean workday = com.erp.entity.hr.HrHolidayCalendar.TYPE_WORKDAY.equals(h.getType());
            String remark = h.getName() + "（" + typeLabel(h.getType()) + "）";
            WorkCalendar exist = calendarDao.selectOne(new LambdaQueryWrapper<WorkCalendar>()
                    .eq(WorkCalendar::getCalDate, h.getHolidayDate()));
            if (exist != null) {
                if (!exist.getIsWorkday().equals(workday ? "1" : "0")
                        || !remark.equals(exist.getRemark())) {
                    exist.setIsWorkday(workday ? "1" : "0");
                    exist.setRemark(remark);
                    exist.setUpdateBy(operator);
                    calendarDao.updateById(exist);
                    affected++;
                }
            } else {
                WorkCalendar row = new WorkCalendar();
                row.setCalDate(h.getHolidayDate());
                row.setIsWorkday(workday ? "1" : "0");
                row.setRemark(remark);
                row.setCreateBy(operator);
                row.setUpdateBy(operator);
                calendarDao.insert(row);
                affected++;
            }
        }
        // 同步动作留痕（spec：同步动作留痕操作人与时间）
        auditLogWriter.write("hr-holiday", "HOLIDAY_SYNC", null,
                "同步 " + holidays.size() + " 条假日，变更 " + affected + " 行",
                "HR 法定假日 → 工作日历单向同步");
        log.info("法定假日同步：HR {} 条 → 工作日历变更 {} 行 by {}", holidays.size(), affected, operator);
        return affected;
    }

    private static String typeLabel(String type) {
        return switch (type == null ? "" : type) {
            case com.erp.entity.hr.HrHolidayCalendar.TYPE_STATUTORY -> "法定假";
            case com.erp.entity.hr.HrHolidayCalendar.TYPE_RESTDAY -> "休息日";
            case com.erp.entity.hr.HrHolidayCalendar.TYPE_WORKDAY -> "调休上班";
            default -> type;
        };
    }

    @Override
    public List<com.erp.entity.hr.HrHolidayCalendar> listHolidays() {
        return holidayDao.selectList(new LambdaQueryWrapper<com.erp.entity.hr.HrHolidayCalendar>()
                .orderByAsc(com.erp.entity.hr.HrHolidayCalendar::getHolidayDate));
    }

    @Override
    @Transactional
    public com.erp.entity.hr.HrHolidayCalendar createHoliday(
            com.erp.entity.hr.HrHolidayCalendar h) {
        requireAdmin("维护法定假日");
        validateHoliday(h, true);
        if (holidayDao.selectByDate(h.getHolidayDate()) != null) {
            throw new ServiceException(422, "假日日期已存在：" + h.getHolidayDate());
        }
        h.setId(null);
        h.setCreateBy(SecurityUtils.getCurrentUserId());
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        try {
            holidayDao.insert(h);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // UK_HR_HOLIDAY_DATE 兜底（与前置校验同口径）
            throw new ServiceException(422, "假日日期已存在：" + h.getHolidayDate());
        }
        return h;
    }

    @Override
    @Transactional
    public com.erp.entity.hr.HrHolidayCalendar updateHoliday(
            com.erp.entity.hr.HrHolidayCalendar h) {
        requireAdmin("维护法定假日");
        com.erp.entity.hr.HrHolidayCalendar old = holidayDao.selectById(h.getId());
        if (old == null) {
            throw new ServiceException(404, "假日记录不存在：" + h.getId());
        }
        h.setHolidayDate(old.getHolidayDate()); // 日期不可改（主数据治理基线）
        validateHoliday(h, false);
        h.setUpdateBy(SecurityUtils.getCurrentUserId());
        holidayDao.updateById(h);
        return holidayDao.selectById(h.getId());
    }

    @Override
    @Transactional
    public void deleteHoliday(String id) {
        requireAdmin("维护法定假日");
        com.erp.entity.hr.HrHolidayCalendar old = holidayDao.selectById(id);
        if (old == null) {
            throw new ServiceException(404, "假日记录不存在：" + id);
        }
        // 物理删：UK_HR_HOLIDAY_DATE 不区分逻辑删——墓碑占日期会让同日重建撞唯一键
        jdbc.update("DELETE FROM hr_holiday_calendar WHERE ID = ?", id);
        log.info("法定假日删除 {} {}（{}）by {}", id, old.getHolidayDate(), old.getName(),
                SecurityUtils.getCurrentUserId());
    }

    private void validateHoliday(com.erp.entity.hr.HrHolidayCalendar h, boolean create) {
        if (create && h.getHolidayDate() == null) {
            throw new ServiceException(422, "假日日期必填");
        }
        if (h.getName() == null || h.getName().trim().isEmpty()) {
            throw new ServiceException(422, "假日名称必填");
        }
        String type = h.getType();
        if (!com.erp.entity.hr.HrHolidayCalendar.TYPE_STATUTORY.equals(type)
                && !com.erp.entity.hr.HrHolidayCalendar.TYPE_RESTDAY.equals(type)
                && !com.erp.entity.hr.HrHolidayCalendar.TYPE_WORKDAY.equals(type)) {
            // spec：TYPE 限三值
            throw new ServiceException(422, "假日类型仅接受 STATUTORY/RESTDAY/WORKDAY：" + type);
        }
    }

    // ---------- 排程/负荷共用读口径（capacity-load spec：FALLBACK 降级 + 班次展开） ----------

    @Override
    public boolean isWorkday(LocalDate date) {
        WorkCalendar row = calendarDao.selectOne(new LambdaQueryWrapper<WorkCalendar>()
                .eq(WorkCalendar::getCalDate, date));
        if (row != null) {
            return "1".equals(row.getIsWorkday()); // 日历行优先（以行为准）
        }
        String mode = sysParam.getValue("KITTING_CALENDAR_FALLBACK", "WEEKDAY");
        if ("DISABLED".equalsIgnoreCase(mode)) {
            // spec：无日历行显式报错，禁止静默全可用/全不可用
            throw new ServiceException(422, "日期 " + date + " 无日历记录且降级档为 DISABLED，"
                    + "请先在 16.11 工作日历补维护");
        }
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY; // WEEKDAY（默认与未知档）
    }

    @Override
    public List<Slot> slotsOf(LocalDate date, String wcCode) {
        List<Slot> out = new ArrayList<>();
        if (!isWorkday(date)) {
            return out;
        }
        for (ShiftTemplate s : shiftTemplatesOf(wcCode)) {
            out.add(new Slot(date, s.getShiftCode(), s.getStartTime(), s.getEndTime(),
                    s.getHours()));
        }
        return out;
    }

    @Override
    public BigDecimal availableHours(LocalDate date, String wcCode) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Slot s : slotsOf(date, wcCode)) {
            sum = sum.add(s.hours());
        }
        return sum;
    }

    /** WC 绑定班次（首期单班次；无绑定 → 默认白班 DAY） */
    private List<ShiftTemplate> shiftTemplatesOf(String wcCode) {
        List<String> codes = new ArrayList<>();
        if (wcCode != null && !wcCode.isEmpty()) {
            for (WcShift b : wcShiftDao.selectList(new LambdaQueryWrapper<WcShift>()
                    .eq(WcShift::getWcCode, wcCode))) {
                codes.add(b.getShiftCode());
            }
        }
        if (codes.isEmpty()) {
            codes.add(DEFAULT_SHIFT);
        }
        List<ShiftTemplate> out = shiftDao.selectList(new LambdaQueryWrapper<ShiftTemplate>()
                .in(ShiftTemplate::getShiftCode, codes)
                .eq(ShiftTemplate::getStatus, "1"));
        if (out.isEmpty()) {
            throw new ServiceException(422, "班次模板缺失（" + String.join(",", codes)
                    + "），请先在 16.11.2 维护");
        }
        return out;
    }

    // ---------- helpers ----------

    private void validate(ShiftTemplate s, boolean create) {
        if (create && (s.getShiftCode() == null || s.getShiftCode().trim().isEmpty())) {
            throw new ServiceException(422, "班次编码必填");
        }
        if (s.getShiftName() == null || s.getShiftName().trim().isEmpty()) {
            throw new ServiceException(422, "班次名称必填");
        }
        if (s.getStartTime() == null || s.getEndTime() == null) {
            throw new ServiceException(422, "班次起止时刻必填");
        }
        if (s.getHours() == null || s.getHours().signum() <= 0) {
            // spec capacity-load：班次工时 MUST > 0
            throw new ServiceException(422, "班次工时必须大于 0");
        }
    }

    /** 写操作仅 ADMIN（无放行角色 → 非 ADMIN 403） */
    private void requireAdmin(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean admin = auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equalsIgnoreCase(a.getAuthority()));
        if (!admin) {
            throw new ServiceException(403, "当前角色无权" + action + "（仅管理员）");
        }
    }
}
