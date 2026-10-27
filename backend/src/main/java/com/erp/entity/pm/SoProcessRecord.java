package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 服务工单处理记录（add-service-order，BR-4.8-35 仅追加）。 */
@Getter
@Setter
@TableName("erp_pm_service_process_record")
public class SoProcessRecord extends BaseEntity {

    public static final String ACT_ACCEPT = "ACCEPT";
    public static final String ACT_PROCESS = "PROCESS";
    public static final String ACT_ROOT_CAUSE = "ROOT_CAUSE";
    public static final String ACT_SUSPEND = "SUSPEND";
    public static final String ACT_RESUME = "RESUME";
    public static final String ACT_REJECT = "REJECT";
    public static final String ACT_SUBMIT_CONFIRM = "SUBMIT_CONFIRM";
    public static final String ACT_CLOSE = "CLOSE";
    public static final String ACT_ESCALATE = "ESCALATE";

    private String orderId;
    /** ACCEPT|PROCESS|ROOT_CAUSE|SUSPEND|RESUME|REJECT|SUBMIT_CONFIRM|CLOSE|ESCALATE */
    private String action;
    private String content;
    private String attachIds;
}
