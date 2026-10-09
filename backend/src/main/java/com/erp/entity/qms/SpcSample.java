package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * SPC 采样组（规则版，design D10）：≥25 组用 X̄±3σ，否则规格折算标 PRE_CONTROL。
 * 组含均值/极值与控制限快照，供趋势预警与图表数据源。
 */
@Getter
@Setter
@TableName("erp_qms_spc_sample")
public class SpcSample extends BaseEntity {
    /** 特性编码 */
    @TableField("CHAR_CODE")
    private String charCode;

    /** 特性名称 */
    @TableField("CHAR_NAME")
    private String charName;

    /** 物料编码 */
    @TableField("MATERIAL_CODE")
    private String materialCode;

    /** 工序 */
    @TableField("PROCESS_ID")
    private String processId;

    /** 组号 */
    @TableField("GROUP_NO")
    private Integer groupNo;

    /** 组内样本数 */
    @TableField("SAMPLE_QTY")
    private Integer sampleQty;

    /** 组均值 */
    @TableField("MEAN_VALUE")
    private java.math.BigDecimal meanValue;

    /** 组最小值 */
    @TableField("MIN_VALUE")
    private java.math.BigDecimal minValue;

    /** 组最大值 */
    @TableField("MAX_VALUE")
    private java.math.BigDecimal maxValue;

    /** 规格下限 */
    @TableField("SPEC_LOWER")
    private java.math.BigDecimal specLower;

    /** 规格上限 */
    @TableField("SPEC_UPPER")
    private java.math.BigDecimal specUpper;

    /** 规格中心 */
    @TableField("SPEC_TARGET")
    private java.math.BigDecimal specTarget;

    /** NORMAL / PRE_CONTROL */
    @TableField("LIMIT_MODE")
    private String limitMode;

    /** 中心线 */
    @TableField("CENTER_VALUE")
    private java.math.BigDecimal centerValue;

    /** 控制上限 */
    @TableField("UCL")
    private java.math.BigDecimal ucl;

    /** 控制下限 */
    @TableField("LCL")
    private java.math.BigDecimal lcl;

    /** 采样时间 */
    @TableField("SAMPLE_TIME")
    private LocalDateTime sampleTime;

    /** 采样人 */
    @TableField("OPERATOR_NAME")
    private String operatorName;

    /** 备注 */
    @TableField("REMARK")
    private String remark;

}
