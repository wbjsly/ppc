package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 协议投诉登记/清零留痕（spec customer-quality-agreement 协议维护与要素校验）。
 */
@Getter
@Setter
@TableName("erp_qms_agreement_log")
public class AgreementLog extends BaseEntity {

    @TableField("AGREEMENT_ID")
    private String agreementId;

    /** COMPLAINT（计数 +1）/ CLEAR（清零） */
    @TableField("ACTION")
    private String action;

    /** 动作后计数值 */
    @TableField("STREAK_AFTER")
    private Integer streakAfter;

    /** 原因（投诉登记必填） */
    @TableField("REASON")
    private String reason;
}
