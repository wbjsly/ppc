package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/** API 凭证（spec api-credential-management：签发/分发/轮换/吊销/环境隔离）。 */
@Getter
@Setter
@TableName("erp_intf_credential")
public class IntfCredential extends BaseEntity {

    public static final String ST_PENDING = "PENDING";
    public static final String ST_ACTIVE = "ACTIVE";
    public static final String ST_DEPRECATED = "DEPRECATED";
    public static final String ST_REVOKED = "REVOKED";
    public static final String ST_EXPIRED = "EXPIRED";

    public static final String ENV_SANDBOX = "SANDBOX";
    public static final String ENV_PROD = "PROD";

    private String channelId;
    private String partnerCode;
    /** 公开标识 */
    private String apiKey;
    /** 口令 SHA-256 加盐哈希（不可逆） */
    private String secretHash;
    private String secretSalt;
    /** HMAC-SHA256 报文签名密钥（服务端须可重算，签发时一次性展示） */
    private String signKey;
    /** 逗号分隔 Scope（C-5.5-06 出参脱敏授权依据） */
    private String scope;
    /** SANDBOX / PROD（不可跨环境，SOP-5.5-A 步骤4） */
    private String env;
    private String rateTier;
    private Integer limitPerMin;
    /** PENDING / ACTIVE / DEPRECATED / REVOKED / EXPIRED */
    private String status;
    private LocalDateTime issueAt;
    private LocalDateTime expireAt;
    @com.baomidou.mybatisplus.annotation.TableField("REMIND_30")
    private Boolean remind30;
    @com.baomidou.mybatisplus.annotation.TableField("REMIND_7")
    private Boolean remind7;
    @com.baomidou.mybatisplus.annotation.TableField("REMIND_1")
    private Boolean remind1;
    /** 计划内轮换灰度起点（观察 24h） */
    private LocalDateTime grayFrom;
    /** DEPRECATED 后保留 7 天可回滚 */
    private LocalDateTime deprecateUntil;
    private String oldCredId;
    /** 一次性分发链接令牌（10 分钟有效） */
    private String downloadToken;
    /** 签发时临时留存的明文口令，首次下载/确认后即清空（SOP-5.5-B 步骤2） */
    private String pendingSecret;
    private LocalDateTime downloadTokenAt;
    private LocalDateTime downloadAt;
    private String downloadIp;
    private String downloadUa;
    /** 调用方「已接收」确认 */
    private LocalDateTime ackAt;
    private String issuedBy;
    private String revokeBy;
    private LocalDateTime revokeAt;
    private String revokeReason;
    private LocalDateTime lastUsedAt;
    private String remark;
}
