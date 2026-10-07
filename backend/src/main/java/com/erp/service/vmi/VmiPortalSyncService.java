package com.erp.service.vmi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * VMI 门户同步（spec vmi-portal-sync，接 add-consignment-procurement 三处门户桩）：
 * 水位惰性同步推送（design D10 小时桶+哈希）、补货建议门户确认→ASN。
 */
public interface VmiPortalSyncService {

    /**
     * 水位同步推送（惰性）：supplierId 为空 → 全部有寄售库存的供应商。
     * 快照哈希比对 + 小时桶幂等，无变化不写事件（design D10）。
     * @return {syncTime, written, snapshotHash, items:[{itemCode,qty,min,max,status,needReplenish}]}
     */
    Map<String, Object> scanWater(String supplierId);

    /**
     * 补货建议确认（PORTAL/OFFLINE）：默认建议量可下修、上修不得突破协议最高水位；
     * 联动创建 REPLENISH ASN、告警置 CONFIRMED、发布补货确认事件；重复确认 409/422。
     */
    Map<String, Object> confirmReplenish(String alertId, Map<String, Object> payload, String source);

    /** 我的告警（门户行级隔离 / 内部可全量） */
    Page<Map<String, Object>> alerts(long current, long size, String alertType,
                                     String status, String supplierId);
}
