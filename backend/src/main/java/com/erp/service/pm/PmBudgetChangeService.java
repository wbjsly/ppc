package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 预算变更（project-initiation，BR-4.8-12；11.3.3 页内挂载）。 */
public interface PmBudgetChangeService {

    /** 变更单清单（按项目） */
    List<Map<String, Object>> list(String projectId);

    /**
     * 提交变更申请：仅 BASELINE_LOCKED=1 项目可提，PENDING 单存在时 422。
     * @param payload {reason, lines:[{wbsId, material, subcontract, travel, other}]}
     */
    Map<String, Object> submit(String projectId, Map<String, Object> payload);
}
