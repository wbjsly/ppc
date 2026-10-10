package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工单工序快照行（change add-work-order-management，FR-4.5-3-4；迁移 120）。
 * 创建时按已发布工艺路线快照：工序 + 工作中心分配 + 四类标准工时（准备/加工/等待/移动）带入。
 * OP_STATUS 预留工序级状态（5.7 报工推进），本期恒 PENDING。
 */
@Getter
@Setter
@TableName("erp_mrp_mo_op")
public class MrpMoOp extends BaseEntity {

    private String moId;
    private Integer opSeq;
    private String opCode;
    private String opName;
    private String wcCode;
    private String wcName;
    /** 工序提前期（天） */
    private BigDecimal leadTime;
    /** 准备工时 */
    private BigDecimal setupHours;
    /** 标准（加工）工时 */
    private BigDecimal runHours;
    /** 等待工时 */
    private BigDecimal waitHours;
    /** 移动工时 */
    private BigDecimal moveHours;
    /** 工序状态（预留，PENDING/DONE） */
    private String opStatus;
}
