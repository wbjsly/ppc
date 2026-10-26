package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 外派补贴标准（城市×职级→月补贴，FR-4.7-7-3 自动带出）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_dispatch_allowance_std")
public class HrDispatchAllowanceStd extends BaseEntity {

    /** 缴纳地/外派地 */
    private String place;
    /** 职级 */
    private String gradeId;
    /** 月补贴标准 */
    private BigDecimal monthly;
}
