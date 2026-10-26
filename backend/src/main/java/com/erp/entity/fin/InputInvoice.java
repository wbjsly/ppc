package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 进项发票台账（M9-2-2，spec invoice-management，D1 全生命周期状态机）。
 * 登记（自动三单匹配）→ 查验留痕 → 认证登记（C-4.14-10/07/11 三卡控）→ 批量进项凭证（TAXIN 锚）。
 */
@Data
@TableName("erp_fin_input_invoice")
public class InputInvoice {

    public static final String MATCH_MATCHED = "MATCHED";
    public static final String MATCH_DIFF = "DIFF";
    /** 已接收未查验未入库（add-input-certification D1：FR-4.14-3-2 查验闸口前置态） */
    public static final String CERT_PENDING_INSPECT = "PENDING_INSPECT";
    public static final String CERT_PENDING = "PENDING_CERT";
    public static final String CERT_CERTIFIED = "CERTIFIED";
    public static final String CERT_NOT_DEDUCTIBLE = "NOT_DEDUCTIBLE";
    /** 查验失败终态（留档不删，阻断认证/凭证/勾稽——FR-4.14-3-2 L1 阻断入账） */
    public static final String CERT_INVALID = "INVALID";
    /** 三单匹配待查验后执行（PENDING_INSPECT 行的 MATCH_STATUS 占位） */
    public static final String MATCH_PENDING = "PENDING";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String invoiceNo;
    private String supplierId;
    private String supplierName;
    private LocalDate invoiceDate;
    private BigDecimal amount;
    private BigDecimal taxAmount;
    private String poId;
    private String poNo;
    private String matchStatus;
    private BigDecimal qtyDiffRate;
    private BigDecimal amountDiffRate;
    private String matchDetail;
    private String inspectMethod;
    private String inspectResult;
    private LocalDateTime inspectAt;
    private String certStatus;
    private LocalDate certDate;
    private String certPeriod;
    private String deductible;
    private String certNote;
    private String voucherId;
    /** 累计转出税额（进项税额转出 FR-4.14-3-6，≤ 已抵扣税额） */
    private BigDecimal transferOutAmount;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
