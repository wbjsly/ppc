package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpMo;
import com.erp.service.mrp.MoStatusRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单状态白名单单测（task 4.1）：合法路径全通过、非法迁移拒绝、终态封闭。
 */
class MoStatusUnitTest {

    @Test
    void legalTransitionsAllowed() {
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_PLANNED, MrpMo.ST_PENDING));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_PENDING, MrpMo.ST_CONFIRMED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_PENDING, MrpMo.ST_PLANNED)); // 驳回
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_CONFIRMED, MrpMo.ST_RELEASED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_RELEASED, MrpMo.ST_HOLD));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_RELEASED, MrpMo.ST_COMPLETED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_HOLD, MrpMo.ST_RELEASED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_COMPLETED, MrpMo.ST_CLOSED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_PLANNED, MrpMo.ST_CANCELLED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_CONFIRMED, MrpMo.ST_CANCELLED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_RELEASED, MrpMo.ST_CANCELLED));
        assertDoesNotThrow(() -> MoStatusRules.assertTransit(MrpMo.ST_HOLD, MrpMo.ST_CANCELLED));
    }

    @Test
    void illegalTransitionsRejected() {
        // PLANNED 直接 RELEASED（spec 场景「非法状态迁移拒绝」）
        ServiceException e = assertThrows(ServiceException.class,
                () -> MoStatusRules.assertTransit(MrpMo.ST_PLANNED, MrpMo.ST_RELEASED));
        assertEquals(422, e.getCode());
        // 跳过审批
        assertThrows(ServiceException.class,
                () -> MoStatusRules.assertTransit(MrpMo.ST_PLANNED, MrpMo.ST_CONFIRMED));
        // PENDING 不可取消（审批在途）
        assertThrows(ServiceException.class,
                () -> MoStatusRules.assertTransit(MrpMo.ST_PENDING, MrpMo.ST_CANCELLED));
        // 未完工不可关闭
        assertThrows(ServiceException.class,
                () -> MoStatusRules.assertTransit(MrpMo.ST_RELEASED, MrpMo.ST_CLOSED));
        // 回退禁止
        assertThrows(ServiceException.class,
                () -> MoStatusRules.assertTransit(MrpMo.ST_RELEASED, MrpMo.ST_CONFIRMED));
        assertThrows(ServiceException.class,
                () -> MoStatusRules.assertTransit(MrpMo.ST_CLOSED, MrpMo.ST_RELEASED));
    }

    @Test
    void terminalStatesSealed() {
        for (String to : new String[]{MrpMo.ST_PLANNED, MrpMo.ST_PENDING, MrpMo.ST_CONFIRMED,
                MrpMo.ST_RELEASED, MrpMo.ST_HOLD, MrpMo.ST_COMPLETED, MrpMo.ST_CLOSED, MrpMo.ST_CANCELLED}) {
            assertFalse(MoStatusRules.canTransit(MrpMo.ST_CLOSED, to), "CLOSED 终态封闭: " + to);
            assertFalse(MoStatusRules.canTransit(MrpMo.ST_CANCELLED, to), "CANCELLED 终态封闭: " + to);
        }
        assertTrue(MoStatusRules.canTransit(MrpMo.ST_COMPLETED, MrpMo.ST_CLOSED));
    }
}
