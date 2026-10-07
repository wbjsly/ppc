package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 补货确认（spec vmi-portal-sync，design D9）：REPLENISH 告警 → 供应商确认 → 联动 ASN。 */
@Getter
@Setter
@TableName("erp_proc_replenish_confirm")
public class ReplenishConfirm extends BaseEntity {

    public static final String SRC_PORTAL = "PORTAL";
    public static final String SRC_OFFLINE = "OFFLINE";

    /** VmiAlert REPLENISH 告警 ID（唯一，防重复确认） */
    private String alertId;
    private String supplierId;
    private String itemCode;
    /** 建议量 */
    private BigDecimal suggestQty;
    /** 确认量（可下修，不得突破协议最高水位） */
    private BigDecimal confirmQty;
    /** 联动生成的 ASN */
    private String asnId;
    /** PORTAL 门户 / OFFLINE 代录 */
    private String source;
    private String confirmBy;
    private LocalDateTime confirmAt;
    private String remark;
}
