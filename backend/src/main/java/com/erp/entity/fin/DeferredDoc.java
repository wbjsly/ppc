package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 待摊费用登记单（M8-9-2，spec prepaid-amortization FR-4.6-8-1、BR-4.6-05）。
 * STATUS：OPEN / COMPLETED（计划行全部已生成凭证）。
 */
@Data
@TableName("erp_fin_deferred_doc")
public class DeferredDoc {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_COMPLETED = "COMPLETED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String defNo;
    private String summary;
    private BigDecimal totalAmount;
    private Integer amortMonths;
    /** 起始期间 YYYYMM */
    private String startPeriod;
    /** 费用科目（摊销 借方） */
    private String expenseAccountCode;
    private String expenseAccountName;
    /** 待摊资产科目（默认 1801，design D7） */
    private String assetAccountCode;
    private String status;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
