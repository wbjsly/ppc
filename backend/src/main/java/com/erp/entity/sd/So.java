package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 销售订单头（SO）。本变更提前建表：4.9 报价转化需落 SO 草稿（D12 自动串联）。
 * 四创建入口（手工/报价转化/EDI/框架下达）与完整状态机、审批、确认在第 7 组展开。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_so")
public class So extends BaseEntity {

    public static final String SRC_QUOTE = "QUOTE";
    public static final String SRC_MANUAL = "MANUAL";
    public static final String SRC_EDI = "EDI";
    public static final String SRC_FRAMEWORK = "FRAMEWORK";

    public static final String ST_DRAFT = "DRAFT";
    /** 审批中（7.6 提交审批后） */
    public static final String ST_PENDING = "PENDING";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_PARTIAL_SHIPPED = "PARTIAL_SHIPPED";
    public static final String ST_SHIPPED = "SHIPPED";
    public static final String ST_SIGNED = "SIGNED";
    public static final String ST_INVOICED = "INVOICED";
    public static final String ST_CLOSED = "CLOSED";
    /** 挂起态：信用冻结（PREV_STATUS 记前一稳定状态） */
    public static final String ST_CREDIT_FREEZE = "CREDIT_FREEZE";
    /** 挂起态：变更中 */
    public static final String ST_CHANGING = "CHANGING";
    public static final String ST_CANCELLED = "CANCELLED";

    /** 稳定状态集合（挂起态判定与回退基准，业务逻辑 6） */
    public static final java.util.Set<String> STABLE = java.util.Set.of(
            ST_DRAFT, ST_CONFIRMED, ST_PARTIAL_SHIPPED, ST_SHIPPED,
            ST_SIGNED, ST_INVOICED, ST_CLOSED, ST_CANCELLED);
    /** 挂起态集合（信用冻结 / 变更中） */
    public static final java.util.Set<String> SUSPENDED = java.util.Set.of(
            ST_CREDIT_FREEZE, ST_CHANGING);

    private String soNo;
    private String sourceType;
    /** 来源单据 ID（报价转化 = 报价 ID） */
    private String sourceId;
    private String oppId;
    /** 来源合同 ID（14.3 合同下达 SO 回写；10.8 计划达成链路） */
    private String contractId;
    private String contractNo;
    /** 最近发票号（10.4 开票回写） */
    private String invoiceNo;
    private String customerId;
    private String customerCode;
    private String customerName;
    private String orderType;
    private String paymentTerms;
    private String tradeTerms;
    private BigDecimal totalAmount;
    private String status;
    /** 挂起前的稳定状态（业务逻辑 6：解除后回前一状态） */
    private String prevStatus;
    /** 在途审批实例（erp_sys_approval.ID） */
    private String approvalId;
    /** 订单毛利率（小数，链尾加签判定） */
    private BigDecimal marginRate;
    /** 付款条件差异的销售主管确认人（BR-4.3-25） */
    private String payConfirmBy;
    private java.time.LocalDateTime payConfirmAt;
    private String payConfirmReason;
    /** 关闭原因（手动关闭必填） */
    private String closeReason;
    private String closedBy;
    private java.time.LocalDateTime closedAt;
    /** 关闭的销售经理确认人（手动关闭） */
    private String closeConfirmBy;
    /** 事件快照版本（outbox recordVersion） */
    private Integer eventVersion;
    private String remark;

    // ---------- 非持久化辅助字段（服务层填充） ----------
    /** 待办清单里展示用：是否挂起 */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private Boolean suspended;
    /** 挂起原因（冻结缺口/变更说明） */
    @com.baomidou.mybatisplus.annotation.TableField(exist = false)
    private String suspendReason;
}
