package com.erp.service.scm;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/** CPFR 预测版本与数据包（spec cpfr-collaboration R2）。 */
public interface CpfrForecastService {

    Page<Map<String, Object>> pageHeads(long current, long size, String periodTag);

    /** 建预测版本（DRAFT） */
    Map<String, Object> createHead(Map<String, Object> payload);

    /** 录/改 SKU×周预测行（同 HEAD+AGR+ITEM+WEEK 幂等覆盖） */
    Map<String, Object> saveLine(Map<String, Object> payload);

    /** 行清单（含快照字段） */
    java.util.List<Map<String, Object>> lines(String headId, String agreementId);

    /** 生成数据包：对每行快照当前库存/在途 PO/安全库存（design D2/丙决策） */
    Map<String, Object> generatePackage(String headId);

    /**
     * 按周期推送：数据包行按协议分组生成反馈行（待反馈），
     * 协议非已启用 → 422；幂等键去重；LAST_PERIOD_TAG 更新。
     */
    Map<String, Object> pushCycle(String headId);
}
