package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工单成本归集明细行（change add-cost-aggregation，迁移 127）。
 * MATERIAL 按物料、LABOR 按报工单、OH 按期次池维度分行（design D1）。
 */
@Getter
@Setter
@TableName("erp_mrp_cost_line")
public class CostLine extends BaseEntity {

    public static final String T_MATERIAL = "MATERIAL";
    public static final String T_LABOR = "LABOR";
    public static final String T_OH = "OH";

    /** 单价缺失（标准成本与最近采购价均无） */
    public static final String MARK_NO_PRICE = "NO_PRICE";
    /** 当期费用池未维护 */
    public static final String MARK_NO_POOL = "NO_POOL";

    /** 工单 ID */
    private String moId;
    /** MATERIAL/LABOR/OH */
    private String costType;
    /** 维度键：物料编码 / 报工单号 / 池期次 */
    private String dimKey;
    /** 维度显示名 */
    private String dimName;
    /** 数量（投料量 / 工时 / 基准工时） */
    private BigDecimal qty;
    /** 单价 / 费率 / 分摊额 */
    private BigDecimal rate;
    /** 金额 */
    private BigDecimal amount;
    /** 缺失标记 NO_PRICE/NO_POOL */
    private String mark;
}
