package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 费用类型-科目映射（M8-8，spec expense-reimbursement，决策③）。
 * 报销行按 TYPE_CODE 引用，凭证分录取 ACCOUNT_CODE；映射缺失/停用 → 422（FR-4.6-7-5）。
 */
@Data
@TableName("erp_fin_expense_type")
public class ExpenseType {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DISABLED = "DISABLED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String typeCode;
    private String typeName;
    private String accountCode;
    private String status;
    private String remark;
    private String createBy;
    private java.time.LocalDateTime createDate;
    private String updateBy;
    private java.time.LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
