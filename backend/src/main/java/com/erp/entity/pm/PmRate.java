package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 岗位费率（project-initiation，FR-4.8-2-3 人工成本口径）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_rate")
public class PmRate extends BaseEntity {

    /** 职级 CODE：JUNIOR/MID/SENIOR */
    private String grade;
    private String gradeName;
    private BigDecimal ratePerHour;
    /** 生效自（含；170 加列，期间型费率） */
    private java.time.LocalDate effFrom;
    /** 生效至（含，NULL=至今） */
    private java.time.LocalDate effTo;
    private String status;
}
