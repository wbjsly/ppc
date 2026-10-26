package com.erp.service.hr;

import com.erp.entity.hr.HrSocialChange;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 社保增减员/转移单（social-insurance-ops）：
 * 事件自动开单三触点（入职过审→ADD / 离职结算→REMOVE / 调岗生效→TRANSFER）+ 手工补开 + 申报登记。
 */
public interface HrSocialChangeService {

    /**
     * 自动开单（人事事件触发）。同员工同类型存在 DRAFT/DECLARED 在途单 → 防重跳过（返回 null）。
     * 联动失败不回滚人事事务（调用方 try-catch）。
     */
    HrSocialChange autoOpen(String empId, String chgType, String srcType, String srcId,
                            String targetPlace, LocalDate changeDate);

    /** HR 手工补开（srcType=MANUAL，覆盖漏办与特殊场景）。 */
    HrSocialChange manualOpen(Map<String, Object> payload);

    /** 申报登记：记录申报日期/回执号/经办人，DRAFT（或 FAILED 重试）→ DONE。 */
    HrSocialChange declare(String chgId, Map<String, Object> payload);

    /** 作废（DRAFT/FAILED/DECLARED → VOID 留痕，可重开）。 */
    HrSocialChange voidChange(String chgId, String note);

    /** 单据清单。 */
    List<HrSocialChange> list(String empId, String chgType, String status);

    /** 员工是否存在指定类型 DONE 单（减员生效判断，payroll-calculation 消费）。 */
    boolean hasDoneChange(String empId, String chgType, LocalDate asOfMonthEnd);

    /** 单据详情。 */
    Map<String, Object> detail(String chgId);
}
