package com.erp.entity.bi;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** 敏感字段临时授权（add-perm-control-console，spec bi-temp-grant，≤24h 到期自动收回）。 */
@Getter
@Setter
@TableName("erp_bi_temp_grant")
public class BiTempGrant extends BaseEntity {

    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING_APPROVAL";
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_EXPIRED = "EXPIRED";
    public static final String ST_REJECTED = "REJECTED";

    /** 有效期上限（FR-4.10-3-4③） */
    public static final int MAX_HOURS = 24;

    /** 申请明文的字段 */
    private String fieldKey;
    private String userName;
    /** 用途说明 */
    private String purpose;
    private String status;
    private LocalDateTime applyAt;
    /** 服务端钳制 ≤ APPLY_AT+24h */
    private LocalDateTime expireAt;
    private String apprId;
    /** 实际收回时间 */
    private LocalDateTime recallAt;
    private String remark;
}
