package com.erp.routing;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpRouting;
import com.erp.service.mrp.RoutingStatusRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路线状态白名单（change add-routing-management，任务 4.1）：
 * 合法路径全通过 / 非法迁移（DRAFT 直接 PUBLISHED、OBSOLETE 出边等）422。
 */
class RoutingStatusUnitTest {

    @Test
    void allowedTransitionsPass() {
        assertTrue(RoutingStatusRules.canTransit(MrpRouting.ST_DRAFT, MrpRouting.ST_PENDING), "草稿→待审");
        assertTrue(RoutingStatusRules.canTransit(MrpRouting.ST_PENDING, MrpRouting.ST_DRAFT), "驳回→草稿");
        assertTrue(RoutingStatusRules.canTransit(MrpRouting.ST_PENDING, MrpRouting.ST_PUBLISHED), "通过→发布");
        assertTrue(RoutingStatusRules.canTransit(MrpRouting.ST_PUBLISHED, MrpRouting.ST_REVISED), "被取代→已变更");
        assertTrue(RoutingStatusRules.canTransit(MrpRouting.ST_PUBLISHED, MrpRouting.ST_OBSOLETE), "发布→废止");
        assertTrue(RoutingStatusRules.canTransit(MrpRouting.ST_REVISED, MrpRouting.ST_OBSOLETE), "已变更→废止");
    }

    @Test
    void illegalTransitionsRejectedWith422() {
        assertFalse(RoutingStatusRules.canTransit(MrpRouting.ST_DRAFT, MrpRouting.ST_PUBLISHED), "草稿不可直达发布");
        assertFalse(RoutingStatusRules.canTransit(MrpRouting.ST_DRAFT, MrpRouting.ST_OBSOLETE), "草稿不可直接废止");
        assertFalse(RoutingStatusRules.canTransit(MrpRouting.ST_PENDING, MrpRouting.ST_OBSOLETE), "待审不可直接废止");
        assertFalse(RoutingStatusRules.canTransit(MrpRouting.ST_OBSOLETE, MrpRouting.ST_DRAFT), "废止无出边");
        assertFalse(RoutingStatusRules.canTransit(MrpRouting.ST_PUBLISHED, MrpRouting.ST_DRAFT), "发布不可回草稿");
        assertFalse(RoutingStatusRules.canTransit(null, MrpRouting.ST_PENDING), "null 拒绝");

        ServiceException e = assertThrows(ServiceException.class,
                () -> RoutingStatusRules.assertTransit(MrpRouting.ST_DRAFT, MrpRouting.ST_PUBLISHED));
        e.getCode();
        org.junit.jupiter.api.Assertions.assertEquals(422, e.getCode(), "非法迁移 422");
    }
}
