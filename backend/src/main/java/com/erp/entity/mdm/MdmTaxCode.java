package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 税码主数据（税码管理 1.6.1，表 erp_mdm_tax_code，FR-4.1-3-2）。
 * 序列键 = TAX_CODE（BR-4.1-17 字面口径）；闭区间 [EFFECTIVE_DATE, EXPIRE_DATE] 均必填；
 * 无状态列（计算态按日期推导，与汇率域同构）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_tax_code")
public class MdmTaxCode extends BaseEntity {

    /** 税码编号：大写字母数字短横线 2~32 位，创建后不可改（C-4.1-01） */
    private String taxCode;

    /** 税率值（百分数，如 13.0000），4 位小数精度，>0（ZERO/EXEMPT 类型可为 0） */
    private BigDecimal taxRate;

    private LocalDate effectiveDate;

    private LocalDate expireDate;

    /** DOMESTIC 国内 / EXPORT 出口 / EXEMPT 免税 */
    private String scope;

    /** 政策文号（C-4.1-04 必附；与政策台账文本关联，无外键 design D4） */
    private String policyNo;

    /** GENERAL 一般 / SIMPLIFIED 简易 / DIFFERENTIAL 差额 */
    private String calcType;

    /** STANDARD 标准 / LOW 低税率 / ZERO 零税率 / EXEMPT 免税 */
    private String rateKind;

    /** 变更原因（编辑时必填），非持久化，写入版本记录 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient String changeReason;
}
