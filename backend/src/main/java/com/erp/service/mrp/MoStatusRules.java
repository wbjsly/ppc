package com.erp.service.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpMo;

import java.util.Map;
import java.util.Set;

/**
 * 工单状态白名单（change add-work-order-management，spec work-order-management「工单审批状态机」）。
 *
 * PLANNED → PENDING(审批) → CONFIRMED → RELEASED → COMPLETED → CLOSED；
 * 旁路 HOLD（仅自 RELEASED 挂起，恢复回 RELEASED）与 CANCELLED（终态）。
 * 取消允许 PLANNED/CONFIRMED/RELEASED/HOLD（PENDING 不可——审批在途，须先由签署侧驳回）。
 * 非法迁移（如 PLANNED 直接 RELEASED）一律拒绝。
 */
public final class MoStatusRules {

    private MoStatusRules() {
    }

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            MrpMo.ST_PLANNED, Set.of(MrpMo.ST_PENDING, MrpMo.ST_CANCELLED),
            MrpMo.ST_PENDING, Set.of(MrpMo.ST_CONFIRMED, MrpMo.ST_PLANNED),
            MrpMo.ST_CONFIRMED, Set.of(MrpMo.ST_RELEASED, MrpMo.ST_CANCELLED),
            MrpMo.ST_RELEASED, Set.of(MrpMo.ST_HOLD, MrpMo.ST_COMPLETED, MrpMo.ST_CANCELLED),
            MrpMo.ST_HOLD, Set.of(MrpMo.ST_RELEASED, MrpMo.ST_CANCELLED),
            MrpMo.ST_COMPLETED, Set.of(MrpMo.ST_CLOSED),
            // 终态
            MrpMo.ST_CLOSED, Set.of(),
            MrpMo.ST_CANCELLED, Set.of()
    );

    /** 白名单断言；非法迁移 → 422 */
    public static void assertTransit(String from, String to) {
        Set<String> ok = ALLOWED.get(from);
        if (ok == null || !ok.contains(to)) {
            throw new ServiceException(422,
                    "非法状态迁移（" + from + " → " + to + "），当前状态不允许该操作");
        }
    }

    public static boolean canTransit(String from, String to) {
        Set<String> ok = ALLOWED.get(from);
        return ok != null && ok.contains(to);
    }
}
