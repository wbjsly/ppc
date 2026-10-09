package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * 检验特性（CTQ / 安全法规标记）：CTQ 必检且必须录实测值（BR-4.12-14）；
 * REGULATORY_FLAG=1 的 CTQ 不合格禁止让步接收（BR-4.12-26）。
 */
@Getter
@Setter
@TableName("erp_qms_characteristic")
public class Characteristic extends BaseEntity {
    /** 版本 ID */
    @TableField("VERSION_ID")
    private String versionId;

    /** 序号 */
    @TableField("SEQUENCE_NO")
    private Integer sequenceNo;

    /** 特性名称 */
    @TableField("CHARACTERISTIC_NAME")
    private String characteristicName;

    /** 1=CTQ 关键质量特性 */
    @TableField("CTQ_FLAG")
    private String ctqFlag;

    /** 1=安全/法规类（禁让步） */
    @TableField("REGULATORY_FLAG")
    private String regulatoryFlag;

    /** NUMERIC 计量 / COUNT 计数 / LOOK 外观 */
    @TableField("SPEC_TYPE")
    private String specType;

    /** 规格下限 */
    @TableField("LOWER_LIMIT")
    private java.math.BigDecimal lowerLimit;

    /** 规格上限 */
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

}
