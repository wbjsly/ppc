package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 客户法人视图（客户管理 1.3.1，表 erp_mdm_customer_view，流程六 FR-4.1-6-2）。
 * 挂靠集团视图（GROUP_ID），每集团每法人一条（UK_GROUP_LE）。
 * 保存时校验 Σ本集团法人额度 × ratio ≤ 集团总额度（BR-4.1-31）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_customer_view")
public class MdmCustomerView extends BaseEntity {

    /** 所属集团视图 ID */
    private String groupId;

    /** 挂靠法人主体（erp_mdm_legal_entity.ID，须启用） */
    private String legalEntityId;

    private String shipAddress;

    private String contactName;

    private String contactPhone;

    /** 付款条件（如 NET30 / 月结30天） */
    private String paymentTerms;

    /** 本法人信用额度（可空；额度调整 UI 属 1.3.2，本期建列并落求和校验） */
    private BigDecimal creditLimit;

    /** 临时额度（BR-4.1-33，到期由定时/懒校验回滚清空） */
    private BigDecimal tempCreditLimit;

    /** 临时额度有效期（设临时额度时必填 > 当天） */
    private java.time.LocalDate tempExpireDate;

    /** 年度复审日期（C-4.3-13 台账；空 = 从未复审，按建档日起算宽限） */
    private java.time.LocalDate lastReviewDate;

    /** 复审超期压缩后的生效额度（非空 = 压缩中；原值 CREDIT_LIMIT 不动，恢复即清空，天然幂等） */
    private BigDecimal compressedLimit;

    /** 1 启用 / 0 停用 / 2 冻结（由集团冻结级联写入，BR-4.1-34） */
    private String status;

    /** 操作原因（增改/状态操作时必填），非持久化，写入版本记录 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private transient String changeReason;
}
