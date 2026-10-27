package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 结项成本结算不可变快照（BR-4.8-29 十年归档锚点；生成后无 UPDATE 业务路径）。 */
@Getter
@Setter
@TableName("erp_pm_closure_settlement")
public class ClosureSettlement extends BaseEntity {

    private String projectId;
    private String projectNo;
    /** 成本归集总额 */
    private BigDecimal collectTotal;
    /** 预算基线总额 */
    private BigDecimal baselineAmt;
    /** 偏差率（归集-基线）/基线 */
    private BigDecimal deviationRate;
    /** 确认时曾冻结并已解除 1|0 */
    private String unfrozenFlag;
    private String confirmBy;
    private LocalDateTime confirmDate;
}
