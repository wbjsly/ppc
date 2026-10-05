package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 年度采购预算（change add-framework-agreement-order，design D7：轻量，科目 × 年度唯一）。
 * 主数据基线模式：无审批、立即生效、修改留痕（CHANGE_REASON）。
 * 表无 DEL_FLAG，不继承 BaseEntity；消费方为价控第 3 级（BR-4.2-17）与审批明细。
 */
@Data
@TableName("erp_proc_budget")
public class ProcBudget implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    /** 预算年度，如 2026（与 CATEGORY_CODE 唯一） */
    private Integer budgetYear;

    /** 品类科目（erp_mdm_item_category.CATEGORY_CODE 同口径） */
    private String categoryCode;

    private String categoryName;

    /** 预算金额（元） */
    private BigDecimal budgetAmt;

    /** 修改原因（留痕） */
    private String changeReason;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;

    private Integer verNo;
}
