package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 预提/待摊计划行（M8-9，spec prepaid-amortization，决策⑦共用；GEN_STATUS 为结账⑨闸口与幂等锚）。
 */
@Data
@TableName("erp_fin_amort_plan")
public class AmortPlan {

    public static final String TYPE_PREPAY = "PREPAY";
    public static final String TYPE_AMORT = "AMORT";
    public static final String GEN_NOT = "NOT_GEN";
    public static final String GEN_DONE = "GENERATED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String planType;
    private String docId;
    private String docNo;
    private String period;
    private BigDecimal amount;
    private String genStatus;
    private String voucherId;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
