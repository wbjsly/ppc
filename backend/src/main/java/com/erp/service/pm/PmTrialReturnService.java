package com.erp.service.pm;

import java.util.Map;

/**
 * 试制结余退库与成本冲减（add-rnd-material，FR-4.8-10-4 + BR-4.8-04）。
 * 质检结论 REUSABLE → 入库+负向成本冲减；SCRAP → 不入库成本保留；
 * 跨项目挪用 L1（批次属原领料项目）；退库数不得超过结余；RETURNED_FLAG 防重复冲减。
 */
public interface PmTrialReturnService {

    /** 保存/提交退料单（isNew 显式；QC_RESULT 校验；跨项目 L1；结余校验） */
    Map<String, Object> save(Map<String, Object> payload, boolean submit);

    /** 审核（APPROVED 时同事务入库+成本冲减；REUSABLE 入库，SCRAP 保留） */
    Map<String, Object> review(String id, Map<String, Object> payload);

    /** 退料单列表（按项目/领料单过滤） */
    Map<String, Object> list(Map<String, Object> query);

    /** 退料单详情（含退库行） */
    Map<String, Object> detail(String id);
}
