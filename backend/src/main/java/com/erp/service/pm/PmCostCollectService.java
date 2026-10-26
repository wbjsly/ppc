package com.erp.service.pm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 五类成本统一归集（add-cost-collection-freeze，design D2）。
 * 领料/外协/差旅/间接/人工五类均落 erp_pm_cost_collect，归集过账各生成 SOURCE_TYPE=PROJECT_COST 凭证。
 */
public interface PmCostCollectService {

    /** 五类成本类别 */
    String TYPE_LABOR = "LABOR";
    String TYPE_MATERIAL = "MATERIAL";
    String TYPE_SUBCONTRACT = "SUBCONTRACT";
    String TYPE_TRAVEL = "TRAVEL";
    String TYPE_INDIRECT = "INDIRECT";

    /** 来源类型 */
    String SRC_MAT_ISSUE = "MAT_ISSUE";
    String SRC_SUB_PAYMENT = "SUB_PAYMENT";
    String SRC_TRAVEL_CLAIM = "TRAVEL_CLAIM";
    String SRC_HOURS_COLLECT = "HOURS_COLLECT";
    String SRC_ALLOC = "ALLOC";

    /** 凭证来源类型（与凭证域 SOURCE_TYPE 对齐） */
    String VOUCHER_SOURCE = "PROJECT_COST";

    /**
     * 归集统一入口：写 erp_pm_cost_collect（只增）+ 生成会计凭证（借贷平衡，科目按类别映射）。
     *
     * @param costType   LABOR|MATERIAL|SUBCONTRACT|TRAVEL|INDIRECT
     * @param sourceType MAT_ISSUE|SUB_PAYMENT|TRAVEL_CLAIM|HOURS_COLLECT|ALLOC
     * @param sourceId   来源单据/归集行 ID（业财锚点）
     * @param wbsId      任务级；间接/部分外协可空（项目级归集）
     * @param amount     金额（正数；冲减走负数行）
     * @param date       归集日
     * @return 归集行 ID
     */
    String collect(String costType, String sourceType, String sourceId,
                   String projectId, String wbsId, BigDecimal amount, LocalDate date);

    /** 项目五分类合计（偏差监控/页面看板）：返回 {LABOR, MATERIAL, SUBCONTRACT, TRAVEL, INDIRECT, total} */
    Map<String, Object> summaryByType(String projectId);

    /** 归集明细（按项目/类别/期间筛选，最新在前） */
    List<Map<String, Object>> list(String projectId, String costType, String dateFrom, String dateTo);

    /** 项目实际累计成本（五类合计） */
    BigDecimal actualTotal(String projectId);
}
