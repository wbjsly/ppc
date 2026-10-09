package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 返利政策阶梯（达成率区间 → 返利率，超额累进；tasks 11.2/11.4，spec sales-rebate）。
 * CUSTOMER_ID 为空 = 全局默认政策；BAND_TO 为空 = 无上限段。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_rebate_policy")
public class RebatePolicy extends BaseEntity {

    public static final String ST_EFFECTIVE = "EFFECTIVE";
    public static final String ST_DISABLED = "DISABLED";

    private String policyNo;
    private String policyName;
    private String customerId;
    /** 达成率下限 %（含），如 80 */
    private BigDecimal bandFrom;
    /** 达成率上限 %（不含，空 = 无上限） */
    private BigDecimal bandTo;
    /** 该段返利率 %，如 2 */
    private BigDecimal rebateRate;
    private LocalDate validFrom;
    private LocalDate validTo;
    private String status;
    private Integer seq;
    private String remark;
}
