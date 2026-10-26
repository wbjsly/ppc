package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 预算变更单（project-initiation，BR-4.8-12；11.3.3 页内挂载）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_pm_budget_change")
public class PmBudgetChange extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_APPROVED = "APPROVED";
    public static final String ST_REJECTED = "REJECTED";

    /** PBC+yyyyMMdd-序号 */
    private String changeNo;
    private String projectId;
    private String reason;
    private BigDecimal beforeAmount;
    private BigDecimal afterAmount;
    /** 变更后预算行快照 */
    private String snapshotJson;
    /** PENDING/APPROVED/REJECTED */
    private String status;
    private String apprId;
    private String pmOpinion;
    private String finOpinion;
}
