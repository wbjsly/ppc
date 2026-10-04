package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 请购单行（erp_proc_pr_line）：80% 基线、逾期标记、PO 反写、行级关闭。 */
@Data
@TableName("erp_proc_pr_line")
public class ProcPrLine implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String prId;

    private Integer lineNo;

    private String itemCode;

    private BigDecimal qty;

    private LocalDate reqDate;

    /** 预估单价（询价前判级基数） */
    private BigDecimal estUnitPrice;

    /** WO / PLAN_ORDER / SO / SAFETY_STOCK / SIMULATED */
    private String sourceEnum;

    private String sourceDocNo;

    private String suggestedSupplierId;

    /** MRP 建议量（BR-4.2-08 80% 基线；手工行为 NULL 不卡控） */
    private BigDecimal mrpSuggestedQty;

    private String reduceReason;

    private String reviewer;

    /** 逾期/异常需求标记（FR-4.2-1-1） */
    private String overdueFlag;

    /** OPEN / CLOSED */
    private String lineStatus;

    private String closeReason;

    private LocalDateTime closeDate;

    /** PO 下达反写累计（BR-4.2-51 桩） */
    private BigDecimal allocQty;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;
}
