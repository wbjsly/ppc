package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 费用报销行（M8-8-1）：发票号+销方为 C-4.6-13 重复报销 L1 阻断的校验键。
 */
@Data
@TableName("erp_fin_reimburse_line")
public class ReimburseLine {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String reimburseId;
    private String expNo;
    private String expenseType;
    private BigDecimal amount;
    private LocalDate occurDate;
    private String costCenterId;
    private String costCenterName;
    /** 发票号（C-4.6-13 唯一校验键） */
    private String invoiceNo;
    /** 发票销方 */
    private String invoiceVendor;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
