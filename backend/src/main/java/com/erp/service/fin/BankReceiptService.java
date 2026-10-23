package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.BankReceipt;

import java.util.Map;

/**
 * 回单对账（M8-4-3，spec bank-receipt-reconciliation，design D8）：
 * 银行回单台账 + CSV 导入 + 与 cash_doc 逐笔匹配（自动/人工）+ 异常标记与解除。
 */
public interface BankReceiptService {

    /** 手工录入回单（UNMATCHED） */
    BankReceipt create(Map<String, Object> payload);

    /** CSV 批量导入（回单号/账户/日期/方向/金额/对方户名/摘要；逐行报告） */
    Map<String, Object> importCsv(String csvContent);

    /** 自动匹配：账户+方向映射+金额相等±3 天，一单一回单；返回匹配对数 */
    Map<String, Object> autoMatch();

    /** 人工匹配（方向/账户校验 422；已匹配拒绝） */
    BankReceipt manualMatch(String receiptId, String docId);

    /** 标记异常（EXCEPTION + 原因） */
    BankReceipt markException(String receiptId, String reason);

    /** 解除匹配（仅 FINANCE_MGR/ADMIN；回单回 UNMATCHED、单据回 POSTED，留痕） */
    BankReceipt unreconcile(String receiptId, String reason);

    /** 分页（匹配状态/关键字过滤） */
    Page<Map<String, Object>> page(long current, long size, String matchStatus,
                                   String keyword, String dateFrom, String dateTo);

    /** 详情 */
    Map<String, Object> detail(String id);
}
