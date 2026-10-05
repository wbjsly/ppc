package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收货差异单（BR-4.2-21/22，差异对账台 L1064，change add-goods-receipt design D3）。
 * 状态机：PENDING → ADJUSTED（走调整单）| RETURNED（拒收）| CLOSED（短交补齐/手动关闭）。
 * 无"直接接受超收"出口（探索期 P-1 决策）。
 */
@Getter
@Setter
@TableName("erp_proc_gr_difference")
public class ReceiptDifference extends BaseEntity {

    /** GDF-YYYYMMDD-NNN */
    private String diffNo;

    private String grId;
    private String grLineId;
    private String poId;
    private String poLineId;
    private String itemCode;
    private String itemName;

    /** OVER 超交 / SHORT 短交 */
    private String diffType;

    private BigDecimal orderedQty;
    private BigDecimal receivedQty;
    private BigDecimal diffQty;
    /** 容差比（0.005） */
    private BigDecimal tolerancePct;

    /** PENDING / ADJUSTED / RETURNED / CLOSED */
    private String status;
    private String closeReason;

    private String disposeBy;
    private String disposeByName;
    private LocalDateTime disposeDate;
    private String disposeNote;
}
