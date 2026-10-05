package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价控校验日志（BR-4.2-19 完整校验日志；表 erp_proc_po_price_control）。
 * CHECK_LEVEL: CONTRACT / HISTORY / BUDGET；RESULT: PASS / ESCALATE / BLOCK / NO_HISTORY / NO_BUDGET。
 */
@Data
@TableName("erp_proc_po_price_control")
public class PriceControlLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String poId;

    private String poNo;

    private Integer lineNo;

    private String itemCode;

    private String checkLevel;

    /** 基准值（协议价 / 历史均价 / 预算 × 阈值） */
    private BigDecimal baseValue;

    /** 实际值（PO 单价 / 累计 + 本次） */
    private BigDecimal actualValue;

    /** PASS / ESCALATE / BLOCK / NO_HISTORY / NO_BUDGET */
    private String result;

    private String reason;

    private String chkBy;

    private LocalDateTime chkDate;
}
