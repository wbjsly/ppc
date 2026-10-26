package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 薪酬补扣项（内嵌 10.5.3 录入；正=补贴 负=扣款；批次生成后锁）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_pay_adjustment")
public class HrPayAdjustment extends BaseEntity {
    public static final String TYPE_MEAL = "MEAL_ALLOW";
    public static final String TYPE_TRANSPORT = "TRANSPORT_ALLOW";
    public static final String TYPE_BONUS = "ATTEND_BONUS";
    public static final String TYPE_FINE = "LATE_FINE";
    public static final String TYPE_OTHER = "OTHER";

    private String period;
    private String empId;
    private String itemType;
    private BigDecimal amount;
    private String reason;
    /** 1=随批次锁定 */
    private String locked;
}
