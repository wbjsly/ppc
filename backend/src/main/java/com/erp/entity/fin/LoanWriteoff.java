package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 借款冲销明细（M8-8-3，spec expense-reimbursement FR-4.6-7-6，FIFO 逐笔留痕）。
 */
@Data
@TableName("erp_fin_loan_writeoff")
public class LoanWriteoff {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String woNo;
    private String reimburseId;
    private String reimburseNo;
    private String loanId;
    private String loanNo;
    private String employeeId;
    private BigDecimal amount;
    private LocalDate writeoffDate;
    private String voucherId;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
