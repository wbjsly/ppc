package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 整改与冻结（spec BR-4.9-05：D 级冻结新单，整改关闭+审批解冻）。 */
@Getter
@Setter
@TableName("erp_scm_scorecard_rectify")
public class ScmScorecardRectify extends BaseEntity {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_CLOSED = "CLOSED";

    private String rectifyNo;
    private String resultId;
    private String monthTag;
    private String supplierId;
    /** GRADE_D / C_TWO_MONTHS */
    private String triggerType;
    private String status;
    private Boolean frozen;
    private String itemsJson;
    private LocalDateTime dueAt;
    private String closeBy;
    private LocalDateTime closeAt;
    private String unfreezeApproval;
    private LocalDateTime unfreezeAt;
    private String remark;
}
