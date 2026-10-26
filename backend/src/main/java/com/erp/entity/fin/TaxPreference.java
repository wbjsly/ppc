package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 税收优惠备案台账（tax-preference-register，BR-4.14-05/06 / S-4.14-11）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tax_preference")
public class TaxPreference extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_FROZEN = "FROZEN";
    public static final String ST_EXPIRED = "EXPIRED";
    public static final String ST_TERMINATED = "TERMINATED";

    public static final String TYPE_VAT_REFUND = "VAT_REFUND";
    public static final String TYPE_RND_SUPER = "RND_SUPER";
    public static final String TYPE_OTHER = "OTHER";

    /** 备案号 TPF+流水 */
    private String prefNo;
    private String policyName;
    /** 政策依据文号 */
    private String policyDocNo;
    /** VAT_REFUND 即征即退 / RND_SUPER 加计扣除 / OTHER */
    private String prefType;
    /** 适用主体 */
    private String subject;
    private LocalDate prefStart;
    private LocalDate prefEnd;
    /** 备案范围口径 JSON（收入上限/产品清单/科目口径） */
    private String prefScopeJson;
    private String docList;
    private String obligation;
    /** ACTIVE/FROZEN/EXPIRED/TERMINATED */
    private String status;
    private String frozenReason;
    private LocalDate revokeDate;
    private String terminateReason;
    /** 补税风险评估说明（废止时必填） */
    private String riskAssessNote;
    /** 续期留痕 JSON（原期间/新期间/续期依据/操作人/时间） */
    private String renewHistoryJson;
}
