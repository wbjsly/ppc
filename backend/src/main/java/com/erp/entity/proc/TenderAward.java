package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 招标中标子表（change add-framework-agreement-order，design D2：多中标人）。
 * 定标时人工录入每家中标人与份额，Σ份额 = 100（服务端校验）；
 * TENDER_ID × SUPPLIER_ID 唯一。头 awardSupplierId/awardPrice 保留为份额最大者快照。
 * <p>偏差出处见 spec: tender-bidding-management「多中标人定标录入」。</p>
 */
@Data
@TableName("erp_proc_tender_award")
public class TenderAward implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String tenderId;

    /** 中标顺序（人工录入顺序，份额从大到小） */
    private Integer lineNo;

    private String supplierId;

    private String supplierName;

    /** 中标单价 = 该投标方最终轮有效报价（服务端校验相等） */
    private BigDecimal awardPrice;

    /** 份额分配（%），全部中标人合计 = 100 */
    private BigDecimal sharePct;

    private String remark;

    private String createBy;

    private LocalDateTime createDate;

    private String updateBy;

    private LocalDateTime updateDate;

    private Integer verNo;
}
