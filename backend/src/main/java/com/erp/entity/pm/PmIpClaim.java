package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 仲裁双方权属主张（add-ip-management，依据永久留痕）。 */
@Getter
@Setter
@TableName("erp_pm_ip_claim")
public class PmIpClaim extends BaseEntity {

    private String arbId;
    /** PARTY_A|PARTY_B */
    private String claimant;
    private String claimantName;
    private java.math.BigDecimal claimRate;
    /** 主张依据说明（附件走 erp_sys_attachment） */
    private String evidence;
    private String remark;

    public static final String PARTY_A = "PARTY_A";
    public static final String PARTY_B = "PARTY_B";
}
