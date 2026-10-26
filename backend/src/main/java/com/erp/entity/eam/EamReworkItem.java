package com.erp.entity.eam;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 判定依据明细（BR-37 三条件量化 + 证据附件，add-asset-rework design D1）。
 */
@Getter
@Setter
@TableName("erp_eam_rework_item")
public class EamReworkItem extends BaseEntity {

    @TableField("ORDER_ID")
    private String orderId;

    /** LIFT_LIFE 寿命延长 / LIFT_CAPACITY 产能提升 / LIFT_QUALITY 精度性能 */
    @TableField("COND_TYPE")
    private String condType;

    /** 量化值（寿命=延长年数；产能=提升百分比；精度=空） */
    @TableField("QUANT_VALUE")
    private BigDecimal quantValue;

    /** 证据附件锚 */
    @TableField("EVIDENCE_NO")
    private String evidenceNo;

    @TableField("REMARK")
    private String remark;
}
