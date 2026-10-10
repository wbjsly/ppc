package com.erp.service.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpBom;

import java.util.Map;
import java.util.Set;

/**
 * BOM 状态白名单（change add-bom-management，spec bom-management 状态流转 / 任务 4.1）。
 * 合法路径（00-erp-spec 4.5-1 状态图）：
 *   DRAFT → PENDING（提交审核）
 *   PENDING → DRAFT（驳回退回）
 *   PENDING → PUBLISHED（审核通过发布）
 *   PUBLISHED → REVISED（同父项新版本发布后被取代，BR-4.5-08 唯一已发布）
 *   PUBLISHED → OBSOLETE / REVISED → OBSOLETE（工艺主管手动废止）
 * 其余迁移一律 422 阻断（如 DRAFT 直接 PUBLISHED）。
 * 执行侧统一走 CAS（WHERE id + status + ver_no，影响行数 0 → 422 并发冲突）。
 */
public final class BomStatusRules {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            MrpBom.ST_DRAFT, Set.of(MrpBom.ST_PENDING),
            MrpBom.ST_PENDING, Set.of(MrpBom.ST_DRAFT, MrpBom.ST_PUBLISHED),
            MrpBom.ST_PUBLISHED, Set.of(MrpBom.ST_REVISED, MrpBom.ST_OBSOLETE),
            MrpBom.ST_REVISED, Set.of(MrpBom.ST_OBSOLETE),
            MrpBom.ST_OBSOLETE, Set.of()
    );

    private BomStatusRules() {
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
