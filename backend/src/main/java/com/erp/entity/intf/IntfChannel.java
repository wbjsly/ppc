package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 伙伴与通道（design D1/D2：限流档位与熔断状态机落库，可断言可跨重启）。 */
@Getter
@Setter
@TableName("erp_intf_channel")
public class IntfChannel extends BaseEntity {

    public static final String ST_SANDBOX = "SANDBOX";
    public static final String ST_TRIAL = "TRIAL";
    public static final String ST_PROD = "PROD";
    public static final String ST_DISABLED = "DISABLED";

    public static final String TIER_STRATEGIC = "STRATEGIC";
    public static final String TIER_NORMAL = "NORMAL";
    public static final String TIER_NEW = "NEW";
    /** BR-4.9-19 自动降档后的最低档 */
    public static final String TIER_LOWEST = "LOWEST";

    public static final String CIRCUIT_CLOSED = "CLOSED";
    public static final String CIRCUIT_HALF_OPEN = "HALF_OPEN";
    public static final String CIRCUIT_OPEN = "OPEN";

    private String partnerCode;
    private String partnerName;
    /** STRATEGIC / NORMAL / NEW */
    private String partnerTier;
    /** API / EDI */
    private String channelType;
    /** HTTPS / AS2 / OFTP2 / SFTP（偏差表：仅登记与证书台账，不真连） */
    private String protocol;
    private String callbackUrl;
    private String certFingerprint;
    private LocalDate certExpireDate;
    /** SANDBOX / TRIAL / PROD / DISABLED */
    private String status;
    /** 当前限流档位（降档后 LOWEST） */
    private String rateTier;
    private Integer limitPerMin;
    /** CLOSED / HALF_OPEN / OPEN（C-5.5-09） */
    private String circuitState;
    private LocalDateTime circuitSince;
    private LocalDateTime circuitRecoverAt;
    /** 半开探测连续成功笔数（≥10 恢复） */
    private Integer circuitProbeOk;
    /** 半开期探测尝试序号（每 20 次放行 1 次 ≈5%，C-5.5-09） */
    private Integer probeHits;
    /** 24 小时内熔断次数（≥3 升级告警） */
    private Integer circuitTripCount;
    private LocalDate circuitTripDay;
    private LocalDateTime downgradeAt;
    private String downgradeFrom;
    private String remark;
}
