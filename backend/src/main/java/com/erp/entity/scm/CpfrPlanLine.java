package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** CPFR 预测订单行（spec cpfr-collaboration R5/R6，C-4.9-08 冻结/弹性标注）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_plan_line")
public class CpfrPlanLine extends BaseEntity {

    public static final String ZONE_FREEZE = "FREEZE";
    public static final String ZONE_ELASTIC = "ELASTIC";
    public static final String ZONE_FREE = "FREE";

    public static final String ADJ_PENDING = "PENDING_APPROVAL";

    private String planId;
    private String agreementId;
    private String periodTag;
    private String itemCode;
    private String itemName;
    /** 相对确认周的偏移（ZONE 判定输入） */
    private Integer weekOffset;
    /** 当前锁定数量 */
    private BigDecimal qty;
    /** FREEZE/ELASTIC/FREE */
    private String zone;
    /** 转化登记：实际到货量 */
    private BigDecimal poConversionQty;
    private LocalDateTime convertedAt;
    private String convertRemark;
    /** 冻结/超弹性调整审批中 */
    private String adjustStatus;
}
