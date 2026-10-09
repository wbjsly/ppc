package com.erp.procurement;

import com.erp.common.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * 招标项目状态机单点守卫（add-tender-bidding design D1，对照 RfqStateMachine）。
 * 全部状态迁移必须经 require 校验，非法迁移 422 带当前态与可达集合。
 *
 * <pre>
 * DRAFT --开始报名--> BIDDING --截止锁价(sweep)--> LOCKED --开标--> EVALUATING
 *   --提交定标--> PENDING_AWARD --采购总监批准--> AWAITING_PUBLICITY
 *   --期满无异议--> AWARDED   |   --有效异议--> OBJECTION
 * OBJECTION --复核维持--> AWAITING_PUBLICITY | --重新招标--> CANCELLED
 * PENDING_AWARD --驳回--> EVALUATING
 * 锁价前各阶段 + 公示前可作废 --> CANCELLED（已生成框架协议不可作废）
 * </pre>
 */
public final class TenderStateMachine {

    /** 立项草稿 */
    public static final String DRAFT = "DRAFT";
    /** 报名/竞价中 */
    public static final String BIDDING = "BIDDING";
    /** 已锁价（截止即锁，BR-4.2-06 / 偏差 D5） */
    public static final String LOCKED = "LOCKED";
    /** 评标中 */
    public static final String EVALUATING = "EVALUATING";
    /** 待定标审批（采购总监） */
    public static final String PENDING_AWARD = "PENDING_AWARD";
    /** 公示中（BR-4.2-48） */
    public static final String AWAITING_PUBLICITY = "AWAITING_PUBLICITY";
    /** 异议复核中（BR-4.2-48 冻结定标） */
    public static final String OBJECTION = "OBJECTION";
    /** 已定标（终态） */
    public static final String AWARDED = "AWARDED";
    /** 已作废（终态） */
    public static final String CANCELLED = "CANCELLED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // 立项后开始报名
            DRAFT, Set.of(BIDDING, CANCELLED),
            // 截止锁价由懒 sweep 触发；报名不足时可延期（留在 BIDDING）或作废
            BIDDING, Set.of(LOCKED, CANCELLED),
            // 开标进入评标
            LOCKED, Set.of(EVALUATING, CANCELLED),
            // 提交定标审批
            EVALUATING, Set.of(PENDING_AWARD, CANCELLED),
            // 采购总监批准 -> 公示；驳回 -> 回评标
            PENDING_AWARD, Set.of(AWAITING_PUBLICITY, EVALUATING, CANCELLED),
            // 公示期满无异议 -> 定标；有效异议 -> 冻结复核
            AWAITING_PUBLICITY, Set.of(AWARDED, OBJECTION, CANCELLED),
            // 复核维持 -> 恢复公示流程；重新招标 -> 作废原定标
            OBJECTION, Set.of(AWAITING_PUBLICITY, CANCELLED),
            AWARDED, Set.of(),
            CANCELLED, Set.of()
    );

    private TenderStateMachine() {
    }

    /** 头状态迁移校验；非法 422 带当前态与可达集合 */
    public static void require(String from, String to) {
        Set<String> ok = ALLOWED.get(from);
        if (ok == null) {
            throw new ServiceException(422, "未知招标状态：" + from);
        }
        if (!ok.contains(to)) {
            throw new ServiceException(422, "状态不允许从 " + from + " 迁移至 " + to
                    + (ok.isEmpty() ? "（已到终态）" : "，可达状态：" + ok));
        }
    }

    /** 是否已到终态（不可再迁移） */
    public static boolean isTerminal(String status) {
        return Set.of(AWARDED, CANCELLED).contains(status);
    }

    /** 是否已锁价（锁价后禁录报价、禁改截止、禁延期） */
    public static boolean isLocked(String status) {
        return !DRAFT.equals(status) && !BIDDING.equals(status);
    }
}
