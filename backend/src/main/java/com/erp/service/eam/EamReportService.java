package com.erp.service.eam;

import java.util.List;
import java.util.Map;

/**
 * 资产报表（add-asset-report，只读，零状态机）。
 * 7.8.1 变动事件流水+三栏勾对 / 7.8.2 折旧汇总·明细·净值台账 /
 * 7.8.3 盘点报告消费端 / 三叶统一 CSV 导出（5 万行上限）。
 */
public interface EamReportService {

    /** 变动事件流水分页（期间/类型/分类/成本中心筛选，日期倒序） */
    Map<String, Object> changeEvents(long current, long size, String fromDate, String toDate,
                                     String evtType, String categoryCode, String costCenter);

    /** 三栏勾对汇总（事件口径增减 + 快照口径期末 + reconOk/TOLERANCE_DEFAULT 判定） */
    Map<String, Object> changeSummary(String fromDate, String toDate);

    /** 折旧汇总（分类/成本中心双维，环比差异 + 大于容差标 alerted） */
    Map<String, Object> depreciationSummary(String periodTag);

    /** 当期折旧明细（与计提单一致） */
    List<Map<String, Object>> depreciationDetails(String periodTag);

    /** 资产净值台账（当前口径，排除 DISPOSED/IN_REWORK/REPLACED） */
    Map<String, Object> netAssetLedger(long current, long size, String categoryCode,
                                       String costCenter, String deprStatus);

    /** 盘点报告列表（历次计划含覆盖率/差异率/归档态） */
    Map<String, Object> countReportList(long current, long size, String keyword);

    /** 盘点报告详情（四类分布/TOP/原因聚合/SUMMARY；未结案 reportReady=false） */
    Map<String, Object> countReportDetail(String planId);

    /** 变动事件 CSV（筛选一致，超限 422） */
    String exportChangeCsv(String fromDate, String toDate, String evtType,
                           String categoryCode, String costCenter);

    /** 折旧明细 CSV */
    String exportDepreciationCsv(String periodTag);

    /** 净值台账 CSV */
    String exportLedgerCsv(String categoryCode, String costCenter, String deprStatus);
}
