package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 销售能力档案（add-lead-auto-assign：本期仅资深标记，经理维护）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_crm_sales_profile")
public class CrmSalesProfile extends BaseEntity {

    /** 销售登录账号（与 lead.OWNER_ID 同语义） */
    private String empId;
    private String empName;
    /** 1=资深（A 级线索优先分配） */
    private String isSenior;
    private String remark;
}
