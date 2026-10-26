package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 超预算审批申请（add-cost-collection-freeze，BR-4.8-22 三级分级）。 */
@Getter
@Setter
@TableName("erp_pm_over_budget")
public class PmOverBudget extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** BR-4.8-22 三级路由角色 */
    public static final String ROUTE_PMO = "ROLE_PMO";
    public static final String ROUTE_FINANCE = "ROLE_FINANCE";
    public static final String ROUTE_EXEC = "ROLE_EXEC";

    /** 申请单号 OBB+yyyyMMdd-序号 */
    private String applyNo;
    private String projectId;
    /** 超支金额 = 实际累计 − 基线 */
    private java.math.BigDecimal overAmount;
    /** 超支比例（%） */
    private java.math.BigDecimal overRate;
    private String reason;
    /** 已采取节约措施 */
    private String savingMeasure;
    /** 追加预算需求（0=纯解冻） */
    private java.math.BigDecimal extraBudget;
    /** 审批实例 */
    private String apprId;
    /** 按超支比例路由：ROLE_PMO|ROLE_FINANCE|ROLE_EXEC */
    private String approvalRole;
    /** PENDING|APPROVED|REJECTED */
    private String status;
    private String pmOpinion;
}
