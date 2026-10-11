package com.erp.service.mrp;

import com.erp.entity.mrp.MrpDispatch;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 派工单管理（change add-dispatch-management，spec dispatch-management：
 * 待派列表 / 工序级生成含批量 / 前向排程 / 确认状态机 / 取消回退 / WC 校验 / 齐套 L1 门禁）。
 *
 * 粒度口径（D7）：一工序一张；「工单级派工」= 批量生成入口（全选即整单）。
 * 排程口径（D3）：前向时段装箱，日容量 = 日历×班次，工序 setup+run 装箱、跨天顺延，
 * 超 planEndDate 置交期风险；排程与重排共用 PACK_LOCK 互斥（checkBatch 同款）。
 * 权限：写 = PLANNER/PLAN_MGR/SHOP_FLOOR/ADMIN 之一；确认 = SHOP_FLOOR/PLAN_MGR/ADMIN。
 */
public interface DispatchService {

    /** 待派工行：Released 工单 × opStatus=PENDING 工序（未派），priority + planStartDate 排序 */
    List<Map<String, Object>> pending(String keyword);

    /**
     * 已生成派工单列表（5.6.1 确认/取消/重排的操作对象——生成接口只返回本次新增，
     * 刷新页面后须能找回）：状态 + 关键字（单号/工单号/WC）筛选，创建时间倒序。
     */
    List<MrpDispatch> list(String status, String keyword);

    /**
     * 生成派工单（单张/批量同一入口）：Released 校验、齐套 L1 门禁（C-4.5-11）、
     * WC 校验（停用/外协供应商 QUALIFIED）、防重；前向排程定计划日期与风险标；
     * 工序 opStatus → DISPATCHED。生成态 PENDING_CONFIRM。
     */
    List<MrpDispatch> generate(List<GenReq> rows);

    /**
     * 缺料派工审批通过后的生成（C-4.5-12 出口，group5 回调调用）：
     * 跳过 L1 齐套门禁、打「缺料先行」标记，其余校验与排程同 {@link #generate}。
     */
    List<MrpDispatch> generateAfterShortageApproval(List<GenReq> rows);

    /** 确认（SHOP_FLOOR/PLAN_MGR/ADMIN）：双点齐套校验（缺料且未经审批 → 422）+ 触发物料预扣 */
    MrpDispatch confirm(String id);

    /** 取消：原因必填；工序 opStatus 回 PENDING；MO 无其他活动派工时释放预扣 */
    MrpDispatch cancel(String id, String reason);

    /**
     * 重新排程（某 WC 自 from 日起）：已确认（RELEASED）占用固定，未确认（PENDING_CONFIRM）
     * 按 MO 优先级（priority → planStartDate → 单号）重装箱（低优先级自动后移，FR-4.5-4-7），
     * 更新计划日期/风险标并留痕操作人与时间。
     */
    List<MrpDispatch> reschedule(String wcCode, LocalDate from);

    /** 生成入参（工序级；wcCode 空 → 取工序快照默认工作中心） */
    record GenReq(String moId, Integer opSeq, String wcCode) {
    }

    // ---------- 审批链（group 5：ShortageDispatch / OutsourceDispatch） ----------

    /**
     * 缺料派工申请（C-4.5-12，spec「缺料派工 L2 审批」）：提交审批底座单节点
     * （BIZ_TYPE=ShortageDispatch、签 ROLE_PLAN_MGR），bizId = moId#opSeq。
     * 缺料标记为 0 时 422（无需缺料先行）；同一工序 PENDING 实例由底座 422 拒重。
     */
    Map<String, Object> applyShortage(String moId, Integer opSeq);

    /**
     * 外协改派申请（BR-4.5-21，spec「外协改派与 L2 审批」）：校验目标 WC 为外协且供应商
     * QUALIFIED（C-4.5-05），提交 BIZ_TYPE=OutsourceDispatch（签 ROLE_PLAN_MGR），
     * bizId = dispatchId#targetWcCode。提交权限 PLANNER/ADMIN（outsourcing 权限矩阵）。
     */
    Map<String, Object> applyOutsource(String dispatchId, String targetWcCode);

    /** 审批通过（回调同事务）：缺料先行生成派工单（跳 L1、打标、已有预扣则刷新基数）+ 推送申请人 */
    MrpDispatch approveShortage(String moId, Integer opSeq, String applyBy, String apprNo);

    /** 审批驳回（回调同事务）：不生成派工单，推送申请人（留痕在审批实例） */
    void rejectShortage(String moId, Integer opSeq, String applyBy, String apprNo);

    /** 审批通过：改派外协 WC + OUTSOURCE 标记 + 推送申请人（驳回由 rejectOutsource 处理，派工不变） */
    MrpDispatch approveOutsource(String dispatchId, String targetWcCode, String applyBy, String apprNo);

    /** 审批驳回：派工单保持原状，推送申请人 */
    void rejectOutsource(String dispatchId, String applyBy, String apprNo);

    /**
     * 外协回联 ORDERED（spec「PR 漏斗下单与回联」场景「转单回联」）：PO 审批全通过（下达）时由
     * 采购域后置钩子调用，按 PR 单号定位外协派工单回写 PO 号并推 OUTSOURCE_PROGRESS 给申请人。
     * 非外协派工的 PO / 幂等重复调用 → 静默返回 false（不影响采购主流程，design D5）。
     */
    boolean recallOrdered(String prNo, String poNo);

    /**
     * 外协回联 RECEIVED（spec 场景「收货回联」）：外协 PO 的 GR 过账完成时由采购域后置钩子调用，
     * 按 PO 号回写收货标记并推 OUTSOURCE_PROGRESS 给申请人。非外协/幂等 → 静默 false。
     */
    boolean recallReceived(String poNo);

    /**
     * 5.6.3 外协候选推荐（spec outsourcing-dispatch「可替代外协资源推荐」+「外协报价参考 D8」）：
     * 定额矩阵中该工序存在定额、类型=外协、启用、供应商维护且 QUALIFIED（C-4.5-05 前置过滤）；
     * 每候选附今日负荷率与协议价参考（生效中/临期协议的中标单价，无则 price=null）。
     * 协议价仅展示参考，不参与任何金额计算或卡控（D8）。无候选 → 空列表。
     *
     * @param opCode      工序编码（与 dispatchId 二选一）
     * @param productCode 协议价查询物料（空则取工单产成品）
     * @param dispatchId  派工单 ID（页面超载下钻入口——内部经 moOp 解析 opCode 与产成品）
     */
    List<Map<String, Object>> outsourceCandidates(String opCode, String productCode, String dispatchId);
}
