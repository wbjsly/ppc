package com.erp.service.inv;

import java.util.Map;

/**
 * 拣货推荐（4.6.3，spec outbound-strategy）：从出库队列 A 单据生成《批次推荐表》
 * （FIFO+FEFO，批次+仓位），确认后回写单据行 BATCH_NO/BIN_CODE——保守版不建预留（偏差 D2），
 * 过账由 stock-posting-engine 按行值扣减、冲突 422 封顶兜底。
 */
public interface PickRecommendService {

    /**
     * 逐行生成推荐（只读，不改单据）。
     *
     * @param type SALES_OUT / MATERIAL_OUT / TRANSFER_OUT / SCRAP_OUT
     * @param docNo 来源单据号
     * @return { docNo, status, lines: [{lineNo,itemCode,qty,recommendedBatch,bins[],excluded[]}] }
     *         单据不存在 404；不在队列 A 口径 422
     */
    Map<String, Object> generate(String type, String docNo);

    /**
     * 确认回写：后端重算基准（防篡改），逐行比较——改写行落偏离台账（原因必填，空 422），
     * 回写行 BATCH_NO/BIN_CODE（乐观锁），MUST NOT 生成预留行。
     *
     * payload: { type, docNo, lines: [{lineNo, batchNo, binCode, deviationReason}] }
     */
    Map<String, Object> confirm(Map<String, Object> payload);
}
