package com.erp.entity.intf;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/**
 * 限流档位变更历史（spec 12.3.2 降档与调档历史留痕，design D1）。
 * 只 INSERT：自动降档（BR-4.9-19）与两处手动调档同源落行，服务层不提供改删。
 */
@Getter
@Setter
@TableName("erp_intf_tier_change_log")
public class IntfTierChangeLog extends BaseEntity {

    /** BR-4.9-19 连续 3 个统计周期 429 自动降档 */
    public static final String TYPE_AUTO = "AUTO_DOWNGRADE";
    /** 管理员手动调档（12.1.3 / 2.8.3 写路径） */
    public static final String TYPE_MANUAL = "MANUAL";

    /** 自动降档的操作者占位 */
    public static final String OPERATOR_SYSTEM = "SYSTEM";

    /** 调用方（API Key 或伙伴编码） */
    private String caller;
    private String partnerCode;
    /** AUTO_DOWNGRADE / MANUAL */
    private String changeType;
    private String tierFrom;
    private String tierTo;
    private Integer limitFrom;
    private Integer limitTo;
    /** 自动降档固定文案 / 手动操作备注 */
    private String triggerReason;
    /** SYSTEM / 操作者用户 ID */
    private String operator;
}
