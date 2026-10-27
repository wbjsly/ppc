package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** IP 归属协议（add-ip-management，FR-4.8-6-2 数据基座）。 */
@Getter
@Setter
@TableName("erp_pm_ip_agreement")
public class PmIpAgreement extends BaseEntity {

    private String projectId;
    /** INDEP 独立研发|COOP 合作研发|ENTRUST 委托研发 */
    private String coopMode;
    /** 公司权属比例%（INDEP=100） */
    private java.math.BigDecimal companyRate;
    private String partnerName;
    /** 合作方比例%（COOP 合计必须=100，BR-4.8-25） */
    private java.math.BigDecimal partnerRate;
    private String entrusteeName;
    private java.time.LocalDate signDate;
    /** 1=生效（后签覆盖前签，旧协议置 0 留痕） */
    private String effective;
    private String remark;
}
