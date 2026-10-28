package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 事件订阅配置（add-event-subscription，FR-4.9-2-3）。
 * 伙伴 × 事件类型唯一；URL 空回落通道 CALLBACK_URL（两级回落零断流）；
 * RETRY_MAX 行级死信阈值（1~5，建投递行时快照）。
 */
@Getter
@Setter
@TableName("erp_intf_subscription")
public class IntfSubscription extends BaseEntity {

    public static final String ST_ENABLED = "ENABLED";
    public static final String ST_DISABLED = "DISABLED";

    private String partnerCode;
    private String eventType;
    private String callbackUrl;
    private Integer retryMax;
    private String status;
    private String remark;
}
