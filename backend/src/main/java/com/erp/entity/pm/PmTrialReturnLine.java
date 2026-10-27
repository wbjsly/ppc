package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 试制退库行（add-rnd-material）。 */
@Getter
@Setter
@TableName("erp_pm_trial_return_line")
public class PmTrialReturnLine extends BaseEntity {

    private String returnId;
    private String itemId;
    private String itemName;
    /** 退库批次（跨项目挪用校验依据 BR-4.8-04） */
    private String batchNo;
    /** 退库数（不得超过结余） */
    private java.math.BigDecimal returnQty;
    /** 原领用单价快照 */
    private java.math.BigDecimal unitPrice;
    /** 冲减额=退库数×原单价 */
    private java.math.BigDecimal amount;
    /** REUSABLE 可再用|SCRAP 报废 */
    private String qcResult;
    /** 1=已入库冲减（防重复） */
    private String returnedFlag;

    public static final String QC_REUSABLE = "REUSABLE";
    public static final String QC_SCRAP = "SCRAP";
}
