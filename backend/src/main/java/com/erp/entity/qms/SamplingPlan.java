package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 抽样方案（GB/T 2828.1）：方案变更须新版本；抽样取严时与风险等级比例比对（BL-4.12-02）。
 */
@Getter
@Setter
@TableName("erp_qms_sampling_plan")
public class SamplingPlan extends BaseEntity {
    /** 方案编码（唯一） */
    @TableField("PLAN_CODE")
    private String planCode;

    /** 方案名称 */
    @TableField("NAME")
    private String name;

    /** 检验水平（默认 II） */
    @TableField("INSPECTION_LEVEL")
    private String inspectionLevel;

    /** AQL 等级 */
    @TableField("AQL_LEVEL")
    private String aqlLevel;

    /** 样本量规则 */
    @TableField("SAMPLE_SIZE_RULE")
    private String sampleSizeRule;

    /** Ac 判定规则 */
    @TableField("ACCEPT_RULE")
    private String acceptRule;

    /** Re 判定规则 */
    @TableField("REJECT_RULE")
    private String rejectRule;

    /** ACTIVE / RETIRED */
    @TableField("STATUS")
    private String status;

}
