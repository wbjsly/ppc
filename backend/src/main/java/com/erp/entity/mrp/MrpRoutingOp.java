package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工艺路线工序行（change add-routing-management，spec routing-management / 00-erp-spec FR-4.5-2-5/FR-4.5-3-4；迁移 118）。
 * 纯串行 op_seq 递增（偏差 D2 无并行分支）；行保存时 L1 校验（工序/工作中心启用 + 定额组合存在）；
 * LEAD_TIME 供 MRP 提前期倒排串行 Σ。
 */
@Getter
@Setter
@TableName("erp_mrp_routing_op")
public class MrpRoutingOp extends BaseEntity {

    private String routingId;
    /** 工序序号（纯串行递增，服务端按载荷序分配） */
    private Integer opSeq;
    private String opCode;
    private String opName;
    private String wcCode;
    private String wcName;
    /** 工序提前期（天，供 MRP 串行 Σ；缺失由下游回落物料默认提前期） */
    private BigDecimal leadTime;
    /** 标准良率（%，(0,100] 默认 98——FR-4.5-5-5 良率判定基准，5.7 变更加列，迁移 123） */
    private BigDecimal standardYield;
    /** 关键工序标记（FR-4.5-5-3 首次完工报工自动建 IPQC 批，5.7 变更加列） */
    private String keyOpFlag;
    private String remark;
}
