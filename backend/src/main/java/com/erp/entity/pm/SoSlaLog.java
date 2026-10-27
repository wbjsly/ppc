package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 服务工单 SLA 事件（add-service-order，定时任务幂等唯一键）。 */
@Getter
@Setter
@TableName("erp_pm_service_sla_log")
public class SoSlaLog extends BaseEntity {

    public static final String EV_ESCALATE = "ESCALATE";
    public static final String EV_REMIND_CONFIRM = "REMIND_CONFIRM";
    public static final String EV_DEFAULT_CLOSE = "DEFAULT_CLOSE";
    public static final String EV_SUSPEND = "SUSPEND";
    public static final String EV_RESUME = "RESUME";
    public static final String EV_SURVEY_DUE = "SURVEY_DUE";
    public static final String EV_IMPROVE_OVERDUE = "IMPROVE_OVERDUE";

    private String orderId;
    /** ESCALATE|REMIND_CONFIRM|DEFAULT_CLOSE|SUSPEND|RESUME|SURVEY_DUE|IMPROVE_OVERDUE */
    private String event;
    /** ESCALATE 时目标档 1 主管|2 销售经理（组合幂等键） */
    private Integer escalateStage;
    /** 升级/提醒对象角色 */
    private String target;
    private String detail;
}
