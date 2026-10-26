package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 申报跨期调整单（BR-4.14-31 快照留痕，spec tax-filing）。
 * 锁定期（APPROVED 起）修改申报数据的唯一通道：调整前后快照 JSON + 审批。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_tax_return_adj")
public class TaxReturnAdj extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING_APPROVAL = "PENDING_APPROVAL";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** 调整单号 TADJ+yyyyMMdd+流水 */
    private String adjNo;
    private String returnId;
    private String period;
    /** 调整前申报数据快照 JSON */
    private String beforeJson;
    /** 调整后申报数据快照 JSON */
    private String afterJson;
    private String reason;
    private String status;
    private String apprId;
    private String remark;
}
