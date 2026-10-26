package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 超预算审批解冻（add-cost-collection-freeze，FR-4.8-5-7 / BR-4.8-22）。 */
public interface PmOverBudgetService {

    /**
     * 提交超预算申请：按超支比例动态选审批节点（≤5% PMO / 5–10% 财务 / >10% 分管领导）。
     * payload {projectId, reason, savingMeasure?, extraBudget?}；超支金额/比例服务端现算。
     */
    Map<String, Object> submit(Map<String, Object> payload);

    /** 冻结项目清单（含超支数据） */
    List<Map<String, Object>> listFrozen();

    /** 申请清单（按项目/状态） */
    List<Map<String, Object>> list(String projectId, String status);

    /** 单据详情 */
    Map<String, Object> detail(String id);
}
