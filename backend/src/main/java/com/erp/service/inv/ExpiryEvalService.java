package com.erp.service.inv;

import com.erp.entity.inv.ExpiryEval;

import java.util.Map;

/**
 * 效期质量评估（4.10.3，spec expiry-management 需求④）：
 * 手工发起（锁定批次校验）→ 判定三分支 SCRAP/RELEASE/FREEZE → 审批 → CLOSED。
 * 解锁唯一入口=RELEASE 签署通过写豁免（全链不变量）。
 */
public interface ExpiryEvalService {

    /** 发起评估：非锁定批次 422、重复未关闭 422、评估说明必填、质量角色分流（否则 403） */
    ExpiryEval submit(String itemCode, String batchNo, String evalNote, String remark);

    /**
     * 提交判定（三分支，design D4）：
     * SCRAP→生成关联报废单+挂审批；RELEASE→校验放行有效期>今日+挂审批；
     * FREEZE→调冻结链+即刻 CLOSED。状态非 PENDING_EVAL 422；同人签署由底座拦截。
     */
    Map<String, Object> submitConclusion(String evalId, String conclusion,
                                         String releaseUntil, String evalNote);

    /** 评估单列表（状态/物料/批次筛选，分页） */
    Map<String, Object> page(String status, String itemCode, String keyword,
                             long current, long size);

    /** 评估单详情 */
    Map<String, Object> detail(String id);
}
