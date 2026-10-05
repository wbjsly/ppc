package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检验批明细：检验项快照（自标准特性复制）+ 录入结果。
 * CTQ 项必须录实测值（BR-4.12-14）；数值偏离 ≥10×公差须二次确认（BR-4.12-15）。
 */
@Getter
@Setter
@TableName("erp_qms_lot_item")
public class LotItem extends BaseEntity {
    /** 检验批 ID */
    @TableField("LOT_ID")
    private String lotId;

    /** 序号 */
    @TableField("SEQUENCE_NO")
    private Integer sequenceNo;

    /** 特性名称 */
    @TableField("CHARACTERISTIC_NAME")
    private String characteristicName;

    /** 1=CTQ */
    @TableField("CTQ_FLAG")
    private String ctqFlag;

    /** 1=安全/法规 */
    @TableField("REGULATORY_FLAG")
    private String regulatoryFlag;

    /** NUMERIC / COUNT / LOOK */
    @TableField("SPEC_TYPE")
    private String specType;

    /** 下限 */
    @TableField("LOWER_LIMIT")
    private java.math.BigDecimal lowerLimit;

    /** 上限 */
    @TableField("UPPER_LIMIT")
    private java.math.BigDecimal upperLimit;

    /** 目标值 */
    @TableField("TARGET_VALUE")
    private java.math.BigDecimal targetValue;

    /** 单位 */
    @TableField("UNIT")
    private String unit;

    /** 检验方法 */
    @TableField("METHOD_NAME")
    private String methodName;

    /** 器具类型 */
    @TableField("INSTRUMENT_TYPE")
    private String instrumentType;

    /** 实测值 / 不合格数 */
    @TableField("MEASURED_VALUE")
    private java.math.BigDecimal measuredValue;

    /** PENDING / PASS / FAIL */
    @TableField("JUDGE")
    private String judge;

    /** 1=数据异常已二次确认 */
    @TableField("ABNORMAL_CONFIRM")
    private String abnormalConfirm;

    /** 异常确认前原值 */
    @TableField("ABNORMAL_ORIGINAL")
    private java.math.BigDecimal abnormalOriginal;

    /** 使用器具 ID */
    @TableField("INSTRUMENT_ID")
    private String instrumentId;

    /** 使用器具编码 */
    @TableField("INSTRUMENT_CODE")
    private String instrumentCode;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

    /** 检验人 */
    @TableField("INSPECT_BY")
    private String inspectBy;

    /** 检验时间 */
    @TableField("INSPECT_TIME")
    private LocalDateTime inspectTime;

}
