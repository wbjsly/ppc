package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 期间成本报表（M8-10-3，change add-cost-accounting）。
 * 只读聚合 erp_mrp_cost_summary + 工单物料，无写路径。
 */
public interface CostReportService {

    /** 期间报表：summary 汇总卡 + rows 工单明细（含超容差红标布尔）。period 必填 yyyyMM。 */
    Map<String, Object> report(String period, String productCode, String reportStatus);

    /** CSV 导出（与 report 同源查询，中文表头 UTF-8 BOM）。 */
    String csv(String period, String productCode, String reportStatus);

    /** 多期趋势：按期间聚合三要素构成（单期如实返回单元素）。 */
    List<Map<String, Object>> trend();
}
