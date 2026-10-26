package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 银行对账（M8-7，spec bank-reconciliation）：余额级对账闭环。
 * 8.7.1 流水导入/8.7.2 自动比对复用 bank-receipt-reconciliation（同一数据源），本服务不重复逐笔匹配。
 */
public interface BankReconciliationService {

    /** 录入/修改对账单（DRAFT/CONFIRMED 可改）：账户+期间查重 422；勾稽自校验（不平提示仍可保存） */
    Map<String, Object> save(Map<String, Object> payload);

    /** 对账单详情（含差异率/超容差标记/调节表/四类未达账项） */
    Map<String, Object> detail(String id);

    /** 对账单列表（按期间倒序） */
    List<Map<String, Object>> list(String period);

    /** 出纳确认 DRAFT→CONFIRMED（FR-4.6-6-7） */
    void confirm(String id);

    /** 财务主管审核 CONFIRMED→AUDITED（BR-4.6-36） */
    void audit(String id);

    /** 撤销：AUDITED→DRAFT（仅主管/ADMIN，必填原因留痕）；CONFIRMED 可直接回 DRAFT */
    void revoke(String id, String reason);

    /**
     * 四类未达账项实时分类（FR-4.6-6-3）：①企业已收银行未收=期间 POSTED 未对账 IN 单；
     * ②企业已付银行未付=POSTED 未对账 OUT 单；③④=期间 UNMATCHED 回单收/付。
     * 每条带账龄，超 90 天红标（BR-4.6-35 提示性）。
     */
    Map<String, Object> outstandingItems(String bankAccountId, String period);

    /** 处理留痕（追加式）：markProcessing / markDone / escalate（升级主管，提示性） */
    Map<String, Object> markHandling(Map<String, Object> payload);

    /** 处理记录回看（按账户+期间） */
    List<Map<String, Object>> handlingRecords(String bankAccountId, String period);

    /**
     * 结账⑧判定辅助：某期间存在对账单的 ACTIVE 账户是否全部 AUDITED。
     * 返回 {total, unaudited, unauditedList:[{accountName, period, status}]}
     */
    Map<String, Object> closingCheck(String period);

    /**
     * D6 已审期间锁定守卫（供 BankReceiptServiceImpl 调用）：
     * 该回单账户+交易日期所属期间存在 AUDITED 对账单 → 抛 422。
     */
    void assertNotAuditedCover(String bankAccountId, java.time.LocalDate tradeDate, String action);
}
