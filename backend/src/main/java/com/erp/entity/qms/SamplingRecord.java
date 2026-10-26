package com.erp.entity.qms;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 检验批抽样执行记录（6.3.1，spec inspection-lot 抽样执行记录 / FR-4.12-2-1 ④）。
 * 每批 0..n 条：INITIAL 首次 / ESCALATE 加大抽样 / RESAMPLE 补抽，多条并存不覆盖。
 * 抽样为可选留痕（探索 D2）：缺失不阻断录入/判定/放行。
 */
@Getter
@Setter
@TableName("erp_qms_sampling_record")
public class SamplingRecord extends BaseEntity {

    /** 批内序号 1..n */
    @TableField("SEQ")
    private Integer seq;

    @TableField("LOT_ID")
    private String lotId;

    /** 实际抽样数量（可大于理论样本量——加大抽样） */
    @TableField("ACTUAL_SAMPLE_QTY")
    private BigDecimal actualSampleQty;

    /** RANDOM 随机 / NINE_POINT 九点取样 / FULL 逐件 / OTHER 其他 */
    @TableField("SAMPLE_METHOD")
    private String sampleMethod;

    @TableField("SAMPLER_ID")
    private String samplerId;

    @TableField("SAMPLER_NAME")
    private String samplerName;

    @TableField("SAMPLED_TIME")
    private LocalDateTime sampledTime;

    /** INITIAL / ESCALATE / RESAMPLE */
    @TableField("TRIGGER_REASON")
    private String triggerReason;

    /** 备注（异常描述 / OTHER 方法说明） */
    @TableField("REMARK")
    private String remark;
}
