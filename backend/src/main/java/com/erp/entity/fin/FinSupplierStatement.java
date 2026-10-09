package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 供应商对账单（2.7.3，spec supplier-statement-reconciliation，C-4.2-15）。
 * 冻结派生自「存在 EXCEPTION 单」；双签两节点字段齐 → CLOSED 解冻。
 */
@Getter
@Setter
@TableName("erp_fin_supplier_statement")
public class FinSupplierStatement extends BaseEntity {

    public static final String ST_ACCEPTED = "ACCEPTED";
    public static final String ST_EXCEPTION = "EXCEPTION";
    public static final String ST_CLOSED = "CLOSED";

    /** 对账单号 ST+yyyyMMdd+流水 */
    private String stmtNo;
    private String supplierId;
    private String supplierName;
    private LocalDate stmtDate;
    /** 供应商对账单金额（手工录入） */
    private BigDecimal stmtAmount;
    /** 比对基准 = 该供应商 OPEN 暂估余额合计 */
    private BigDecimal baseAmount;
    private BigDecimal diffAmount;
    private BigDecimal diffRate;
    /** ACCEPTED / EXCEPTION / CLOSED */
    private String status;
    private String pmConfirmBy;
    private LocalDateTime pmConfirmAt;
    private String pmOpinion;
    private String finConfirmBy;
    private LocalDateTime finConfirmAt;
    private String finOpinion;
    private String remark;

    /** 系统匹配结果 JSON（spec supplier-statement-reconciliation ADDED，design D11） */
    private String matchResult;
    private String matchBy;
    private java.time.LocalDateTime matchAt;
}
