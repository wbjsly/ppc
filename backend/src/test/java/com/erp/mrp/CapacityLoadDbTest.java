package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.service.SysParamService;
import com.erp.service.mrp.CapacityLoadService;
import com.erp.service.mrp.CapacityLoadService.LoadRow;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 负荷计算库测（task 3.3，spec capacity-load：75% 正常、非工作日有占用 999、
 * 阈值调整下次查询生效、区间上限 422）。派工单最小手工种子（迁移 122 表直插）。
 */
@SpringBootTest
class CapacityLoadDbTest {

    @Autowired
    private CapacityLoadService service;
    @Autowired
    private SysParamService sysParam;
    @Autowired
    private JdbcTemplate jdbc;

    private LocalDate wed;
    private LocalDate sat;

    @BeforeEach
    void seed() {
        cleanup();
        wed = LocalDate.of(2028, 7, 1).with(java.time.DayOfWeek.WEDNESDAY);
        sat = LocalDate.of(2028, 7, 1).with(java.time.DayOfWeek.SATURDAY);
        sysParam.setValue("CAPACITY_LOAD_TRIGGER", "110", "PERCENT", "MRP", "测试默认", "test");
    }

    @AfterEach
    void tearDown() {
        sysParam.setValue("CAPACITY_LOAD_TRIGGER", "110", "PERCENT", "MRP", "恢复迁移 122 默认", "test");
        cleanup();
    }

    private void cleanup() {
        // 派工单测试种子 + 日历区间残留（物理删：日历有物理唯一键）
        jdbc.update("DELETE FROM erp_mrp_dispatch WHERE WC_CODE LIKE 'LOADWC%'");
        jdbc.update("DELETE FROM erp_admin_work_calendar WHERE CAL_DATE >= '2028-07-01'");
    }

    private void mkDispatch(String id, String wc, LocalDate start, LocalDate end, String hours) {
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, "
                        + "WC_CODE, WC_TYPE, PLAN_START, PLAN_END, DURATION_HOURS, STATUS, CREATE_BY) "
                        + "VALUES (?,?,?,?,?,?,'INTERNAL',?,?,?, 'RELEASED', 'junit')",
                id, "DP-" + id, "mo-load-" + id, "MO-LOAD-1", 10,
                wc, start, end, new BigDecimal(hours));
    }

    private LoadRow rowOf(List<LoadRow> rows, String wc, LocalDate date) {
        return rows.stream()
                .filter(r -> wc.equals(r.wcCode()) && date.equals(r.date()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺行 " + wc + " " + date));
    }

    @Test
    void normalLoadIsSeventyFivePercent() {
        mkDispatch("ld-1", "LOADWC-A", wed, wed, "6"); // 周三 6h / 可用 8h
        List<LoadRow> rows = service.loads(wed, wed, "LOADWC-A");
        LoadRow r = rowOf(rows, "LOADWC-A", wed);
        assertEquals(0, r.assignedHours().compareTo(new BigDecimal("6")), "占用 6h");
        assertEquals(0, r.availableHours().compareTo(new BigDecimal("8")), "可用 8h（DAY 白班）");
        assertEquals(0, r.loadRate().compareTo(new BigDecimal("75.0")), "负荷率 75%");
        assertFalse(r.overLimit(), "75 ≤ 110 不超限");
    }

    @Test
    void weekendWithAssignedLoadIs999() {
        mkDispatch("ld-2", "LOADWC-A", sat, sat, "4"); // 非工作日有占用
        List<LoadRow> rows = service.loads(sat, sat, "LOADWC-A");
        LoadRow r = rowOf(rows, "LOADWC-A", sat);
        assertEquals(0, r.availableHours().compareTo(BigDecimal.ZERO), "非工作日可用 0");
        assertEquals(0, r.loadRate().compareTo(new BigDecimal("999")), "除零保护 999");
        assertTrue(r.overLimit(), "999 超限");
    }

    @Test
    void thresholdChangeTakesEffectNextQuery() {
        mkDispatch("ld-3", "LOADWC-A", wed, wed, "6"); // 75%
        assertEquals(false, rowOf(service.loads(wed, wed, "LOADWC-A"),
                "LOADWC-A", wed).overLimit(), "阈值 110：75 不超限");

        sysParam.setValue("CAPACITY_LOAD_TRIGGER", "50", "PERCENT", "MRP", "测试调低", "test");
        assertTrue(rowOf(service.loads(wed, wed, "LOADWC-A"), "LOADWC-A", wed).overLimit(),
                "阈值调 50 后下次查询即超限（实时读参）");
    }

    @Test
    void rangeGuardRejectsLongSpan() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.loads(LocalDate.of(2028, 1, 1), LocalDate.of(2029, 1, 1), null));
        assertEquals(422, e.getCode(), "区间上限 422：" + e.getMessage());
    }

    @Test
    void spanDispatchProRataAcrossDays() {
        // 8h 摊 [周三, 周四] 两天 → 各 4h（v1 跨日均摊口径）
        mkDispatch("ld-4", "LOADWC-B", wed, wed.plusDays(1), "8");
        List<LoadRow> rows = service.loads(wed, wed.plusDays(1), "LOADWC-B");
        assertEquals(0, rowOf(rows, "LOADWC-B", wed).assignedHours()
                .compareTo(new BigDecimal("4.0000")), "首日摊 4h");
        assertEquals(0, rowOf(rows, "LOADWC-B", wed.plusDays(1)).assignedHours()
                .compareTo(new BigDecimal("4.0000")), "次日摊 4h");
    }
}
