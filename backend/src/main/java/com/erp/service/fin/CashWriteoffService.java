package com.erp.service.fin;

import com.erp.entity.fin.CashDoc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 统一核销内核（spec payment-collection，design D4）：
 * FIFO（IN→AR 发票池 / AP→所选或池）、币种断言 C-4.6-11、跨期审批 C-4.6-02、统一 cash_writeoff 落账。
 */
public interface CashWriteoffService {

    /**
     * 过账时自动核销：对单据可用金额执行 FIFO。
     * 返回 {writeoffTotal, remaining, crossPeriod, pendingRows}；
     * 跨期时核销行先落库（PENDING 语义由单据 writeoffState 承载），PAID 回写挂起待审批。
     */
    Map<String, Object> settle(CashDoc doc, List<?> targets);

    /** 跨期审批通过后执行挂起核销（回调入口，幂等：已回写跳过） */
    void applyApprovedSettle(String docId);

    /** 人工核销（手动指定目标单据；同样走币种断言与跨期审批） */
    Map<String, Object> manualSettle(String docId, String targetType, String targetId, BigDecimal amount);

    /** 单据核销明细 */
    List<Map<String, Object>> listByDoc(String docId);
}
