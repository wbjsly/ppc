package com.erp.entity.vmi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 处置建议单（BR-4.2-39，L4 不阻断结算，design D4 惰性生成）。 */
@Getter
@Setter
@TableName("erp_proc_vmi_disposal")
public class VmiDisposal extends BaseEntity {

    public static final String SUGGEST_CONVERT = "CONVERT";
    public static final String SUGGEST_RETURN = "RETURN";
    public static final String ST_OPEN = "OPEN";
    public static final String ST_DONE = "DONE";
    public static final String ST_CANCELLED = "CANCELLED";

    /** 处置单号 VD+yyyyMMdd+流水 */
    private String disposalNo;
    /** 寄售库存批次 ID */
    private String stockId;
    private String itemCode;
    private String itemName;
    private String batchNo;
    private String supplierId;
    private String supplierName;
    private BigDecimal qty;
    /** 生成时库龄 */
    private Integer ageDays;
    /** CONVERT 转自有采购 / RETURN 退回供应商 */
    private String suggestType;
    /** OPEN / DONE / CANCELLED */
    private String status;
    /** 实际处置方式（处理时可与建议不同） */
    private String resolvedVia;
    private String resolveBy;
    private LocalDateTime resolveAt;
    private String resolveNote;
    private String remark;
}
