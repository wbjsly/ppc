package com.erp.mrp;

import com.erp.common.ServiceException;
import com.erp.entity.mrp.MrpBom;
import com.erp.service.mrp.BomStatusRules;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOM 状态白名单纯逻辑（change add-bom-management，任务 4.1）：
 * 全部合法路径放行 / 非法迁移（DRAFT 直接 PUBLISHED 等）422 / 终态不可再迁。
 */
class BomStatusUnitTest {

    @Test
    void allLegalTransitionsAllowed() {
        assertTrue(BomStatusRules.canTransit(MrpBom.ST_DRAFT, MrpBom.ST_PENDING), "提交审核");
        assertTrue(BomStatusRules.canTransit(MrpBom.ST_PENDING, MrpBom.ST_DRAFT), "驳回退回");
        assertTrue(BomStatusRules.canTransit(MrpBom.ST_PENDING, MrpBom.ST_PUBLISHED), "通过发布");
        assertTrue(BomStatusRules.canTransit(MrpBom.ST_PUBLISHED, MrpBom.ST_REVISED), "被新版本取代");
        assertTrue(BomStatusRules.canTransit(MrpBom.ST_PUBLISHED, MrpBom.ST_OBSOLETE), "手动废止（已发布）");
        assertTrue(BomStatusRules.canTransit(MrpBom.ST_REVISED, MrpBom.ST_OBSOLETE), "手动废止（已变更）");
    }

    @Test
    void illegalTransitionsRejected() {
        assertFalse(BomStatusRules.canTransit(MrpBom.ST_DRAFT, MrpBom.ST_PUBLISHED), "草稿不得直接发布");
        assertFalse(BomStatusRules.canTransit(MrpBom.ST_DRAFT, MrpBom.ST_OBSOLETE), "草稿不得废止");
        assertFalse(BomStatusRules.canTransit(MrpBom.ST_DRAFT, MrpBom.ST_REVISED), "草稿不得已变更");
        assertFalse(BomStatusRules.canTransit(MrpBom.ST_PUBLISHED, MrpBom.ST_PENDING), "已发布不得回待审");
        assertFalse(BomStatusRules.canTransit(MrpBom.ST_REVISED, MrpBom.ST_PUBLISHED), "已变更不得再发布");
        assertFalse(BomStatusRules.canTransit(MrpBom.ST_OBSOLETE, MrpBom.ST_PUBLISHED), "已废止为终态");
        assertFalse(BomStatusRules.canTransit(null, MrpBom.ST_DRAFT), "空来源拒绝");
        assertFalse(BomStatusRules.canTransit(MrpBom.ST_DRAFT, "NO_SUCH_STATUS"), "未知目标拒绝");
    }

    @Test
    void assertTransitThrows422OnIllegal() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> BomStatusRules.assertTransit(MrpBom.ST_DRAFT, MrpBom.ST_PUBLISHED));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("DRAFT"), "消息含来源状态：" + ex.getMessage());
    }
}
