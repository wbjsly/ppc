package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 建档六步状态机（FR-4.7-1-1~6，外部核验离线留桩）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_onboarding")
public class HrOnboarding extends BaseEntity {

    public static final String ST_IN_PROGRESS = "IN_PROGRESS";
    public static final String ST_RETURNED = "RETURNED";
    public static final String ST_DONE = "DONE";

    /** 建档单号 ONB+流水 */
    private String onbNo;
    /** 当前步骤 1~6 */
    private Integer step;
    /** IN_PROGRESS / RETURNED / DONE */
    private String status;
    /** 第⑤步生成的主档 ID */
    private String empId;

    // ①录用单接收
    private String offerNo;
    /** 录用单已审批通过 1/0（门禁） */
    private String offerApproved;

    // ②信息登记（人工核验留桩）
    private String candidateJson;
    private String realnameCheck;
    private String realnameBy;
    private LocalDateTime realnameAt;
    private String bank4Check;

    // ③证件核验（离职证明必备 L1）
    private String docsJson;
    private String docsVerified;

    // ④合同签订
    private String contractJson;
    private String contractOk;

    // ⑥社保增员留桩
    private String socialJson;
    private String socialDone;
}
