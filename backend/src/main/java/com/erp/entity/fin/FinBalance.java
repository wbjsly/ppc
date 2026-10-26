package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 科目余额（add-gl-management，design D3：期间+科目+辅助维度键唯一，过账回写）。
 */
@Getter
@Setter
@TableName("erp_fin_balance")
public class FinBalance extends BaseEntity {

    /** 会计期间 YYYYMM */
    private String period;

    private String accountCode;

    /** 辅助维度拼接键（|分隔，空=无辅助） */
    private String auxKey;

    /** 本期借方累计 */
    private BigDecimal drTotal;

    /** 本期贷方累计 */
    private BigDecimal crTotal;
}
