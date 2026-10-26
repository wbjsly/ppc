package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 间接分摊规则（add-cost-collection-freeze，FR-4.8-5-5；每项目至多一条生效）。 */
@Getter
@Setter
@TableName("erp_pm_alloc_rule")
public class PmAllocRule extends BaseEntity {

    private String projectId;
    /** HOURS 工时占比 | BUDGET 预算占比 */
    private String ruleType;
    private String status;
}
