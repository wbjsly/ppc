package com.erp.entity.scm;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 门户账号激活 token（add-partner-onboarding，表 erp_scm_partner_activate_token，
 * FR-4.9-1-4 / BR-4.9-11）。一次性随机 64hex、7 天有效、重发旧链立即失效。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_scm_partner_activate_token")
public class PartnerActivateToken extends BaseEntity {

    /** 有效期天数（BR-4.9-11） */
    public static final int EXPIRE_DAYS = 7;

    /** 待激活门户账号 */
    private String accountId;

    private String supplierId;

    /** 一次性随机 64hex（UK） */
    private String token;

    /** 生成 +7 天 */
    private LocalDateTime expireAt;

    /** 激活时间（一次性） */
    private LocalDateTime usedAt;

    /** 1=已失效（过期扫描/重发旧链） */
    private String invalidFlag;

    public boolean isUsable() {
        return "0".equals(invalidFlag) && usedAt == null
                && expireAt != null && expireAt.isAfter(LocalDateTime.now());
    }
}
