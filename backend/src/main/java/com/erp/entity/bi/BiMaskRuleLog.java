package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 脱敏规则变更留痕（add-data-extract-console design D1/D3，spec bi-mask-rule-management）。
 * CREATE / UPDATE_STYLE / UPDATE_ROLE / ENABLE / DISABLE / REJECT 逐次记录，审计只增。
 */
@Getter
@Setter
@TableName("erp_bi_mask_rule_log")
public class BiMaskRuleLog extends BaseEntity {

    public static final String ACT_CREATE = "CREATE";
    public static final String ACT_UPDATE_STYLE = "UPDATE_STYLE";
    public static final String ACT_UPDATE_ROLE = "UPDATE_ROLE";
    public static final String ACT_ENABLE = "ENABLE";
    public static final String ACT_DISABLE = "DISABLE";
    public static final String ACT_REJECT = "REJECT";

    private String ruleId;
    private String action;
    private String oldVal;
    private String newVal;
    private String operator;
    private LocalDateTime opAt;
}
