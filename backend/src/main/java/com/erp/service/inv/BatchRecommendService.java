package com.erp.service.inv;

import java.util.Map;

/**
 * 出库批次推荐（4.6 出库策略，spec outbound-strategy）：
 * 统一的 FIFO+FEFO 推荐口径——入库日期升序、同日按有效期升序（BR-4.4-19），
 * 剔除冻结占用、效期锁定与零可用批次，按序累加至满足数量、末位缺口取量。
 * 同一算法服务于 4.6.1 试算（只读模拟）与 4.6.3 真实单据推荐（design D1）。
 */
public interface BatchRecommendService {

    /**
     * 生成《批次推荐表》。
     *
     * @param warehouseCode 仓库（必填）
     * @param itemCode      物料（必填）
     * @param qty           出库数量（> 0）
     * @param binLevel      true=逐批次内再按位行拆分（4.6.3 用），false=仅批次级
     * @return { satisfied, totalAvailable, gap, splitSuggestion, lines[], excluded[] }
     *         lines: [{batchNo, inboundDate, expiryDate, available, take, cumulative, bins[]}]
     *         excluded: [{batchNo, reason}]（效期锁定/零可用等剔除原因）
     *         入参非法（物料/仓库缺失、qty<=0）MUST 422
     */
    Map<String, Object> recommend(String warehouseCode, String itemCode,
                                  java.math.BigDecimal qty, boolean binLevel);

    /**
     * 波次级推荐（spec wave-management 波次级统一分配，design D3 共享预算）：
     * 与 recommend 同口径（FIFO+FEFO、剔池、位级），差异仅在**批次预算**
     * = Σ位行可用 −（ACTIVE 预留 − excludeSoIds 预留）——波次内单据自身的
     * ATP 预留不占用共享预算（否则波次需求会被自己锁的量双扣导致误拆单）。
     *
     * @param excludeSoIds 波次内发货单的 SO ID 集合（排除自身预留；空=同 recommend）
     */
    Map<String, Object> recommendForWave(String warehouseCode, String itemCode,
                                         java.math.BigDecimal qty, boolean binLevel,
                                         java.util.List<String> excludeSoIds);

    /**
     * 效期锁定每日扫描（BR-4.4-20，design D4）：按 当前参数 全量重算
     * `剩余天数 < 有效期总天数 × EXPIRY_LOCK_RATIO` → 置 EXPIRY_LOCK_FLAG='1'，否则回 '0'；
     * 双向回位使效期修正可自愈。生产日期或有效期缺失的批次 MUST 跳过（无法计算总天数）。
     *
     * @return 本次状态发生变化的批次数
     */
    int scanExpiryLock();
}
