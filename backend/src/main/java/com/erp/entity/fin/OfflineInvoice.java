package com.erp.entity.fin;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 离线开票留桩（48h 未补传 L4 告警）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("erp_fin_offline_invoice")
public class OfflineInvoice extends BaseEntity {

    /** 离线开票单号 OFL+流水 */
    private String offlineNo;
    private String invoiceApplyNo;
    /** 离线开票时间 */
    private LocalDateTime openTime;
    private String reason;
    /** 补传时间 */
    private LocalDateTime uploadTime;
    /** L4 告警标记 1=告警中 */
    private String alertFlag;
}
