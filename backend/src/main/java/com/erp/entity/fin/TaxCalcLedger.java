package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 计税台账/手工计税入账申请（M9-1，spec tax-calculation，design D3 状态机）。
 * RECORDED=已留痕 → 补录对手方/期间/摘要 APPLYING → generateVoucher VOUCHED（DRAFT 凭证锚）。
 */
@Data
@TableName("erp_fin_tax_calc_ledger")
public class TaxCalcLedger {

    public static final String STATUS_RECORDED = "RECORDED";
    public static final String STATUS_APPLYING = "APPLYING";
    public static final String STATUS_VOUCHED = "VOUCHED";

    public static final String TRX_SALES = "SALES";
    public static final String TRX_PURCHASE = "PURCHASE";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String trxType;
    private String scope;
    private String calcType;
    private String rateKind;
    private LocalDate businessDate;
    private BigDecimal amount;
    private String grossFlag;
    private String taxCode;
    private BigDecimal taxRate;
    private BigDecimal taxableBase;
    private BigDecimal taxAmount;
    private String deductible;
    private String overTolerance;
    private String multiCandidate;
    private String counterparty;
    private String period;
    private String summary;
    private String voucherId;
    private String status;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
