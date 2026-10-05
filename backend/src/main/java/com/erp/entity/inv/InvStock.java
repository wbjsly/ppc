package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 最小库存（4.4 承接，change add-goods-receipt design D1/D6；add-quality-collaboration B 口径改造）。
 * QTY = QC_QTY + AVAILABLE_QTY；QC_QTY 语义在 2.5 接入后由「待检锁定」转为
 * 「让步接收限制锁定」（检验合格量过账后直入 AVAILABLE_QTY，spec receipt-posting MODIFIED）。
 * 唯一键 (ITEM_CODE, BATCH_NO)。
 */
@Getter
@Setter
@TableName("erp_inv_stock")
public class InvStock extends BaseEntity {

    private String itemCode;
    private String itemName;
    /** 无批次时为空串（唯一键组成） */
    private String batchNo;

    private BigDecimal qty;
    /** 锁定量（让步接收限制锁定，B 口径） */
    private BigDecimal qcQty;
    /** 可领用量 */
    private BigDecimal availableQty;

    /** 1 = 让步接收锁定中（spec concession-acceptance，D8） */
    private String concessionFlag;
    /** 让步限制快照 JSON：有效期 / 批准上限 / 使用范围 */
    private String concessionLimit;
}
