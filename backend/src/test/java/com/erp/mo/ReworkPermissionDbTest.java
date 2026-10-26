package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.service.mrp.ReworkOrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 返工权限矩阵（task 4.2，spec rework-order「菜单与权限」+ rework-execution 5.8.2：
 * 服务层即 spec 校验点——SHOP_FLOOR 全 403 / PLANNER 计划侧 / QUALITY 执行侧 / 未认证 401）。
 */
@SpringBootTest
class ReworkPermissionDbTest {

    @Autowired
    private ReworkOrderService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        MoSeed.cleanup(jdbc);
    }

    private com.erp.entity.mrp.MrpMo emptyHead() {
        com.erp.entity.mrp.MrpMo h = new com.erp.entity.mrp.MrpMo();
        h.setSrcType("NCR");
        h.setSrcNo("NCR-PERM-TEST");
        h.setProductCode("X");
        return h;
    }

    @Test
    void permissionMatrix() {
        // ① SHOP_FLOOR：全 403（菜单不可见的接口面）
        MoTestAuth.login("perm-floor", "ROLE_SHOP_FLOOR");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.create(emptyHead())).getCode(), "SHOP_FLOOR 建单 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.sourceCandidates("NCR")).getCode(), "SHOP_FLOOR 来源候选 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.applySecond("NCR-X")).getCode(), "SHOP_FLOOR 二次审批 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.enforceScrap("mo-x")).getCode(), "SHOP_FLOOR 强制报废 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.closeFromInspection("mo-x")).getCode(), "SHOP_FLOOR 入库重试 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.phaseOf("mo-x")).getCode(), "SHOP_FLOOR 阶段查询 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.list(null, null)).getCode(), "SHOP_FLOOR 列表 403");

        // ② PLANNER：计划侧过门（走到业务校验 422 而非 403）、执行类 403
        MoTestAuth.login("perm-planner", "ROLE_PLANNER");
        // applySecond 是质量侧 → PLANNER 403（requireQuality）
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.applySecond("NCR-PERM-TEST")).getCode(),
                "PLANNER 发起二次审批 403");
        assertEquals(422, assertThrows(ServiceException.class,
                () -> service.create(emptyHead())).getCode(),
                "PLANNER 建单过权限门（走到来源校验 422）：" );
        assertEquals(422, assertThrows(ServiceException.class,
                () -> service.closeFromInspection("mo-no-such")).getCode(),
                "PLANNER 入库重试过权限门（工单不存在 422）");
        service.list(null, null); // 计划侧列表可读（无异常即通过）

        // ③ QUALITY_ENG：执行侧过门、计划侧 403
        MoTestAuth.login("perm-quality", "ROLE_QUALITY_ENG");
        assertEquals(422, assertThrows(ServiceException.class,
                () -> service.applySecond("NCR-PERM-TEST")).getCode(),
                "QUALITY 发起二次审批过门（无工单 422）");
        assertEquals(422, assertThrows(ServiceException.class,
                () -> service.closeFromInspection("mo-no-such")).getCode(),
                "QUALITY 入库重试过门（工单不存在 422）");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.create(emptyHead())).getCode(), "QUALITY 建单 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> service.sourceCandidates("NCR")).getCode(), "QUALITY 来源候选 403（计划侧）");
        service.list(null, null); // 质量侧只读可

        // ④ 未认证 401
        MoTestAuth.logout();
        assertEquals(401, assertThrows(ServiceException.class,
                () -> service.create(emptyHead())).getCode(), "未认证建单 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> service.applySecond("NCR-X")).getCode(), "未认证二次审批 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> service.phaseOf("mo-x")).getCode(), "未认证阶段查询 401");
    }
}
