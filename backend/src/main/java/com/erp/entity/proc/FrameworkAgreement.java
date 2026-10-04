package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 采购框架协议（FR-4.2-10-3 / BR-4.2-05 产出物，表 erp_proc_framework_agreement）。
 * <b>不复用</b> 销售侧客户价协议 erp_mdm_price_agreement（design D8）。
 * TENDER_NO 唯一 -> 公示期满只生成一次（幂等靠 DuplicateKey 快速失败）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_proc_framework_agreement")
public class FrameworkAgreement extends BaseEntity {

    /** FA-YYYYMMDD-NNN 按日流水，全局唯一 */
    private String agreementNo;

    /** 关联招标编号（唯一） */
    private String tenderNo;

    private String tenderId;

    private String title;

    /** 生效日起，默认 AGREEMENT_VALID_MONTHS（12 个月）后到期 */
    private LocalDate effectiveDate;

    private LocalDate expireDate;

    /** 0 未生效 / 1 生效中 / 2 过期 / 3 停用 */
    private String status;

    /** 份额合计（%） */
    private BigDecimal totalShare;

    private String changeReason;
}
