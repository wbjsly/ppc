package com.erp.service.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpRouting;

import java.util.Map;
import java.util.Set;

/**
 * 工艺路线状态白名单（change add-routing-management，spec routing-management「路线状态机与唯一在途」/ 任务 4.1）。
 * 合法路径（与 bom-management 同构）：
 *   DRAFT → PENDING（提交审核）
 *   PENDING → DRAFT（驳回退回）
 *   PENDING → PUBLISHED（审核通过发布）
 *   PUBLISHED → REVISED（同产品新版本发布后被取代）
 *   PUBLISHED → OBSOLETE / REVISED → OBSOLETE（工艺主管手动废止）
 * 其余迁移一律 422 阻断（如 DRAFT 直接 PUBLISHED）。
 * 执行侧统一走 CAS（WHERE id + status + ver_no，影响行数 0 → 422 并发冲突）。
 */
public final class RoutingStatusRules {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            MrpRouting.ST_DRAFT, Set.of(MrpRouting.ST_PENDING),
            MrpRouting.ST_PENDING, Set.of(MrpRouting.ST_DRAFT, MrpRouting.ST_PUBLISHED),
            MrpRouting.ST_PUBLISHED, Set.of(MrpRouting.ST_REVISED, MrpRouting.ST_OBSOLETE),
            MrpRouting.ST_REVISED, Set.of(MrpRouting.ST_OBSOLETE),
            MrpRouting.ST_OBSOLETE, Set.of()
    );

    private RoutingStatusRules() {
    }

    public static boolean canTransit(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        Set<String> targets = ALLOWED.get(from);
        return targets != null && targets.contains(to);
    }

    /** 非法迁移 → 422 */
    public static void assertTransit(String from, String to) {
        if (!canTransit(from, to)) {
            throw new ServiceException(422, "非法状态迁移：" + from + " → " + to);
        }
    }
}
