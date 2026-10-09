package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 开放入口调用审计（BR-4.9-21：全量记录、脱敏、fail-closed 预写入）。 */
@Getter
@Setter
@TableName("erp_intf_call_log")
public class IntfCallLog extends BaseEntity {

    private String caller;
    private String partnerCode;
    private String apiPath;
    private String httpMethod;
    /** X-Request-Id 全链路追踪 */
    private String reqId;
    /** 预写入时为 0，业务执行后回写真实状态码 */
    private Integer respCode;
    private String errCode;
    private Integer costMs;
    /** 参数摘要（敏感字段脱敏后写入） */
    private String paramSummary;
    private Boolean rateHit;
    private Boolean circuitHit;
    private String env;
    private String ip;
    private String userAgent;
    private LocalDateTime callAt;
}
