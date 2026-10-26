package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 出口业务一票一档（FR-4.14-7-1，spec export-tax-refund，离线手工登记代理口径）。
 * FOB 折算（BR-4.14-41）：CIF/CFR 扣运费/保险费/佣金；退税率按商品大类 SysParam；
 * 单证三 flag + 委托代理证明；申报截止 = 报关次年 4 月（C-4.14-13 起算）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_export_deal")
public class ExportDeal extends BaseEntity {

    public static final String TERM_FOB = "FOB";
    public static final String TERM_CIF = "CIF";
    public static final String TERM_CFR = "CFR";

    /** 档案号 EXP+yyyyMMdd+流水 */
    private String dealNo;
    private String customerId;
    private String customerName;
    /** 出口发票号 */
    private String invoiceNo;
    /** 报关单号 */
    private String declNo;
    private LocalDate declDate;
    private String tradeTerm;
    private String currency;
    private BigDecimal fxRate;
    private BigDecimal grossAmount;
    private BigDecimal freight;
    private BigDecimal insurance;
    private BigDecimal commission;
    /** FOB 折算人民币 */
    private BigDecimal fobCny;
    /** 商品大类（退税率 SysParam 键） */
    private String itemCategory;
    private BigDecimal refundRate;
    private BigDecimal refundTax;
    private String docDecl;
    private String docInvoice;
    private String docReceipt;
    /** 代理出口证明（委托出口必收） */
    private String docAgent;
    private LocalDate receiptDate;
    /** 报关单与出口发票不一致（L4 预警） */
    private String mismatchFlag;
    private String mismatchNote;
    /** 申报截止日（次年 4 月申报期截止） */
    private LocalDate expireDate;
    /** 所属退税申报单锚 */
    private String refundApplyNo;
    private String remark;
}
