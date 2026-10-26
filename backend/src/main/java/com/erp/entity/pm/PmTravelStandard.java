package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 职级差旅标准（add-cost-collection-freeze，FR-4.8-5-3）。 */
@Getter
@Setter
@TableName("erp_pm_travel_standard")
public class PmTravelStandard extends BaseEntity {

    /** 职级 CODE */
    private String grade;
    /** 一线|二线|其他 */
    private String cityCategory;
    /** 日限额 */
    private java.math.BigDecimal dailyLimit;
    private String status;
}
