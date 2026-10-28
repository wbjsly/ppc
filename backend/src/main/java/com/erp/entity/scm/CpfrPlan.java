package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** CPFR 联合补货计划（spec cpfr-collaboration R5）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_plan")
public class CpfrPlan extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SINGLE = "单方确认";
    public static final String ST_BOTH = "双方确认";
    public static final String ST_ORDERED = "已生成订单";

    private String agreementId;
    private String periodTag;
    private String status;
    private String supplierConfirmBy;
    private LocalDateTime supplierConfirmAt;
    private String internalConfirmBy;
    private LocalDateTime internalConfirmAt;
    /** 单方确认后 48h 催办（幂等标记） */
    private LocalDateTime singleRemindAt;
    /** 未达成例外数（确认前校验） */
    private Integer openExceptionCount;
    /** 月度准确率（%） */
    private BigDecimal accuracyPct;
    /** 评估月 yyyyMM */
    private String accuracyMonth;
}
