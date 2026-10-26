package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 摊余成本计划行（add-asset-transfer-lease，design D1/D5，实际利率法）：
 * 确认时全周期预生成；SNAPSHOT_SEQ=0 为当前行，重计量旧行 +1 归档（UNIQUE(CONTRACT_ID,SEQ,SNAPSHOT_SEQ)）。
 */
@Getter
@Setter
@TableName("erp_eam_lease_schedule")
public class EamLeaseSchedule extends BaseEntity {

    @TableField("CONTRACT_ID")
    private String contractId;

    @TableField("LSE_NO")
    private String lseNo;

    /** 月序（1 起；确认月 T0 行 SEQ=0 无利息无付款） */
    @TableField("SEQ")
    private Integer seq;

    @TableField("PERIOD_TAG")
    private String periodTag;

    @TableField("BEGIN_BAL")
    private BigDecimal beginBal;

    @TableField("INTEREST")
    private BigDecimal interest;

    @TableField("PAYMENT")
    private BigDecimal payment;

    @TableField("END_BAL")
    private BigDecimal endBal;

    @TableField("PROCESSED_FLAG")
    private String processedFlag;

    @TableField("PROCESSED_AT")
    private LocalDateTime processedAt;

    @TableField("VOUCHER_INT_ID")
    private String voucherIntId;

    @TableField("VOUCHER_PAY_ID")
    private String voucherPayId;

    @TableField("SNAPSHOT_SEQ")
    private Integer snapshotSeq;
}
