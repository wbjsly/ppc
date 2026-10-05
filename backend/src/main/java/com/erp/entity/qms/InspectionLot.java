package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检验批（spec inspection-lot）：GR 登记提交同事务生成，1 个 GR 行 = 1 批；
 * 生成即固化标准版本与抽样方案快照（BR-4.12-56），标准升级不回写在途任务。
 * STATUS：BLOCKED(无标准) / PENDING / INPUTTING / REVIEWING(边界复核) /
 * RELEASED / SKIPPED(免检) / CONCESSION / FROZEN(不合格) / CANCELLED。
 * 检验时限 DUE_TIME 自 GR 登记提交起算（偏差 D1）。
 */
@Getter
@Setter
@TableName("erp_qms_inspection_lot")
public class InspectionLot extends BaseEntity {
    /** IL + yyyyMMdd + - + 6 位流水 */
    @TableField("LOT_NO")
    private String lotNo;

    /** IQC / IPQC / OQC */
    @TableField("LOT_TYPE")
    private String lotType;

    /** GR / MANUAL / PLAN / SHIPMENT */
    @TableField("SOURCE_TYPE")
    private String sourceType;

    /** 来源收货单 */
    @TableField("GR_ID")
    private String grId;

    /** 来源收货行 */
    @TableField("GR_LINE_ID")
    private String grLineId;

    /** IPQC/OQC 来源类型（PLAN/WORKORDER/SHIPMENT） */
    @TableField("REF_TYPE")
    private String refType;

    /** IPQC/OQC 来源 ID */
    @TableField("REF_ID")
    private String refId;

    /** 物料 ID */
    @TableField("MATERIAL_ID")
    private String materialId;

    /** 物料编码 */
    @TableField("ITEM_CODE")
    private String itemCode;

    /** 物料名称 */
    @TableField("ITEM_NAME")
    private String itemName;

    /** 供应商 ID */
    @TableField("SUPPLIER_ID")
    private String supplierId;

    /** 供应商名称 */
    @TableField("SUPPLIER_NAME")
    private String supplierName;

    /** 批次号 */
    @TableField("BATCH_NO")
    private String batchNo;

    /** 待检数量（实收） */
    @TableField("LOT_QTY")
    private java.math.BigDecimal lotQty;

    /** 风险等级快照 A/B/C */
    @TableField("RISK_GRADE")
    private String riskGrade;

    /** 命中标准 ID（BLOCKED 时为空） */
    @TableField("STANDARD_ID")
    private String standardId;

    /** 标准编码快照 */
    @TableField("STANDARD_CODE")
    private String standardCode;

    /** 标准版本号快照 */
    @TableField("STANDARD_VERSION")
    private Integer standardVersion;

    /** 适用范围命中依据 */
    @TableField("APPLICABILITY_BASIS")
    private String applicabilityBasis;

    /** 抽样取严依据（比例/AQL 字码）C-4.12-13 */
    @TableField("SAMPLE_BASIS")
    private String sampleBasis;

    /** 检验水平 */
    @TableField("INSPECTION_LEVEL")
    private String inspectionLevel;

    /** AQL 等级 */
    @TableField("AQL_LEVEL")
    private String aqlLevel;

    /** 执行样本量（取严后） */
    @TableField("SAMPLE_QTY")
    private java.math.BigDecimal sampleQty;

    /** Ac 允许不合格数 */
    @TableField("AC_VALUE")
    private Integer acValue;

    /** Re 拒收数 */
    @TableField("RE_VALUE")
    private Integer reValue;

    /** NORMAL / TIGHTENED / RELAXED */
    @TableField("STRICTNESS")
    private String strictness;

    /** 1=免检命中 */
    @TableField("EXEMPT_FLAG")
    private String exemptFlag;

    /** 免检记录 ID */
    @TableField("EXEMPT_ID")
    private String exemptId;

    /** 见类注释状态机 */
    @TableField("STATUS")
    private String status;

    /** PENDING / PASS / FAIL */
    @TableField("RESULT")
    private String result;

    /** NORMAL / HIGH（紧急物料） */
    @TableField("PRIORITY")
    private String priority;

    /** 检验员 */
    @TableField("INSPECTOR_ID")
    private String inspectorId;

    /** 检验员姓名 */
    @TableField("INSPECTOR_NAME")
    private String inspectorName;

    /** 检验时限（自登记提交起算，D1） */
    @TableField("DUE_TIME")
    private LocalDateTime dueTime;

    /** 1=超时 */
    @TableField("OVERDUE_FLAG")
    private String overdueFlag;

    /** 放行人 */
    @TableField("RELEASED_BY")
    private String releasedBy;

    /** 放行时间 */
    @TableField("RELEASED_DATE")
    private LocalDateTime releasedDate;

    /** 边界复核人 */
    @TableField("REVIEW_BY")
    private String reviewBy;

    /** 复核时间 */
    @TableField("REVIEW_DATE")
    private LocalDateTime reviewDate;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
