package com.erp.service.vmi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.vmi.VmiAlert;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

/**
 * VMI 告警/建议（spec vmi-consignment，design D3/D4）。
 * WATER_HIGH：超最高水位 422 + 告警 → 采购员确认（CONFIRMED）→ 携带放行标记过账 → 消费（RESOLVED，一次性）。
 * REPLENISH：低于最低水位惰性生成（台账加载/结算生成扫描，去重一条 OPEN），回升自动关闭。
 */
public interface VmiAlertService {

    /**
     * 最高水位校验（BR-4.2-36，L1）：逐物料 现有寄售量 + 本次入账量 > 协议最高水位 → 422；
     * 首次触发落 OPEN 告警（已存在 OPEN 不重复建）。confirm=true 且该物料存在 CONFIRMED
     * 告警（采购员已确认放行）→ 放行。
     */
    void assertWaterLevel(String supplierId, Map<String, BigDecimal> incomingByItem, boolean confirm);

    /** 过账成功后消费放行额度：对应物料 CONFIRMED → RESOLVED（一次性放行，design D3）。 */
    void consumeConfirmed(String supplierId, Collection<String> itemCodes);

    /** 采购员确认放行（ADMIN/PM）：OPEN → CONFIRMED，留痕确认人/时间/意见；仅 WATER_HIGH。 */
    VmiAlert confirm(String alertId, String opinion);

    /** 补货建议扫描（台账加载/结算生成时调用，L4 惰性，design D4）：低于 MIN 建 OPEN、回升关 OPEN。 */
    void scanReplenish();

    /** 告警分页（类型/状态/供应商筛选） */
    Page<Map<String, Object>> page(long current, long size, String alertType, String status,
                                   String supplierId);
}
