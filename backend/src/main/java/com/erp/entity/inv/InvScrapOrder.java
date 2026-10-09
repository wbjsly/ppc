package com.erp.entity.inv;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 报废出库单（4.5.4，spec scrap-order，design D5/D6 按原因分门槛）。
 * DRAFT → APPROVED → POSTED → DISPOSED（+CANCELLED）：
 * STALE 呆滞 → 三方会签（ApprovalNodeSpec 一 SEQ×3 JOINT，C-4.4-14 未会签过账 422）；
 * QUALITY 质量 → 挂 NCR（处置=SCRAP 才放行，不重复会签）；
 * DAMAGE/OTHER → 简化批准（WAREHOUSE/ADMIN）免会签（偏差 D1）。
 * POSTED 生成凭证一（借1901/贷1403），DISPOSED 核销凭证二（借6711/贷1901）。
 */
@Getter
@Setter
@TableName("erp_inv_scrap_order")
public class InvScrapOrder extends BaseEntity {

    public static final String R_STALE = "STALE";
    public static final String R_QUALITY = "QUALITY";
    public static final String R_DAMAGE = "DAMAGE";
    public static final String R_OTHER = "OTHER";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_POSTED = "POSTED";
    public static final String ST_DISPOSED = "DISPOSED";
    public static final String ST_CANCELLED = "CANCELLED";

    /** SC+yyyyMMdd+流水 */
    private String scrapNo;
    private String reason;
    /** NCR 关联号（QUALITY 必填） */
    private String ncrNo;
    /** 关联追溯单号（召回处置闭环，可空；迁移 115，spec trace-recall 报废处置需求） */
    private String traceNo;
    private String warehouseCode;
    private String status;
    /** 三方会签审批实例 ID（仅 STALE） */
    private String apprId;
    private String cancelReason;
    private String postBy;
    private LocalDateTime postAt;
    private String disposeBy;
    private LocalDateTime disposeAt;
    private BigDecimal totalQty;
    /** Σ行 QTY×UNIT_COST（两步凭证金额依据） */
    private BigDecimal totalAmount;
    private String remark;
}
