package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 调休批次溯源锚（作业池在 hr_leave_balance_batch；FR-4.7-6-5）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_comp_time_batch")
public class HrCompTimeBatch extends BaseEntity {
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_USED = "USED";
    public static final String ST_EXPIRED = "EXPIRED";

    /** CT+yyyyMMdd+4 位 */
    private String batchNo;
    private String empId;
    /** 生成时长（1:1） */
    private BigDecimal hours;
    private BigDecimal remain;
    private LocalDateTime generatedAt;
    /** 有效期至 = 生成日 + COMP_TIME_VALID_MONTHS */
    private LocalDate expiredAt;
    /** 来源加班确认单 */
    private String sourceConfirmId;
    private String status;
}
