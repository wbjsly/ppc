package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 会计科目（最小总账骨架，change add-accrual-three-way-match design D1/D2）。
 * 编码全局唯一；STATUS=DISABLED 拒绝被新凭证行引用（gl-voucher spec）。
 */
@Getter
@Setter
@TableName("erp_fin_account")
public class FinAccount extends BaseEntity {

    /** 科目编码（唯一） */
    private String accountCode;
    private String accountName;
    /** DR 借 / CR 贷 */
    private String direction;
    /** ASSET / LIAB / COST / PROFIT / CASH */
    private String category;
    /** ACTIVE / DISABLED */
    private String status;
}
