package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 凭证模板（add-gl-management，design D5：行 JSON 快照，金额留空待录）。
 */
@Getter
@Setter
@TableName("erp_fin_voucher_template")
public class FinVoucherTemplate extends BaseEntity {

    private String name;

    private String voucherType;

    /** 行快照 JSON：[{accountCode,direction,summary,costCenter,profitCenter,auxCustomer,...}] */
    private String rowsJson;

    /** 制单人隔离（NULL=公共模板） */
    private String userScope;
}
