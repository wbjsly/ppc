package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 加班确认单（MIN(申请,打卡) 认定 + 分流 + 金额固化；BR-4.7-11/16）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_overtime_confirm")
public class HrOvertimeConfirm extends BaseEntity {
    public static final String ST_PENDING = "PENDING";
    public static final String ST_EXCESS_PENDING = "EXCESS_PENDING";
    public static final String ST_SPLITTED = "SPLITTED";

    public static final String TYPE_WEEKDAY = "WEEKDAY";
    public static final String TYPE_WEEKEND = "WEEKEND";
    public static final String TYPE_HOLIDAY = "HOLIDAY";

    public static final String SPLIT_WAGE = "WAGE";
    public static final String SPLIT_COMP_TIME = "COMP_TIME";

    /** OTC+yyyyMMdd+4 位 */
    private String confirmNo;
    /** 来源申请 */
    private String applyId;
    private String empId;
    private String empNo;
    private LocalDate otDate;
    private BigDecimal appliedHours;
    private BigDecimal punchedHours;
    /** 有效时长 = MIN(申请,打卡) */
    private BigDecimal validHours;
    /** 打卡超申请待追认 */
    private String excessFlag;
    /** WEEKDAY/WEEKEND/HOLIDAY */
    private String otType;
    /** 倍数 1.5/2.0/3.0 */
    private BigDecimal multiplier;
    /** WAGE/COMP_TIME */
    private String splitChoice;
    private String splitBy;
    private LocalDateTime splitAt;
    /** 小时基数快照 = BASE_SALARY/21.75/8 */
    private BigDecimal hourlyBase;
    /** 加班工资固化金额 */
    private BigDecimal wageAmount;
    private String status;
}
