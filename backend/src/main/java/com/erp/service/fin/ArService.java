package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArReceipt;
import com.erp.entity.fin.ArStatement;
import com.erp.entity.fin.ArWriteoff;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 应收台账与核销（tasks 10.6~10.8，spec sales-invoicing-receivable）。
 *
 * 口径：
 *  - FIFO 自动核销：回款到账按「最早未核销应收优先」匹配，支持部分核销；
 *    无法唯一匹配（无未清应收 / 余额未耗尽）→ 转人工核销队列（receipt PENDING/PARTIAL）；
 *  - 核销动作生成核销凭证（借 1002 银行存款 / 贷 1122 应收账款）并记录核销人、时间与匹配明细；
 *  - 月度对账单：期初 + 本期开票 − 本期核销 = 期末，未核销逐笔明细 + 差异逐笔排查；
 *  - 计划达成：应收回看所属合同期次与收款进度（合同 → SO → 应收链路）。
 */
public interface ArService {

    /** 应收台账多维查询（客户/SO/发票号/状态/超期/合同，3.8.3） */
    Page<ArInvoice> arPage(long current, long size, String keyword, String status,
                           String customerId, String soNo, String contractId, Boolean overdue);

    /** 应收详情：头 + 明细行 + 核销记录 + 所属合同期次与收款进度 */
    Map<String, Object> arDetail(String arId);

    /**
     * 客户回款登记（10.6）：FIFO 自动匹配最早未清应收，支持部分核销；
     * 未匹配余额转人工队列。返回回款单 + 核销明细 + 凭证号。
     */
    Map<String, Object> registerReceipt(String customerId, BigDecimal amount,
                                        LocalDate payDate, String remark);

    /** 人工核销（10.6 无法匹配转人工）：对指定应收按金额手动核销，可来自待人工回款余额 */
    Map<String, Object> manualWriteoff(String receiptId, String arId, BigDecimal amount,
                                       LocalDate payDate, String remark);

    Page<ArReceipt> receiptPage(long current, long size, String status,
                                String keyword, String customerId);

    Page<ArWriteoff> writeoffPage(long current, long size, String keyword,
                                  String customerId, String arNo);

    /**
     * 月度对账单生成（10.7）：期初/本期开票/本期核销/期末 + 未核销逐笔明细。
     * 同客户同期间幂等（存在则刷新明细）。
     */
    Map<String, Object> generateStatement(String customerId, String period);

    Page<ArStatement> statementPage(long current, long size, String keyword, String customerId);

    /** 对账单详情：头 + 明细行（含差异与排查记录） */
    Map<String, Object> statementDetail(String stmtId);

    /** 差异逐笔排查记录（10.7） */
    void checkLine(String lineId, String checkStatus, String note, BigDecimal diffAmt);

    /**
     * 计划达成对比（10.8 / 11.11.2）：逐期展示计划金额、实际应收、已核销与达成率，
     * 超期未收标记 overdue=true（前端标红）。
     */
    List<Map<String, Object>> planProgress(String contractId);

    /** 合同名下应收汇总（合同页关联展示） */
    Map<String, Object> contractArSummary(String contractId);
}
