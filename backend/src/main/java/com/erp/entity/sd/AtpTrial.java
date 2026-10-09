package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ATP 承诺试算台账（tasks 8.10，spec sales-atp-reservation 场景「可保存试算记录」）。
 * 四因子快照：OnHand + InProcess(恒0) + Incoming − Reserved − SafetyStock（BR-4.3-19）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_atp_trial")
public class AtpTrial extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_SAVED = "SAVED";

    private String itemCode;
    private String itemName;
    private String warehouseCode;
    private BigDecimal qty;
    private LocalDate expectDate;
    private BigDecimal onHand;
    /** 被排除：待检/让步锁定 */
    private BigDecimal excludedQc;
    /** 被排除：寄售库存 */
    private BigDecimal excludedVmi;
    /** 在制（生产域未接入恒 0） */
    private BigDecimal inProcess;
    /** 在途（已批 PO 且到货 ≤ 交期） */
    private BigDecimal incoming;
    /** 被剔除：计划延迟不计入（BR-4.3-20） */
    private BigDecimal incomingDelayed;
    /** 预留 SUM(ACTIVE) */
    private BigDecimal reserved;
    private BigDecimal safetyStock;
    private BigDecimal available;
    /** 1 = 满足需求（否则 L4 提示不削量） */
    private String enough;
    private LocalDate earliestPromiseDate;
    private String status;
    private String remark;
}
