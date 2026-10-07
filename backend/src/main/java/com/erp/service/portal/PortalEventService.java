package com.erp.service.portal;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.proc.Asn;
import com.erp.entity.proc.PurchaseOrder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 门户推送事件（spec portal-event-push）：六类事件发布 + 推送台账查询。
 * 发布与业务写操作同事务（OutboxPublisher 语义），载荷最小披露。
 */
public interface PortalEventService {

    /** PO 下达推送（审批通过/新版本时调用） */
    void poPushed(PurchaseOrder po);

    /** 交期确认结果（confirmSeq 支持改期再确认） */
    void poConfirmed(String poNo, int confirmSeq, String supplierId, String promiseDate, String source);

    /** ASN 创建 */
    void asnCreated(Asn asn);

    /**
     * 水位同步（design D10：小时桶幂等 + 快照哈希无变化不写）。
     * @return true=写入新批次事件；false=同桶或同哈希已存在（跳过）
     */
    boolean waterSynced(String supplierId, String snapshotHash, List<Map<String, Object>> snapshotRows);

    /** 补货建议推送 */
    void replenishPushed(String alertId, String supplierId, String itemCode, BigDecimal suggestQty);

    /** 补货确认（联动 ASN） */
    void replenishConfirmed(String alertId, String supplierId, String itemCode,
                            BigDecimal confirmQty, String asnNo);

    /** 结算单推送（生成/双确认发起，seq 递增） */
    void settlePushed(String settleNo, int seq, String supplierId, BigDecimal amount, String period);

    /** 推送台账（内部全量 / 门户按绑定 supplierId 行级过滤） */
    Page<Map<String, Object>> ledger(String eventType, String supplierId, String keyword,
                                     String from, String to, long current, long size);
}
