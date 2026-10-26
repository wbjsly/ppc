package com.erp.entity.crm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 线索自动分配规则（等级+区域/行业 → 目标销售集，优先级首条命中）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_crm_assign_rule")
public class CrmAssignRule extends BaseEntity {

    /** 适用等级 A/B/C */
    private String grade;
    /** 区域条件（空=通配） */
    private String region;
    /** 行业条件（空=通配） */
    private String industry;
    /** 目标销售账号集（逗号 分隔） */
    private String targetEmpIds;
    /** 数字小者先匹配 */
    private Integer priority;
    private String enabled;
    private String remark;
}
