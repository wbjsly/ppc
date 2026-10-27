package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 服务工单满意度回访（add-service-order，FR-4.8-9-5）。 */
@Getter
@Setter
@TableName("erp_pm_service_survey")
public class SoSurvey extends BaseEntity {

    private String orderId;
    /** 满意度 1-5 分 */
    private Integer score;
    private String suggestion;
    /** 回访尝试次数（3 次失败置未回访） */
    private Integer contactTries;
    /** 1=未回访归档 */
    private String unreachedFlag;
    private String surveyBy;
    private LocalDateTime surveyDate;
}
