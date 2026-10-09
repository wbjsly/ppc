package com.erp.service.inv;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 召回入库与处置联动（4.13.2，spec trace-recall 受限区入库/报废处置闭环；design D5/D6）。
 * 动作级分权：receive = WAREHOUSE/ADMIN（沿 4.5.4 口径）；markDisposed 仅供报废链内部回调。
 */
public interface RecallService {

    /**
     * 召回受限区入库（BR-4.4-50 L1）：目标仓位 MUST 为 RETURN/SCRAP 类型（否则 422）；
     * 引擎过账 RECALL_IN 行 intoQc=true（量进 QC 冻结列，处置前天然冻结），
     * 来源挂追溯单号；写审计。
     */
    Map<String, Object> receive(String traceId, String flowId, String warehouseCode,
                                String binCode, BigDecimal qty);

    /**
     * 处置完成回调（design D6，报废 post 成功同事务内调用）：
     * 该追溯单下批次匹配、状态 FROZEN/RECEIVED 的流向行置 DISPOSED 并写审计。
     * 追溯单不存在或无匹配行时静默跳过（报废单未带 TR 关联不回调）。
     */
    void markDisposed(String traceNo, Set<String> batchNos);
}
