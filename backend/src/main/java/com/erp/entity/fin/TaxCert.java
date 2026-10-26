package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 税控设备/数字证书台账（C-4.14-14）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tax_cert")
public class TaxCert extends BaseEntity {

    public static final String TYPE_TAX_DISK = "TAX_DISK";
    public static final String TYPE_DIGITAL_CERT = "DIGITAL_CERT";

    public static final String ST_VALID = "VALID";
    public static final String ST_EXPIRED = "EXPIRED";
    public static final String ST_DISABLED = "DISABLED";

    private String certType;
    private String certName;
    /** 证书/设备编号 */
    private String certNo;
    private LocalDate issueDate;
    private LocalDate expireDate;
    /** VALID/EXPIRED/DISABLED */
    private String status;
    /** 绑定开票通道 */
    private String channel;
}
