package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 假期余额批次（调休+年假统一 FEFO 作业池：到期日升序扣减）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_leave_balance_batch")
public class HrLeaveBalanceBatch extends BaseEntity {
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_USED = "USED";
    public static final String ST_EXPIRED = "EXPIRED";
    public static final String SRC_OT = "OT";
    public static final String SRC_ANNUAL = "ANNUAL_GRANT";
    public static final String UNIT_HOUR = "HOUR";
    public static final String UNIT_DAY = "DAY";

    /** LBB+yyyyMMdd+4 位 */
    private String batchNo;
    private String empId;
    /** COMP_TIME / ANNUAL */
    private String typeCode;
    /** 批次总量 */
    private BigDecimal qty;
    private BigDecimal remain;
    /** HOUR / DAY */
    private String qtyUnit;
    /** OT / ANNUAL_GRANT */
    private String source;
    /** 调休溯源（hr_comp_time_batch.ID） */
    private String sourceBatchId;
    private LocalDateTime generatedAt;
    /** 调休=生成+N月 / 年假=当年 12-31 */
    private LocalDate expiredAt;
    private String status;
}
