package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工序×工作中心工时定额（change add-routing-management，spec routing-management / 00-erp-spec L2330/FR-4.5-3-4；迁移 118）。
 * （OP_CODE, WC_CODE）唯一 = 工序↔工作中心适配关系的唯一表达（无定额行 = 不支持，spec「定额矩阵表达工序与工作中心适配」）；
 * 四类工时（准备/标准/等待/移动）为 MRP 提前期、工单带出、成本分摊的基数。
 */
@Getter
@Setter
@TableName("erp_mrp_op_wc_standard")
public class MrpOpWcStandard extends BaseEntity {

    private String opCode;
    private String wcCode;
    /** 准备工时（≥0） */
    private BigDecimal setupHours;
    /** 标准/加工工时（≥0） */
    private BigDecimal runHours;
    /** 等待工时（≥0） */
    private BigDecimal waitHours;
    /** 移动工时（≥0） */
    private BigDecimal moveHours;
}
