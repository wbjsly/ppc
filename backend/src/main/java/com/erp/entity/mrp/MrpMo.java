package com.erp.entity.mrp;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 生产工单头（change add-work-order-management，spec work-order-management / 00-erp-spec 4.5-3；迁移 120）。
 * 六态状态机：PLANNED → PENDING(审批) → CONFIRMED → RELEASED → COMPLETED → CLOSED，
 * 旁路 HOLD（挂起/恢复）与 CANCELLED（终态）；白名单 + CAS 迁移（MoStatusRules）。
 * PLANNED_MO_NO = 来源 PMO 单号（5.3 D6 对接兑现，双向关联 erp_mrp_suggestion.MO_NO）。
 * SHORTAGE_FLAG：缺料不阻断创建/释放，仅标记「缺料待料」（L2496 场景；L1 阻断归 5.6 派工 C-4.5-11）。
 * 拆分子单经 SPLIT_FROM_MO 构成拆分链（C-4.5-15），链内豁免唯一在途。
 */
@Getter
@Setter
@TableName("erp_mrp_mo")
public class MrpMo extends BaseEntity {

    public static final String ST_PLANNED = "PLANNED";
    public static final String ST_PENDING = "PENDING";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_RELEASED = "RELEASED";
    public static final String ST_HOLD = "HOLD";
    public static final String ST_COMPLETED = "COMPLETED";
    public static final String ST_CLOSED = "CLOSED";
    public static final String ST_CANCELLED = "CANCELLED";

    /** 工单号 MO-YYYYMMDD-NNN（冲突自动递增） */
    private String moNo;
    private String productCode;
    private String productName;
    /** 计划数量（>0） */
    private BigDecimal qty;
    /** 计划开工日期 */
    private LocalDate planStartDate;
    /** 计划完工日期（<今日 L1 阻断，C-4.5-06） */
    private LocalDate planEndDate;
    /** 优先级 1~9（1 最高） */
    private Integer priority;
    private String status;
    /** 来源 PMO 单号（PMO 入口建单留痕，手工建单为空） */
    private String plannedMoNo;
    /** 缺料待料标记 1=缺料 */
    private String shortageFlag;
    /** BOM 快照来源版本 ID（BR-4.5-16 隔离） */
    private String sourceBomId;
    /** BOM 快照版本号（主.次） */
    private String bomVersion;
    /** 路线快照来源版本 ID */
    private String sourceRoutingId;
    /** 完工确认合格产出（5.4.5 手动完工填入） */
    private BigDecimal qualifiedQty;
    /** 变更原因（拆分等操作留痕） */
    private String changeReason;
    /** 挂起原因（必填） */
    private String holdReason;
    /** 取消原因（必填） */
    private String cancelReason;
    /** 审批驳回意见 */
    private String rejectReason;
    /** 拆分来源工单号（C-4.5-15 拆分链） */
    private String splitFromMo;
    /** 合并来源（预留，D6 本期不写） */
    private String mergedFromMo;

    private String submitBy;
    private LocalDateTime submitAt;
    private String approveBy;
    private LocalDateTime approveAt;
    private String releaseBy;
    private LocalDateTime releaseAt;
    private String completeBy;
    private LocalDateTime completeAt;
    private String closeBy;
    private LocalDateTime closeAt;
}
