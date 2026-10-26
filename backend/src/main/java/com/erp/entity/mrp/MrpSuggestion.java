package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * MRP 建议行（change add-mrp-demand-planning，spec mrp-demand-planning / 00-erp-spec FR-4.5-2-6/7；迁移 119）。
 * 一表喂三菜单：TYPE=PURCHASE(5.3.1) / PRODUCTION(5.3.2) / EXCESS(仅异常页，不可转正)。
 * 状态机（CAS）：PENDING → CONFIRMED → CONVERTED；PENDING/CONFIRMED → CANCELLED / SUPERSEDED。
 * 净算五值留痕（D2）；OVERDUE 逾期标记（FR-4.5-2-5）；异常处置留痕（仅 EXCESS/OVERDUE 行）。
 */
@Getter
@Setter
@TableName("erp_mrp_suggestion")
public class MrpSuggestion extends BaseEntity {

    public static final String TYPE_PURCHASE = "PURCHASE";
    public static final String TYPE_PRODUCTION = "PRODUCTION";
    public static final String TYPE_EXCESS = "EXCESS";

    public static final String ST_PENDING = "PENDING";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_CONVERTED = "CONVERTED";
    public static final String ST_CANCELLED = "CANCELLED";
    public static final String ST_SUPERSEDED = "SUPERSEDED";

    private String runId;
    private String itemCode;
    private String itemName;
    /** 物料采购类型 BUY/MAKE（分派依据） */
    private String purchaseType;
    /** 建议类型 */
    private String type;
    /** 需求量（SO+ROP 补货+上层展开聚合） */
    private BigDecimal demandQty;
    /** 现有库存（ΣAVAILABLE_QTY） */
    private BigDecimal onHandQty;
    /** 在制（本期恒 0 桩，proposal D4） */
    private BigDecimal inProcessQty;
    /** 在途（PO APPROVED 行 QTY−RECEIVED_QTY） */
    private BigDecimal inTransitQty;
    /** 净需求（EXCESS 行为负值） */
    private BigDecimal netReq;
    /** 需求日期 */
    private LocalDate reqDate;
    /** 建议下单日期（逐层倒排） */
    private LocalDate orderDate;
    /** 逾期标记 1=下单日<今日 */
    private String overdueFlag;
    /** 建议量（替代分配后） */
    private BigDecimal suggestQty;
    private String status;
    /** 原建议量（确认改量留痕） */
    private BigDecimal origSuggestQty;
    /** 确认量（可改） */
    private BigDecimal confirmQty;
    /** 确认需求日期（可改） */
    private LocalDate confirmDate;
    private String confirmBy;
    private LocalDateTime confirmAt;
    /** 取消原因（必填留痕） */
    private String cancelReason;
    private String cancelBy;
    private LocalDateTime cancelAt;
    /** 转正单号（PR 或 PMO 前缀流水） */
    private String targetNo;
    /** 已关联工单号（PMO 转正后建单回写，NULL=未建；120 列，D6 PMO 对接） */
    private String moNo;
    private LocalDateTime convertAt;
    /** 取代者 RUN_NO（重跑取代规则） */
    private String supersededByRun;
    /** 替代溯源/兜底提示备注 */
    private String remark;
    /** 异常已处理标记 */
    private String handledFlag;
    /** 处理备注（必填） */
    private String handledNote;
    private String handledBy;
    private LocalDateTime handledAt;
}
