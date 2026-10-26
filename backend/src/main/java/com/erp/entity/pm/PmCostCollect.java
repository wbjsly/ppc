package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 五类成本统一归集（add-cost-collection-freeze，design D2；只增不改）。 */
@Getter
@Setter
@TableName("erp_pm_cost_collect")
public class PmCostCollect extends BaseEntity {

    private String projectId;
    /** 任务级；间接/部分外协为空（项目级） */
    private String wbsId;
    /** LABOR|MATERIAL|SUBCONTRACT|TRAVEL|INDIRECT */
    private String costType;
    private java.math.BigDecimal amount;
    /** MAT_ISSUE|SUB_PAYMENT|TRAVEL_CLAIM|HOURS_COLLECT|ALLOC */
    private String sourceType;
    /** 来源单据/归集行 ID */
    private String sourceId;
    private java.time.LocalDate collectDate;
    /** 会计凭证回写 */
    private String voucherId;
}
