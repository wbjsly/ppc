package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 员工借款单（M8-8-2，spec expense-reimbursement，FR-4.6-7-1/2）。
 * OUTSTANDING_BALANCE 冗余列：账龄判定与 FIFO 冲销的读取源（design D1/D3）。
 */
@Data
@TableName("erp_fin_employee_loan")
public class EmployeeLoan {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_PAID = "PAID";
    public static final String ST_REJECTED = "REJECTED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String loanNo;
    private String employeeId;
    private String employeeName;
    private String purpose;
    private BigDecimal amount;
    /** 预计核销日期 */
    private LocalDate expectedDate;
    /** 未核销余额（回写列） */
    private BigDecimal outstandingBalance;
    private String status;
    /** 放款日期（账龄起算基准） */
    private LocalDate paidDate;
    private String apprId;
    private String voucherId;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
