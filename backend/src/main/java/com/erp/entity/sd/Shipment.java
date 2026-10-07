package com.erp.entity.sd;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 销售发货单（tasks 9.1，spec sales-shipment，D8 销售侧策略与确认）。
 * 三种生成策略：PARTIAL 按未发余量 / MERGE 同客户同仓多 SO / BATCH 按分批方案行。
 * 状态机：DRAFT → POSTED(出库过账) → CONFIRMED(发货确认) → SIGNED(签收) / REJECTED(拒收)。
 * 拣货作业由库存域 4.7 承载（页面明示边界，不生成拣货任务单）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_sd_shipment")
public class Shipment extends BaseEntity {

    public static final String TYPE_PARTIAL = "PARTIAL";
    public static final String TYPE_MERGE = "MERGE";
    public static final String TYPE_BATCH = "BATCH";
    public static final String TYPE_EXCHANGE = "EXCHANGE";
    /** 框架下达单发起的分批发货（13.6，执行视图，不走 SO/预留） */
    public static final String TYPE_FRAMEWORK = "FRAMEWORK";

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_POSTED = "POSTED";
    public static final String ST_CONFIRMED = "CONFIRMED";
    public static final String ST_SIGNED = "SIGNED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_CANCELLED = "CANCELLED";

    private String shipNo;
    private String shipType;
    private String customerId;
    private String customerCode;
    private String customerName;
    private String warehouseCode;
    private String status;
    private BigDecimal totalQty;
    /** 应收事件金额 = Σ 行数量×单价（AR.CONFIRMED 口径） */
    private BigDecimal totalAmt;
    private String logisticsCo;
    private String logisticsNo;
    private LocalDateTime shipAt;
    /** 签收时限 = 确认日 + SIGN_TIMEOUT_DAYS */
    private LocalDate signDueDate;
    private LocalDateTime signAt;
    /** 1 = 超时未签收已预警 */
    private String signWarned;
    private String exceptNote;
    private LocalDateTime exceptNotifyAt;
    private String rejectReason;
    private LocalDateTime rejectedAt;
    /** 拒收生成的退货申请 ID */
    private String returnId;
    /** 来源框架协议 / 下达单（13.6 执行视图回链，057 动态列） */
    private String frameworkId;
    private String releaseId;
    private String releaseNo;
    private String postBy;
    private LocalDateTime postAt;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String remark;
}
