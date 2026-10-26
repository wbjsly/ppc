package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 公共费用池（add-cost-collection-freeze，间接费用期间录入）。 */
@Getter
@Setter
@TableName("erp_pm_cost_pool")
public class PmCostPool extends BaseEntity {

    /** yyyy-MM */
    private String period;
    /** 管理费|办公费|IT费用|其他 */
    private String expenseCategory;
    private java.math.BigDecimal amount;
    private String remark;
}
