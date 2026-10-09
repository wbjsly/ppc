package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 税收政策台账（税码管理 1.6.2，表 erp_mdm_tax_policy，S-4.1-07 政策侧留痕）。
 * POLICY_NO 全局唯一、登记后不可改；与税码按文号文本关联（design D4）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_tax_policy")
public class MdmTaxPolicy extends BaseEntity {

    /** 政策文号（唯一，如 税总公告2026年第15号），创建后不可改 */
    private String policyNo;

    private String policyName;

    /** 发文机关 */
    private String issuer;

    private LocalDate issueDate;

    /** 生效日期，不得早于发布日期 */
    private LocalDate effectiveDate;

    private String summary;

    private String remark;
}
