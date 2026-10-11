package com.erp.system;

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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作日历/班次库测（task 3.1，spec capacity-load：日历 upsert 留痕、班次工时>0、
 * 被引用禁删、PLANNER 写 403 / ADMIN 写通过）。
 */
@SpringBootTest
class CapacityCalendarDbTest {

    @Autowired
    private WorkCalendarService service;
    @Autowired
    private WorkCalendarDao calendarDao;
    @Autowired
    private ShiftTemplateDao shiftDao;
    @Autowired
    private WcShiftDao wcShiftDao;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SysParamService sysParam;

    private static final LocalDate D1 = LocalDate.of(2028, 6, 15); // 种子范围外的测试日期

    @BeforeEach
    void loginAdmin() {
        cleanup();
        SystemAuth.login("cal-admin", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        // 恢复迁移 122 默认降级档（本类测试会切换）
        sysParam.setValue("KITTING_CALENDAR_FALLBACK", "WEEKDAY", "ENUM", "MRP", "测试恢复默认", "test");
        SystemAuth.logout();
        cleanup();
    }

    private void cleanup() {
        // 日历保留物理唯一键（生产只有 upsert 无删除路径）→ 测试清理必须物理删，
        // 否则逻辑删墓碑会撞 UK_ADMIN_CAL_DATE
        jdbc.update("DELETE FROM erp_admin_work_calendar WHERE CAL_DATE >= ?", D1);
        wcShiftDao.delete(new LambdaQueryWrapper<WcShift>()
                .like(WcShift::getWcCode, "CALWC"));
        shiftDao.delete(new LambdaQueryWrapper<ShiftTemplate>()
                .like(ShiftTemplate::getShiftCode, "CALSH"));
    }

    private ShiftTemplate shift(String code, String hours) {
        ShiftTemplate s = new ShiftTemplate();
        s.setShiftCode(code);
        s.setShiftName("测试班次 " + code);
        s.setStartTime("08:00");
        s.setEndTime("17:00");
        s.setHours(new BigDecimal(hours));
        return s;
    }

    @Test
    void upsertDateCreatesAndUpdatesWithAudit() {
        WorkCalendar created = service.upsertDate(D1, "1", "测试工作日");
        assertEquals("1", created.getIsWorkday());
        assertEquals("cal-admin", created.getCreateBy(), "创建留痕操作人");

        WorkCalendar updated = service.upsertDate(D1, "0", "临时调休");
        assertEquals("0", updated.getIsWorkday(), "upsert 修改生效");
        assertEquals("临时调休", updated.getRemark());
        assertTrue(updated.getVerNo() >= 1, "修改后 verNo 自增（乐观锁留痕）");
        assertEquals(1, calendarDao.selectList(new LambdaQueryWrapper<WorkCalendar>()
                .eq(WorkCalendar::getCalDate, D1)).size(), "同日期唯一行（upsert 不重复建）");
    }

    @Test
    void shiftHoursMustBePositiveAndCodeUnique() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.createShift(shift("CALSH-ZERO", "0")));
        assertEquals(422, e.getCode(), "工时 0 → 422：" + e.getMessage());

        service.createShift(shift("CALSH-A", "8"));
        ServiceException dup = assertThrows(ServiceException.class,
                () -> service.createShift(shift("CALSH-A", "8")));
        assertEquals(422, dup.getCode(), "编码重复 → 422");
    }

    @Test
    void referencedShiftCannotBeDeleted() {
        service.createShift(shift("CALSH-REF", "8"));
        service.bindWcShift("CALWC-1", "CALSH-REF");

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.deleteShift(shiftDao.selectOne(
                        new LambdaQueryWrapper<ShiftTemplate>()
                                .eq(ShiftTemplate::getShiftCode, "CALSH-REF")).getId()));
        assertEquals(422, e.getCode(), "被引用禁删 → 422");
        assertTrue(e.getMessage().contains("引用"), "提示含引用信息：" + e.getMessage());

        // 解绑后可删
        List<WcShift> binds = service.listWcShifts("CALWC-1");
        assertEquals(1, binds.size());
        service.unbindWcShift(binds.get(0).getId());
        service.deleteShift(shiftDao.selectOne(new LambdaQueryWrapper<ShiftTemplate>()
                .eq(ShiftTemplate::getShiftCode, "CALSH-REF")).getId());
        assertTrue(shiftDao.selectList(new LambdaQueryWrapper<ShiftTemplate>()
                .eq(ShiftTemplate::getShiftCode, "CALSH-REF")).isEmpty(), "解绑后删除成功");
    }

    @Test
    void plannerReadOnlyWriteForbidden() {
        // 种子日期（管理员建）→ PLANNER 读可见
        service.upsertDate(D1, "1", "读测试");
        SystemAuth.logout();
        SystemAuth.login("cal-planner", "ROLE_PLANNER");
        assertNotNull(service.listDates(D1, D1), "PLANNER 可读");
        assertEquals(1, service.listDates(D1, D1).size(), "PLANNER 读到行");

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.upsertDate(D1, "0", "越权写"));
        assertEquals(403, e.getCode(), "PLANNER 写日历 → 403");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.createShift(shift("CALSH-X", "8")));
        assertEquals(403, e2.getCode(), "PLANNER 写班次 → 403");
    }

    @Test
    void fallbackModesAndSlotExpansion() {
        // 动态取 2028-07 的周三/周六（种子范围外、D1 清理范围内）
        LocalDate wed = LocalDate.of(2028, 7, 1).with(java.time.DayOfWeek.WEDNESDAY);
        LocalDate sat = LocalDate.of(2028, 7, 1).with(java.time.DayOfWeek.SATURDAY);
        LocalDate fresh = LocalDate.of(2028, 7, 20); // 无行日期（DISABLED 用例）

        // ① WEEKDAY 档（默认）：缺行周三可用 → 班次展开 8h；缺行周六不可用 → 空
        sysParam.setValue("KITTING_CALENDAR_FALLBACK", "WEEKDAY", "ENUM", "MRP", "测试切换", "test");
        assertTrue(service.isWorkday(wed), "缺行周三按工作日");
        assertEquals(1, service.slotsOf(wed, "MOWC-ANY").size(), "周三展开白班");
        assertEquals(0, new BigDecimal("8.00").compareTo(
                service.availableHours(wed, "MOWC-ANY")), "周三可用 8h（DAY 默认模板）");
        assertTrue(!service.isWorkday(sat), "缺行周六按非工作日");
        assertTrue(service.slotsOf(sat, "MOWC-ANY").isEmpty(), "周六无时段");
        assertEquals(0, service.availableHours(sat, "MOWC-ANY").compareTo(BigDecimal.ZERO),
                "周六可用 0h");

        // ② 日历行优先（以行为准）：周三标休 → 无时段；周六标班 → 展开
        service.upsertDate(wed, "0", "临时休息");
        assertTrue(!service.isWorkday(wed), "行覆盖 WEEKDAY 推断");
        assertTrue(service.slotsOf(wed, "MOWC-ANY").isEmpty(), "标休周三无时段");
        service.upsertDate(sat, "1", "临时加班");
        assertTrue(service.isWorkday(sat), "周六行标班可用");
        assertEquals(1, service.slotsOf(sat, "MOWC-ANY").size(), "加班周六展开白班");

        // ③ DISABLED 档：缺行显式 422；有行则以行为准（DISABLED 只管缺行）
        sysParam.setValue("KITTING_CALENDAR_FALLBACK", "DISABLED", "ENUM", "MRP", "测试切换", "test");
        ServiceException e = assertThrows(ServiceException.class, () -> service.isWorkday(fresh));
        assertEquals(422, e.getCode(), "DISABLED 缺行 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("16.11"), "提示指向 16.11 维护");
        service.upsertDate(fresh, "1", "补维护");
        assertTrue(service.isWorkday(fresh), "有行时 DISABLED 不报错（行优先）");

        // ④ WC 绑定班次优先于默认白班（先把周三恢复为工作日）
        service.upsertDate(wed, "1", "恢复工作日");
        service.createShift(shift("CALSH-NIGHT", "8"));
        service.bindWcShift("CALWC-2", "CALSH-NIGHT");
        List<com.erp.service.system.WorkCalendarService.Slot> slots =
                service.slotsOf(wed, "CALWC-2");
        assertEquals(1, slots.size());
        assertEquals("CALSH-NIGHT", slots.get(0).shiftCode(), "绑定班次优先于默认白班展开");
    }
}
