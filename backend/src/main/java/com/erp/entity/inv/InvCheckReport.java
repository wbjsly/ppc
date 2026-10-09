package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/**
 * 库存恒等式校验报告（spec stock-snapshot，FR-4.4-2-5 / BR-4.4-15；迁移 103）。
 * 每日一次（RUN_DATE 唯一）：逐行断言 QTY = AVAILABLE_QTY + QC_QTY + FIN_QTY；
 * 差异明细 JSON 中的维度在 4.3.1 页面标记「待核实」。
 */
@Getter
@Setter
@TableName("erp_inv_check_report")
public class InvCheckReport extends BaseEntity {

    /** OK / MISMATCH */
    public static final String ST_OK = "OK";
    public static final String ST_MISMATCH = "MISMATCH";

    private LocalDate runDate;
    private Integer mismatchCnt;
    private String status;
    /** 差异明细 JSON：[{warehouseCode,itemCode,batchNo,qty,availableQty,qcQty,finQty,expect}] */
    private String detailJson;
}
