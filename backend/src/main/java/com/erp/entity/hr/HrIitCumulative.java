package com.erp.entity.hr;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 个税累计预扣台账（人×年，月度滚动：读-算-写回）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("hr_iit_cumulative")
public class HrIitCumulative extends BaseEntity {
    private String empId;
    private Integer year;
    /** 累计应发 */
    private BigDecimal cumGross;
    /** 累计专项扣除（社保公积金个人） */
    private BigDecimal cumDeduct;
    /** 累计专项附加扣除 */
    private BigDecimal cumSpecial;
    /** 累计已预扣税额 */
    private BigDecimal cumTaxPaid;
}
