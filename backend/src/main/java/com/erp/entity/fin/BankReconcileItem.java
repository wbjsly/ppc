package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 银行对账差异处理留痕（M8-7-3，spec bank-reconciliation，design D7）。
 * 追加式记录：同一锚可多次追加形成处理轨迹，查询取最新。
 */
@Data
@TableName("erp_fin_bank_reconcile_item")
public class BankReconcileItem {

    /** 四类未达账项（FR-4.6-6-3） */
    public static final String TYPE_ENT_RCPT_BANK_PEND = "ENT_RCPT_BANK_PEND";
    public static final String TYPE_ENT_PAY_BANK_PEND = "ENT_PAY_BANK_PEND";
    public static final String TYPE_BANK_RCPT_ENT_PEND = "BANK_RCPT_ENT_PEND";
    public static final String TYPE_BANK_PAY_ENT_PEND = "BANK_PAY_ENT_PEND";
    /** 锚类型 */
    public static final String ANCHOR_RECEIPT = "RECEIPT";
    public static final String ANCHOR_CASH_DOC = "CASH_DOC";
    /** 处理状态 */
    public static final String ST_OPEN = "OPEN";
    public static final String ST_PROCESSING = "PROCESSING";
    public static final String ST_DONE = "DONE";
    public static final String ST_ESCALATED = "ESCALATED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String bankAccountId;
    private String period;
    private String itemType;
    private String anchorType;
    private String anchorId;
    private String anchorNo;
    private BigDecimal amount;
    private LocalDate tradeDate;
    private String status;
    private String handlingNote;
    private String handleBy;
    private LocalDateTime handleAt;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
