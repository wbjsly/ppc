package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 费用报销单（M8-8-1，spec expense-reimbursement，FR-4.6-7-3/4/5）。
 * SETTLE_STATUS：差额结算状态（决策⑤ 出纳手动，NONE/PENDING/DONE）。
 */
@Data
@TableName("erp_fin_reimburse")
public class Reimburse {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";
    /** 结算状态 */
    public static final String SETTLE_NONE = "NONE";
    public static final String SETTLE_PENDING = "PENDING";
    public static final String SETTLE_DONE = "DONE";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String expNo;
    private String employeeId;
    private String employeeName;
    private BigDecimal totalAmount;
    private String status;
    private String settleStatus;
    /** 已结算合计（补付为正 / 退款为负累计） */
    private BigDecimal settleAmount;
    private String apprId;
    private String voucherId;
    /** 费用归属期间 YYYYMM */
    private String occurPeriod;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
