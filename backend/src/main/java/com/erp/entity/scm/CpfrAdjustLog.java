package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** CPFR 调整/协商留痕（spec cpfr-collaboration R4，C-4.9-11 ≥180 天）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_adjust_log")
public class CpfrAdjustLog extends BaseEntity {

    public static final String TT_EXCEPTION = "EXCEPTION";
    public static final String TT_PLAN_LINE = "PLAN_LINE";

    public static final String ACT_SUPPLIER_OPINION = "SUPPLIER_OPINION";
    public static final String ACT_PLAN_CONFIRM = "PLAN_CONFIRM";
    public static final String ACT_ADJUST_SUBMIT = "ADJUST_SUBMIT";
    public static final String ACT_ADJUST_APPROVED = "ADJUST_APPROVED";
    public static final String ACT_ADJUST_REJECTED = "ADJUST_REJECTED";
    public static final String ACT_ADJUST_DIRECT = "ADJUST_DIRECT";
    public static final String ACT_CONVERT = "CONVERT";

    /** EXCEPTION / PLAN_LINE */
    private String targetType;
    private String targetId;
    private String agreementId;
    private String periodTag;
    private String action;
    private BigDecimal oldQty;
    private BigDecimal newQty;
    private String operator;
    private String operatorRole;
    private String remark;
    private LocalDateTime operatedAt;
}
