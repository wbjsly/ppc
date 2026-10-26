package com.erp.service.hr;

import com.erp.entity.hr.HrLeaveApply;

import java.util.List;
import java.util.Map;

/** 请假单（M10.4.4，leave-management：余额校验 L1/排班日豁免/审批扣减/驳回回滚/扣款入汇总）。 */
public interface HrLeaveApplyService {

    /**
     * 提交请假（类型/起止/时长/事由/代理人；余额类假期校验余额充足 L1；
     * 婚/产/陪产/丧假校验法定天数上限；非余额类直接登记）。
     */
    HrLeaveApply submit(Map<String, Object> payload);

    /** 审批（通过→扣减余额批次+固化扣款；驳回→回滚留痕）。 */
    HrLeaveApply approve(String applyId, Map<String, Object> payload);

    /** 取消（已批未休更正驳回：扣减回滚+占用释放留痕）。 */
    HrLeaveApply cancel(String applyId, String reason);

    /** 请假清单。 */
    List<HrLeaveApply> list(String empId, String status);
}
