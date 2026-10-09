package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 会计凭证行（spec gl-voucher）。金额精确到分，可为负（红字行）。 */
@Getter
@Setter
@TableName("erp_fin_voucher_line")
public class FinVoucherLine extends BaseEntity {

    private String voucherId;
    private Integer lineNo;
    private String accountCode;
    private String accountName;
    /** DR 借 / CR 贷 */
    private String direction;
    private BigDecimal amount;
    private String summary;
    /** 辅助核算-供应商 */
    private String supplierId;
}
