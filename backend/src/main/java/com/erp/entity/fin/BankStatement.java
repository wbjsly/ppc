package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 银行对账单（M8-7，spec bank-reconciliation，design D1）。
 * 账户+期间唯一；余额锚点 = 对账单载明值；确认状态机 DRAFT→CONFIRMED→AUDITED。
 */
@Data
@TableName("erp_fin_bank_statement")
public class BankStatement {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_AUDITED = "AUDITED";

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String bankAccountId;
    private String accountName;
    private String accountNo;
    private String currency;
    /** 对账期间 YYYYMM */
    private String period;
    private String stmtNo;
    private BigDecimal openingBalance;
    private BigDecimal closingBalance;
    private String status;
    /** 1=差异率超 TOLERANCE_DEFAULT（C-0-02 进差异处理） */
    private String overTolerance;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String auditBy;
    private LocalDateTime auditAt;
    private String revokeReason;
    private String revokeBy;
    private LocalDateTime revokeAt;
    private String remark;
    private String createBy;
    private LocalDateTime createDate;
    private String updateBy;
    private LocalDateTime updateDate;
    private Integer verNo;
    private String delFlag;
}
