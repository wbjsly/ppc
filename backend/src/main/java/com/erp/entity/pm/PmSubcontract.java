package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 外协合同台账（add-cost-collection-freeze，FR-4.8-5-2）。 */
@Getter
@Setter
@TableName("erp_pm_subcontract")
public class PmSubcontract extends BaseEntity {

    /** 合同号 PSC+yyyyMMdd-序号 */
    private String contractNo;
    private String projectId;
    /** 可空（L4 标记 NO_WBS_FLAG） */
    private String wbsId;
    /** 1=未挂 WBS（L4 提示） */
    private String noWbsFlag;
    private String supplierId;
    private String supplierName;
    /** 含税总额 */
    private java.math.BigDecimal totalAmount;
    /** 已付累计 */
    private java.math.BigDecimal paidAmount;
    /** ACTIVE|CLOSED */
    private String status;
}
