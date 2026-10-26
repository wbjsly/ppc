package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 工单成本归集汇总行（change add-cost-aggregation，迁移 127，spec production-cost-aggregation）。
 * 工单级唯一（UK MO_ID），重建式归集 = 删旧插新；承载料工费三金额、单位成本、
 * 标准成本法四分解差异与差异报告状态（design D1）。
 */
@Getter
@Setter
@TableName("erp_mrp_cost_summary")
public class CostSummary extends BaseEntity {

    public static final String REPORT_NONE = "NONE";
    public static final String REPORT_RAISED = "RAISED";
    public static final String REPORT_REVIEWED = "REVIEWED";

    /** 工单 ID */
    private String moId;
    /** 工单号 */
    private String moNo;
    /** 归集期次 yyyyMM（归集执行时点） */
    private String period;
    /** 直接材料合计 */
    private BigDecimal materialCost;
    /** 直接人工合计 */
    private BigDecimal laborCost;
    /** 制造费用分摊合计 */
    private BigDecimal overheadCost;
    /** 总成本 = 料+工+费 */
    private BigDecimal totalCost;
    /** 合格产出 */
    private BigDecimal goodQty;
    /** 单位成本 = 总成本/合格产出 */
    private BigDecimal unitCost;
    /** 标准总成本 = 物料标准成本 × 合格产出 */
    private BigDecimal stdTotalCost;
    /** 总差异 = 实际 − 标准 */
    private BigDecimal varTotal;
    /** 价格差异（生产侧恒 0，采购 1406 已确认） */
    private BigDecimal varPrice;
    /** 用量差异（实算） */
    private BigDecimal varVolume;
    /** 效率差异（实算） */
    private BigDecimal varEfficiency;
    /** 产能差异（当期负荷近似） */
    private BigDecimal varCapacity;
    /** 差异率 = |总差异| / 标准总成本 */
    private BigDecimal varRate;
    /** 差异报告状态 NONE/RAISED/REVIEWED */
    private String reportStatus;
    /** 缺素提示（如「制造费用未归集（当期费用池未维护）」） */
    private String mark;
}
