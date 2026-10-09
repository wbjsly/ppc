package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 线索评分模型（spec crm-lead-management，BR-4.8-07 按行业/产品线差异化）。
 * 权重合计必须 = 100%，否则 L1 硬阻断；模型变更留版本。
 */
@Getter
@Setter
@TableName("erp_crm_lead_score_model")
public class LeadScoreModel extends BaseEntity {

    private String modelKey;
    private String modelName;
    private String industry;
    private String productLine;

    // 注意：getWNeed() 形式的 getter 在 JavaBeans 规则下属性名是 "WNeed"（前两字符大写不反规范化），
    // 前端与接口一律用小写 wNeed，故显式指定 JSON 名
    @com.fasterxml.jackson.annotation.JsonProperty("wNeed")
    private Integer wNeed;
    @com.fasterxml.jackson.annotation.JsonProperty("wBudget")
    private Integer wBudget;
    @com.fasterxml.jackson.annotation.JsonProperty("wChain")
    private Integer wChain;
    @com.fasterxml.jackson.annotation.JsonProperty("wUrgency")
    private Integer wUrgency;
    @com.fasterxml.jackson.annotation.JsonProperty("wCompete")
    private Integer wCompete;

    private Integer gradeAMin;
    private Integer gradeBMin;
    private Integer gradeCMin;

    private Integer version;
    private Boolean isCurrent;
    private String status;
    private String remark;

    /** 权重合计（校验用，非持久化字段） */
    public int weightSum() {
        return nvl(wNeed) + nvl(wBudget) + nvl(wChain) + nvl(wUrgency) + nvl(wCompete);
    }

    private int nvl(Integer v) {
        return v == null ? 0 : v;
    }
}
