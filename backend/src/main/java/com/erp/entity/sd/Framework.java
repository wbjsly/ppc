package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售框架协议（tasks 13.1/13.2，spec sales-framework-agreement，D11 与销售合同独立）。
 * FW_NO 创建后锁定；状态机 EFFECTIVE/TERMINATED/EXPIRED；
 * 变更与终止（S-4.3-11）以 PENDING_CHANGE 承载并经销售总监 L2 审批后执行。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_framework")
public class Framework extends BaseEntity {

    public static final String ST_EFFECTIVE = "EFFECTIVE";
    public static final String ST_TERMINATED = "TERMINATED";
    public static final String ST_EXPIRED = "EXPIRED";

    public static final String CHG_TOTAL = "TOTAL";
    public static final String CHG_PRICE = "PRICE";
    public static final String CHG_TERMINATE = "TERMINATE";

    /** 框架协议编号（生成后锁定） */
    private String fwNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private String title;
    private LocalDate effectiveDate;
    private LocalDate expireDate;
    /** 协议总量（=Σ行，变更重算） */
    private BigDecimal totalQty;
    private String status;
    /** 变更/终止审批实例（S-4.3-11 L2） */
    private String approvalId;
    /** 待审批变更 JSON {type,lines,reason} */
    private String pendingChange;
    /** 变更历史 JSON 数组（前后值留痕） */
    private String changeLog;
    private String terminateReason;
    private String terminateBy;
    private LocalDateTime terminateAt;
    private String remark;
}
