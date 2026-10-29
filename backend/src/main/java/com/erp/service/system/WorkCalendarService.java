package com.erp.service.system;

import com.erp.entity.mrp.WcShift;
import com.erp.entity.system.ShiftTemplate;
import com.erp.entity.system.WorkCalendar;

import java.time.LocalDate;
import java.util.List;

/**
 * 工作日历与班次（change add-dispatch-management，spec capacity-load：
 * 日历主数据与种子 / 班次模板与工作中心绑定 / 16.11 菜单权限）。
 * 权限：写接口仅 ADMIN（PLANNER 只读，写 403）；读取经安全过滤链（未认证 401）。
 */
public interface WorkCalendarService {

    /** 日期区间日历行（from/to 均含；未维护日期不在结果中，由 Fallback 降级判定） */
    List<WorkCalendar> listDates(LocalDate from, LocalDate to);

    /** 维护单日（upsert：存在则改并留痕操作人，不存在则建）；isWorkday 必须 0/1 */
    WorkCalendar upsertDate(LocalDate date, String isWorkday, String remark);

    /** 班次模板全量 */
    List<ShiftTemplate> listShifts();

    /** 新建班次：编码唯一、工时必须 > 0（422 校验） */
    ShiftTemplate createShift(ShiftTemplate s);

    /** 修改班次（编码不可改；工时必须 > 0） */
    ShiftTemplate updateShift(ShiftTemplate s);

    /** 删除班次：被工作中心引用 MUST 422（spec 被引用禁删） */
    void deleteShift(String id);

    /** 工作中心绑定（wcCode 空 → 全量；否则该 WC 的绑定列表） */
    List<WcShift> listWcShifts(String wcCode);

    /** 绑定/改绑：班次必须存在且启用；该 WC 既有绑定替换为本次（首期单班次） */
    WcShift bindWcShift(String wcCode, String shiftCode);

    /** 解绑 */
    void unbindWcShift(String id);

    // ---------- 法定假日同步与源头维护（close-work-calendar-gaps，spec capacity-load ADDED） ----------

    /**
     * 同步法定假日（HR 假日表 → 工作日历，单向）：STATUTORY/RESTDAY → 非工作日、
     * WORKDAY（调休上班）→ 工作日，备注带假日名；全量映射幂等可重跑，HR 表无记录的
     * 日期不触碰；同步动作写审计留痕。仅 ADMIN。
     *
     * @return 影响行数（实际 upsert 的日历行数）
     */
    int syncHolidays();

    /** HR 假日列表（按日期升序；读取登录即可） */
    List<com.erp.entity.hr.HrHolidayCalendar> listHolidays();

    /** 新增 HR 假日：TYPE 限 STATUTORY/RESTDAY/WORKDAY、日期唯一（违者 422）；仅 ADMIN */
    com.erp.entity.hr.HrHolidayCalendar createHoliday(com.erp.entity.hr.HrHolidayCalendar h);

    /** 修改 HR 假日（日期不可改；校验同新增）；仅 ADMIN */
    com.erp.entity.hr.HrHolidayCalendar updateHoliday(com.erp.entity.hr.HrHolidayCalendar h);

    /** 删除 HR 假日（物理删——源头维护语义，无下游引用占位）；仅 ADMIN */
    void deleteHoliday(String id);

    // ---------- 排程/负荷共用的时段展开（capacity-load spec Fallback 与班次绑定需求） ----------

    /**
     * 某日是否可用（工作日判定）：日历行优先（以行为准）；无行 → 按参数
     * KITTING_CALENDAR_FALLBACK：WEEKDAY（默认，周一~五可用/周末不可用）/ DISABLED（显式 422 报错）。
     */
    boolean isWorkday(LocalDate date);

    /**
     * 某日某工作中心的可用时段（班次模板展开，含时刻与净工时）。
     * 非工作日 → 空列表；绑定优先、无绑定默认白班（DAY 模板）。
     */
    List<Slot> slotsOf(LocalDate date, String wcCode);

    /** 某日某工作中心可用工时合计（非工作日 0；负荷率分母） */
    java.math.BigDecimal availableHours(LocalDate date, String wcCode);

    /** 排程时段（日期、班次、起止时刻、净工时） */
    record Slot(java.time.LocalDate date, String shiftCode, String start, String end,
                java.math.BigDecimal hours) {
    }
}
