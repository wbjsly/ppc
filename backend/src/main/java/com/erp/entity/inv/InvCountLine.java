package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 盘点任务行（4.11，spec count-management 实盘录入/差异分流；迁移 114）。
 * 行粒度 = 仓库×仓位×物料×批次；BOOK_QTY 在任务进入 COUNTING 时定格（不随账面漂移）。
 * COUNT_STATUS：PENDING → COUNTED（录入）→ ADJUSTED；RECOUNT = >10% 阻断待复盘标记。
 */
@Getter
@Setter
@TableName("erp_inv_count_line")
public class InvCountLine extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_COUNTED = "COUNTED";
    public static final String ST_ADJUSTED = "ADJUSTED";
    /** 差异率 >10% 阻断提交标记（BR-4.4-39 L1 语义保留，重录清除） */
    public static final String ST_RECOUNT = "RECOUNT";

    /** 实物异常标记 */
    public static final String AB_DAMAGED = "DAMAGED";
    public static final String AB_EXPIRED = "EXPIRED";
    public static final String AB_NO_LABEL = "NO_LABEL";

    private String taskId;

    /** 盘点仓位（'' = 未分配位，不进锁仓集合） */
    private String binCode;

    private String itemCode;

    private String itemName;

    /** 无批次物料空串 */
    private String batchNo;

    /** 账面快照（COUNTING 生效时定格） */
    private BigDecimal bookQty;

    /** 实盘数 */
    private BigDecimal actualQty;

    /** 差异数 = ACTUAL − BOOK */
    private BigDecimal diffQty;

    /** 差异率 = |DIFF| / BOOK（BOOK=0 且实盘>0 按 100%） */
    private BigDecimal diffRate;

    /** 差异金额 = |DIFF_QTY| × standardCost */
    private BigDecimal diffAmount;

    private String countStatus;

    /** 1 = 容差内自动调整（无需审批） */
    private String autoAdjust;

    /** 关联差异单（DIFF_TYPE=COUNT，超容差） */
    private String diffId;

    /** 调整单据号（ADJUST_IN/OUT 来源单号） */
    private String adjustDocNo;

    /** DAMAGED / EXPIRED / NO_LABEL */
    private String abnormalFlag;

    private String countedBy;

    private LocalDateTime countedAt;

    private String remark;
}
