package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 电子工资条（审批过生成推送；确认非强制留痕；异议→核算员 3 工作日回复）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_payslip")
public class HrPayslip extends BaseEntity {
    private String runId;
    private String itemId;
    private String period;
    private String empId;
    /** 工资条快照 JSON（应发/扣款分项） */
    private String detailJson;
    /** 员工确认（非强制） */
    private LocalDateTime confirmedAt;
    private String disputeContent;
    private LocalDateTime disputeAt;
    private String replyContent;
    private LocalDateTime replyAt;
    private String replyBy;
}
