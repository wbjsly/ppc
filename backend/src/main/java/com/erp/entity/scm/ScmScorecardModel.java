package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 记分卡模型（spec supplier-scorecard FR-4.9-7-1：权重/阈值/品类差异化/版本）。 */
@Getter
@Setter
@TableName("erp_scm_scorecard_model")
public class ScmScorecardModel extends BaseEntity {

    public static final String ST_ACTIVE = "ACTIVE";

    private String modelCode;
    private String name;
    private String categoryCode;
    private BigDecimal wQuality;
    private BigDecimal wDelivery;
    private BigDecimal wCost;
    private BigDecimal wResponse;
    /** 成本维度内价格竞争力指数权重（design D7 默认 60，其余为降本） */
    private BigDecimal costIndexWeight;
    private BigDecimal threshA;
    private BigDecimal threshB;
    private BigDecimal threshC;
    private Integer version;
    private String status;
    private String approvalStatus;
    private LocalDateTime effectiveAt;
    private String remark;
}
