package com.erp.entity.mdm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 供应商资质证照（S-4.1-10 到期治理载体，表 erp_mdm_supplier_cert）。
 * 有效期 < 今天 → 所属供应商被懒巡检置 CERT_EXPIRED 受限（行 805）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_mdm_supplier_cert")
public class MdmSupplierCert extends BaseEntity {

    private String supplierId;

    /** LICENSE 营业执照 / INDUSTRY 行业认证 / OTHER 其它 */
    private String certType;

    private String certNo;

    private LocalDate issueDate;

    /** 必填（到期判定基准） */
    private LocalDate expireDate;

    /** 核验说明（更新证照/解除受限时记录） */
    private String verifyNote;
}
