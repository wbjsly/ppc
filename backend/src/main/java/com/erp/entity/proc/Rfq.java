package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 询价单（2.2.1，表 erp_proc_rfq，FR-4.2-2-1 + C-4.2-01 + BR-4.2-13）。
 * 状态机见 RfqStateMachine；截止日懒 sweep 锁价。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_proc_rfq")
public class Rfq extends BaseEntity {

    /** RFQ-YYYYMMDD-NNN 按日流水，全局唯一 */
    private String rfqNo;

    private String prId;

    /** DRAFT / SENT / QUOTING / QUOTED_CLOSED / AWARDED / CLOSED */
    private String status;

    private LocalDate quoteDeadline;

    /** OFFLINE / ONLINE（ONLINE 发送状态桩） */
    private String sendMode;

    private String sendStatus;

    private LocalDateTime sentDate;

    /** 矩阵含税试算税码编号（可空，未填=待税率） */
    private String taxCodeNo;

    /** 技术标准/图纸（附件桩文本） */
    private String techNote;

    /** 紧急放行标识（clearance 桩消费，允许最低 1 家） */
    private String emergencyFlag;

    /** BR-4.2-13 锁价后不足 MIN */
    private String insufficientFlag;

    // ---- 定标快照 ----
    private Integer weightPrice;
    private Integer weightDelivery;
    private String awardSupplierId;
    private BigDecimal awardPrice;
    private String analysisNo;
    private String analysisConclusion;

    private String closeReason;
}
