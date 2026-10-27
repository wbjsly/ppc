package com.erp.entity.pm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 服务工单客户确认免密链接（add-service-order，confirmed_via=LINK）。 */
@Getter
@Setter
@TableName("erp_pm_service_confirm_token")
public class SoConfirmToken extends BaseEntity {

    private String orderId;
    /** 32 字节随机 hex，一次性 */
    private String token;
    private LocalDateTime expireAt;
    /** 1=已使用 */
    private String used;
    private LocalDateTime usedAt;
}
