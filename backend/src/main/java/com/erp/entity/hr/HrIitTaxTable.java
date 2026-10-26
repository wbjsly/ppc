package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 个税月度税率表（累计预扣法适用，官方七级超额累进，YEAR 维度）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_iit_tax_table")
public class HrIitTaxTable extends BaseEntity {
    private Integer year;
    private Integer levelNo;
    /** 累计应纳税所得额下限 */
    private BigDecimal lowerBound;
    /** 上限（NULL=无上限） */
    private BigDecimal upperBound;
    private BigDecimal rate;
    /** 速算扣除数 */
    private BigDecimal quickDeduct;
}
