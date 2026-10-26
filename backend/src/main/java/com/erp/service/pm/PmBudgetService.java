package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** 任务级预算（project-initiation，FR-4.8-2-3；11.3.3）。 */
public interface PmBudgetService {

    /** 预算行清单（含自动计算的人工/间接与合计） */
    Map<String, Object> lines(String projectId);

    /**
     * 保存预算行（用户直填材料/外协/差旅/其他；人工/间接服务端重算）。
     * @param payload {lines:[{wbsId, material, subcontract, travel, other}]}
     */
    Map<String, Object> saveLines(String projectId, Map<String, Object> payload);

    /** 提交基线审批（立项校验 + 预算合计>0 + 状态 DRAFT），返回审批任务信息 */
    Map<String, Object> submitBaseline(String projectId);

    /** 预算合计（六列全项目） */
    Map<String, Object> summary(String projectId);
}
