package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 试制实际消耗行（add-rnd-material）。 */
@Getter
@Setter
@TableName("erp_pm_trial_consumption_line")
public class PmTrialConsumptionLine extends BaseEntity {

    private String consumptionId;
    /** 关联领料行 */
    private String issueLineId;
    private String itemId;
    private String itemName;
    /** 领用数 */
    private java.math.BigDecimal issuedQty;
    /** 实耗数 */
    private java.math.BigDecimal actualQty;
    /** 结余=领用-实耗（服务端算） */
    private java.math.BigDecimal surplusQty;
    /** 原领用单价快照（退库冲减依据） */
    private java.math.BigDecimal unitPrice;
    /** 差异说明（>10% 时建议填写，空则 L4） */
    private String deviationNote;
}
