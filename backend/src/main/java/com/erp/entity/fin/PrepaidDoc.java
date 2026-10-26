package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 预提费用登记单（M8-9-1，spec prepaid-amortization FR-4.6-8-1/5）。
 * STATUS：OPEN / SETTLED（实际冲销完成，C-4.6-16 差异行在冲销凭证内生）。
 */
@Data
@TableName("erp_fin_prepaid_doc")
public class PrepaidDoc {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_SETTLED = "SETTLED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String preNo;
    private String expenseType;
    /** 费用科目（计提 借方） */
    private String accountCode;
    private String accountName;
    /** 归属期间 YYYYMM（计提计划期间） */
    private String period;
    private BigDecimal amount;
    private String status;
    private BigDecimal actualAmount;
    private LocalDate settleDate;
    private String settleVoucherId;
    private String settleRemark;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
