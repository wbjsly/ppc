package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 样品台账（add-rnd-material，BR-4.8-39 批次关联）。 */
@Getter
@Setter
@TableName("erp_pm_sample_ledger")
public class PmSampleLedger extends BaseEntity {

    private String consumptionId;
    /** 关联试制领料单 */
    private String issueId;
    /** 样品编号 */
    private String sampleNo;
    private String name;
    /** 关联领料批次（BR-4.8-39 用料来源追溯） */
    private String batchNo;
    private String itemId;
    private java.math.BigDecimal qty;
    /** TEST 测试|CUSTOMER 送客户|RETAIN 留样|SCRAP 报废 */
    private String destination;
    /** AVAILABLE|USED|SCRAPPED */
    private String status;
    private String registrer;
    private String remark;

    public static final String DEST_TEST = "TEST";
    public static final String DEST_CUSTOMER = "CUSTOMER";
    public static final String DEST_RETAIN = "RETAIN";
    public static final String DEST_SCRAP = "SCRAP";
}
