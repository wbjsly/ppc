package com.erp.service.hr;

import com.erp.entity.hr.HrMakeupPunch;

import java.util.List;
import java.util.Map;

/** 补卡申请（BR-4.7-10：员工提交→主管确认→HR 审核→写 MANUAL 打卡+重识别）。 */
public interface HrMakeupPunchService {

    /** 提交补卡（日期/时段方向/原因；期限 MAKEUP_DEADLINE_DAYS）。 */
    HrMakeupPunch submit(Map<String, Object> payload);

    /** 主管确认（通过/驳回）。 */
    HrMakeupPunch mgrConfirm(String makeupId, Map<String, Object> payload);

    /**
     * HR 审核：通过→写来源 MANUAL 打卡记录 + 关闭该日对应缺卡/迟到异常 + 触发当日重识别；
     * 驳回→置 REJECTED。
     */
    HrMakeupPunch hrReview(String makeupId, Map<String, Object> payload);

    /** 补卡清单（按员工/状态）。 */
    List<HrMakeupPunch> list(String empId, String status);
}
