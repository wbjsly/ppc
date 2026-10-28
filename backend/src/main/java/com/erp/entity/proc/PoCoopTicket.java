package com.erp.entity.proc;

import com.baomidou.mybatisplus.annotation.TableName;
import com.erp.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PO 协同异常工单（spec po-collaboration 增补：连续 3 张超时锁定 → OPEN 工单
 * → 工单处理解锁 DONE，L5 留痕；design D1）。
 * 状态机：OPEN → DONE（resolve 时写解锁人/时间/原因），无工单不得解锁。
 */
@Getter
@Setter
@TableName("erp_po_coop_ticket")
public class PoCoopTicket extends BaseEntity {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_DONE = "DONE";

    /** 工单号 PCT+yyyyMMdd+序号 */
    private String ticketNo;
    private String supplierId;
    private String supplierName;
    /** 连续超时的 PO 单号清单（顿号分隔） */
    private String lockPoNos;
    /** 锁定触发时间 */
    private LocalDateTime lockAt;
    /** OPEN / DONE */
    private String status;
    /** 解锁人（L5 留痕） */
    private String unlockBy;
    private LocalDateTime unlockAt;
    /** 解锁原因（L5 留痕，resolve 必填） */
    private String unlockReason;
}
