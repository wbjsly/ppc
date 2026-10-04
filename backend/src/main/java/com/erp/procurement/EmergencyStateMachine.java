package com.erp.procurement;

import com.erp.common.ServiceException;

import java.util.Map;
import java.util.Set;

/**
 * 紧急采购申请状态机单点守卫（design add-emergency-procurement D3）。
 * 全部状态迁移必须经 require 校验，非法 422 带当前态与可达集合。
 * 工作日计算复用 RequisitionStateMachine（跳周末近似，同一口径）。
 */
public final class EmergencyStateMachine {

    public static final String PENDING_SPECIAL_APPROVAL = "PENDING_SPECIAL_APPROVAL";
    public static final String APPROVED_EMERGENCY = "APPROVED_EMERGENCY";
    public static final String FILLING = "FILLING";
    public static final String COMPLETED = "COMPLETED";
    public static final String OVERDUE_EXCEPTION = "OVERDUE_EXCEPTION";
    public static final String REJECTED = "REJECTED";
    public static final String CLOSED = "CLOSED";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            // 特批中 → 特批通过放行 / 驳回
            PENDING_SPECIAL_APPROVAL, Set.of(APPROVED_EMERGENCY, REJECTED),
            // 放行 → 进入补齐（登记时可能一步到 COMPLETED，经由本边）
            APPROVED_EMERGENCY, Set.of(FILLING, OVERDUE_EXCEPTION, CLOSED),
            FILLING, Set.of(COMPLETED, OVERDUE_EXCEPTION, CLOSED),
            // 逾期例外 → 总监复核恢复关闭（补齐禁走此路径）
            OVERDUE_EXCEPTION, Set.of(CLOSED),
            // 终态
            REJECTED, Set.of(),
            COMPLETED, Set.of(),
            CLOSED, Set.of()
    );

    private EmergencyStateMachine() {
    }

    public static void require(String from, String to) {
        Set<String> ok = ALLOWED.get(from);
        if (ok == null) {
            throw new ServiceException(422, "未知紧急申请状态：" + from);
        }
        if (!ok.contains(to)) {
            throw new ServiceException(422, "状态不允许从 " + from + " 迁移至 " + to
                    + (ok.isEmpty() ? "（已到终态）" : "，可达状态：" + ok));
        }
    }
}
