package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** CPFR 分歧例外项（spec cpfr-collaboration R4，BR-4.9-32 两态闭环）。 */
@Getter
@Setter
@TableName("erp_scm_cpfr_exception")
public class CpfrException extends BaseEntity {

    public static final String ST_OPEN = "待协商";
    public static final String ST_AGREED = "已达成";
    public static final String ST_REJECTED = "已驳回";

    private String agreementId;
    private String periodTag;
    private Integer weekOffset;
    private String itemCode;
    private BigDecimal demandQty;
    private BigDecimal supplyQty;
    /** 分歧率（%，负 = 供给不足） */
    private BigDecimal deviationPct;
    private String status;
    private LocalDateTime closedAt;
    private String closeBy;
}
