package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 申报栏次明细（tax-filing design D1，BR-4.14-30 栏次级差异的结构基础）。
 * LINE_CODE 命名空间化：主表-销项税额 / 附表1-13% / 附表2-已认证 / 附加税-城建。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tax_return_line")
public class TaxReturnLine extends BaseEntity {

    private String returnId;
    private String lineCode;
    private String lineName;
    private BigDecimal amount;
    /** 来源描述（可追溯到台账明细） */
    private String sourceDesc;
}
