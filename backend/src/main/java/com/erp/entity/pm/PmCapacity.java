package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 人员产能日历（project-initiation，FR-4.8-2-5）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_capacity")
public class PmCapacity extends BaseEntity {

    private String userId;
    private LocalDate capDate;
    /** 默认工作日 8，周末 0 */
    private BigDecimal hours;
    private String remark;
}
