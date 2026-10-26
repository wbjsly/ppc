package com.erp.service.hr;

import com.erp.entity.hr.HrTransfer;

import java.util.List;
import java.util.Map;

/**
 * 人事变动：转正/调岗（employee-transfer，§4.7 流程四，C-4.7-03 双人复核）。
 */
public interface HrTransferService {

    // ---------- 转正（FR-4.7-4-1~4） ----------

    /** 发起转正（主管评价：评分/综合评价/建议 按期 REGULAR/延期 EXTEND/终止 TERMINATE） */
    HrTransfer submitProbation(Map<String, Object> payload);

    /** HR 审核（合规校验：延期法定上限）并提交审批 */
    HrTransfer hrReviewProbation(String trfId, String opinion);

    // ---------- 调岗（FR-4.7-4-5~10 / C-4.7-03） ----------

    /** 发起调岗（调出/调入部门岗位、原因、拟生效日、薪酬建议；满编 L1） */
    HrTransfer submitPostChange(Map<String, Object> payload);

    /** 调出部门确认 */
    HrTransfer confirmDeptOut(String trfId, String opinion);

    /** 调入部门确认 */
    HrTransfer confirmDeptIn(String trfId, String opinion);

    /** HR 审核（编制/合规）→ 进入双人复核 */
    HrTransfer hrReviewPost(String trfId, String opinion);

    /**
     * 双人复核签署（C-4.7-03）：第一/第二复核人各自逐项确认五要素 JSON
     * （岗位/薪酬/汇报线/成本中心/生效日期）；两人齐且全一致 → 流转审批；
     * 任一项不一致 → REVIEW_DIFF 挂起升级裁决。
     * 系统按当前用户区分复核位（同一人不可双签）。
     */
    HrTransfer signReview(String trfId, Map<String, Object> payload);

    /** 差异裁决（HR 经理）：确认后流转审批 */
    HrTransfer adjudicateDiff(String trfId, String decision, String note);

    /**
     * 缴纳地变更设置（HR 审核环节，FR-4.7-4-8）：勾选后生效执行时更新 SOCIAL_PLACE
     * 并自动开社保转移单；新缴纳地值域=社保政策表已维护城市。
     */
    HrTransfer updatePlaceChange(String trfId, String placeChange, String newPlace);

    /**
     * 生效执行（审批通过回调/每日扫描调用）：更新主档部门/岗位/成本中心（历史留痕）
     * + 账号角色换绑（失败降级 LINK_PENDING 不回滚）。
     */
    HrTransfer effect(String trfId);

    // ---------- 查询 ----------

    List<HrTransfer> list(String type, String status);
    Map<String, Object> detail(String trfId);
}
