package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.SdReturn;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 销售退货（tasks 12.2~12.7，spec sales-return，D14 退货三路）。
 *
 * 流程：申请（关联 SO 行、可退余量校验、开票状态分流、拒收自动带入）→
 *       判定（责任方/核定数量/超期/依据留痕）→ L2 审批（退款：销售经理+财务；换货：销售经理）→
 *       执行（退款：红字发票+应收红冲+凭证 / 换货：新发货单重走 ATP 锁批）→
 *       实物入库（回补 AVAILABLE_QTY；待判入 QC_QTY 不计 ATP）。
 */
public interface ReturnService {

    /** 12.2 退货申请行数据源：SO 行 + 已发量 + 已退量 + 可退余量 + 开票状态 */
    Map<String, Object> returnables(String soId);

    /** 12.2 创建退货申请（数量≤可退余量；开票状态分流；凭证说明必填） */
    SdReturn createApply(Map<String, Object> req);

    Page<SdReturn> page(long current, long size, String keyword, String status,
                        String customerId);

    /** 全链路详情：头 + 行 + 判定日志 + 审批 + 红字发票 + 换货发货单 */
    Map<String, Object> detail(String id);

    /** 12.3 判定：责任方/核定可退数量/处理方式/依据；超期自动标记；→ JUDGED */
    SdReturn judge(String id, Map<String, Object> payload);

    /** 12.3 判定驳回：注明原因退回 → REJECTED（可改后重判） */
    SdReturn judgeReject(String id, String reason);

    /** 12.4 提交审批：退款 → 销售经理+财务 L2；换货 → 销售经理单节点 */
    SdReturn submit(String id);

    /**
     * 12.5 退款执行（财务）：已开票 → 红字发票 + 应收红冲；未开票 → 直接红冲应收事实；
     * 生成红冲凭证（借 6001 / 贷 1122）；支持部分退款登记（payNo）→ DONE。
     */
    Map<String, Object> refund(String id, BigDecimal refundAmt, LocalDate refundDate,
                               String payNo);

    /** 12.6 换货执行：生成关联退货单的新发货单并重走 ATP 锁批，库存不足 422 阻断 → DONE */
    Map<String, Object> exchange(String id);

    /**
     * 12.7 实物入库：按批次回补 AVAILABLE_QTY（qc=1 入 QC_QTY 不计 ATP），
     * 生成退货入库凭证（借 1405 / 贷 6401）；全部行入齐 → STOCK_IN=1。
     */
    Map<String, Object> stockIn(String id, List<Map<String, Object>> items);

    /** 撤销：仅 DRAFT/REJECTED 可撤 → CANCELLED */
    SdReturn cancel(String id, String reason);
}
