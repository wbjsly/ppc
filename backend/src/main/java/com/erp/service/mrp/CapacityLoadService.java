package com.erp.service.mrp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 产能负荷计算（change add-dispatch-management，spec capacity-load「负荷率计算/产能超限提示」，
 * design D8：实时聚合免预聚合落表，5.6.2 与 5.6.3 共用同一读口径）。
 *
 * 负荷率 = 已确认（RELEASED）派工占用工时 ÷ 日历班次可用工时 × 100%；
 * 分配口径 v1（design 记录）：派工单 DURATION_HOURS 按 [PLAN_START, PLAN_END] 跨日均摊
 * （含非工作日——保证非工作日有占用时能算出 999，spec 场景）；无可用时段且有占用 → 999（除零保护）。
 * 超限判定读参数 CAPACITY_LOAD_TRIGGER（默认 110，每次查询实时读取——调整后下次查询生效）。
 */
public interface CapacityLoadService {

    /**
     * WC × 日期负荷行（日期升序、WC 升序）。
     *
     * @param from/to 日期区间（均含）；区间上限 186 天（422 防爆炸）
     * @param wcCode  空 → 区间内有派工单的工作中心（去重）；非空 → 仅该 WC（可无派工单，出 0 负荷行）
     */
    List<LoadRow> loads(LocalDate from, LocalDate to, String wcCode);

    /** 负荷行（assignedHours 已分配 / availableHours 可用 / loadRate 百分比 / overLimit 超限标红） */
    record LoadRow(String wcCode, LocalDate date, BigDecimal assignedHours,
                   BigDecimal availableHours, BigDecimal loadRate, boolean overLimit) {
    }
}
