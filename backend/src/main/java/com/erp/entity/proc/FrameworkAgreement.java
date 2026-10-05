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

    /** 状态（design D4 状态机）：1 生效中 / 2 临期（到期≤EXPIRY_REMIND_DAYS）/ 3 已到期 / 4 已终止 */
    private String status;

    /** 份额合计（%） */
    private BigDecimal totalShare;

    private String changeReason;

    // ---- 执行治理（change add-framework-agreement-order，design D4/D5）----

    /** 来源：TENDER 招标生成 / MANUAL 手工创建 */
    private String source;

    /** 续签来源协议 ID（renew 时指向原协议） */
    private String renewOf;

    /** 终止原因（status=4 必填） */
    private String stopReason;

    /** 最近变更/续签说明 */
    private String changeNote;
}
