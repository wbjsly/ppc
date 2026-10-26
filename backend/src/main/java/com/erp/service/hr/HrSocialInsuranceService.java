package com.erp.service.hr;

import com.erp.entity.hr.HrSocialPolicy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 社保公积金扣缴（payroll-calculation 10.5.1：属地政策/扣缴计算/七月基数提醒）。 */
public interface HrSocialInsuranceService {

    /** 政策维护（upsert 城市×年度×险种行）。 */
    HrSocialPolicy savePolicy(Map<String, Object> payload);

    /** 政策清单（city/year 过滤）。 */
    List<HrSocialPolicy> policyList(String city, Integer year);

    /** 年度复制（year → year+1，供 7 月基数调整后微调）。 */
    Map<String, Object> copyYear(Integer fromYear, Integer toYear);

    /**
     * 扣缴计算器：按 SOCIAL_PLACE+核算年匹配政策，基数 = MIN(MAX(BASE_SALARY,下限),上限)，
     * 返回 {empTotal, eeTotal, perInsType:[{insType, base, empAmt, eeAmt}]}；
     * 任一险种未配置 → ServiceException 422（L1 硬阻断该员工）。
     */
    Map<String, Object> calc(String empId, int year);

    /**
     * 扣缴计算器（核算口径，social-insurance-ops 衔接）：asOfMonthEnd 非空时反查减员单——
     * 存在 DONE 减员单且 CHANGE_DATE ≤ 月末 → 个人/单位部分按 0，返回 reduced=true；
     * 未办增员单不阻断（计算与申报解耦）。
     */
    Map<String, Object> calc(String empId, int year, java.time.LocalDate asOfMonthEnd);

    /** 6 月扫描：当年政策未维护的城市 → hr_remind（DEMO 城市除外）；返回提醒条数。 */
    int julyBaseAdjustScan();
}
