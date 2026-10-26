package com.erp.service.hr;

import com.erp.entity.hr.HrOvertimeConfirm;

import java.util.List;
import java.util.Map;

/** 加班确认与分流（M10-4，overtime-comp-time D4：MIN 认定/EXCESS 追认/分流金额固化/试算）。 */
public interface HrOvertimeConfirmService {

    /**
     * 确认扫描（对 APPROVED 且日期已过的申请）：有效时长 = MIN(申请, 打卡)；
     * 打卡超申请超 OT_EXCESS_TOLERANCE_MIN → EXCESS 待追认；
     * 无申请打卡不认定（BR-4.7-16）；倍数消费假日日历（HOLIDAY 3x/WEEKEND 2x/WEEKDAY 1.5x）。
     * 返回新建确认数。
     */
    int confirmScan();

    /** 追认 EXCESS（主管确认按申请时长或批准追认时长）。 */
    HrOvertimeConfirm resolveExcess(String confirmId, Map<String, Object> payload);

    /**
     * 分流（HOLIDAY/WEEKDAY 强制 WAGE；WEEKEND 二选一默认 COMP_TIME；
     * WAGE 需主档 BASE_SALARY 否则 422；固化 HOURLY_BASE 与 WAGE_AMOUNT）。
     */
    HrOvertimeConfirm split(String confirmId, Map<String, Object> payload);

    /** 实时试算（不落库）：N 小时×倍数 → 工资金额 vs 等值调休（含预计到期日）。 */
    Map<String, Object> trial(String confirmId);

    /** 确认清单。 */
    List<HrOvertimeConfirm> list(String empId, String status);
}
