package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 收货单头（2.4.1，change add-goods-receipt design D1）。
 * SOURCE_TYPE：PO（默认，按未清量核对）/ FREE（无 PO 收货，免容差扩展场景）。
 * 状态机：CREATED → POSTED（过账）| CANCELLED（作废，仅 CREATED）。
 */
@Getter
@Setter
@TableName("erp_proc_gr")
public class GoodsReceipt extends BaseEntity {

    /** GR + yyyyMM + - + 6 位流水 */
    private String grNo;

    /** PO / FREE */
    private String sourceType;

    private String poId;
    private String poNo;
    private String supplierId;
    private String supplierName;

    /** CREATED / POSTED / CANCELLED */
    private String status;

    private LocalDate arrivalDate;
    /** 送货单号 */
    private String deliveryNote;
    /** 到货批次号 */
    private String batchNo;
    /** 运输信息（车牌/快递单号） */
    private String transportInfo;
    /** 外包装状况 */
    private String packageCondition;
    private String remark;

    /** 入库凭证号（过账生成：IV + yyyyMM + - + 6 位） */
    private String postingDocNo;
    private LocalDateTime postingDate;
}
