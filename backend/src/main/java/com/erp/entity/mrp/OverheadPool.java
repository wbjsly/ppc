package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 制造费用池（change add-cost-aggregation，迁移 127，spec production-cost-aggregation
 * 「制造费用池与分摊」；design D2：期次唯一 + 池总额 + 三选一分摊规则）。
 */
@Getter
@Setter
@TableName("erp_mrp_overhead_pool")
public class OverheadPool extends BaseEntity {

    public static final String RULE_TIME = "TIME";
    public static final String RULE_MACHINE = "MACHINE";
    public static final String RULE_QTY = "QTY";

    /** 期次 yyyyMM（UNIQUE） */
    private String period;
    /** 当期制造费用总额 */
    private BigDecimal poolAmt;
    /** 分摊规则 TIME/MACHINE/QTY（未配置默认 TIME） */
    private String rule;
    /** 备注 */
    private String remark;
}
