package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.service.impl.qms.InspectionLotServiceImpl;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.OsMaterialService;
import com.erp.service.proc.OsSettlementService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 外协加工权限矩阵（task 7.1，spec outsourcing-settlement「菜单与权限」）：
 * 两条线 —— 计划采购（5.10.1/3/4 = PLANNER/PLAN_MGR/PURCHASER/ADMIN）
 *          质量（5.10.2 = INSPECTOR/QUALITY_ENG/QUALITY_MGR/ADMIN）；
 * PLANNER 见 1/3/4 不见 2；INSPECTOR 反之；越权 403、未认证 401。
 */
@SpringBootTest
class OutsourcingPermissionDbTest {

    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private OsMaterialService osmService;
    @Autowired
    private OsSettlementService osrService;
    @Autowired
    private InspectionLotServiceImpl lotService;

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
    }

    /** 计划采购线：1/3/4 过权限门、5.10.2 403 */
    @Test
    void plannerLineVisibleExceptInspection() {
        MoTestAuth.login("perm-plan", "ROLE_PLANNER");

        // 5.10.1 工作台可读（走到业务查询，无异常）
        dispatchService.workbench(null, null, null, null);
        // 5.10.1 外协字段维护：非外协派工单 404（过权限门）
        assertEquals(404, assertThrows(ServiceException.class,
                () -> dispatchService.maintainOsFields("no-such", null, null, null)).getCode(),
                "PLANNER 维护外协字段过权限门（404 而非 403）");

        // 5.10.3 发料单列表可读
        osmService.list(null, null, null);
        // 5.10.3 创建：过权限门 → 业务校验 404（派工单不存在）
        assertEquals(404, assertThrows(ServiceException.class,
                () -> osmService.create(Map.of("dispatchId", "no-such",
                        "lines", java.util.List.of(Map.of())))).getCode(),
                "PLANNER 创建发料过权限门（404 而非 403）");

        // 5.10.4 对账单列表可读
        osrService.list(null, null, null);
        // 5.10.4 生成：过权限门 → 业务校验 422（PO 必填）
        assertEquals(422, assertThrows(ServiceException.class,
                () -> osrService.generate(null)).getCode(),
                "PLANNER 生成对账过权限门（422 而非 403）");

        // 5.10.2 收货检验：计划线不可见 → 403
        assertEquals(403, assertThrows(ServiceException.class,
                () -> lotService.outsourceBoard(1, 10, null)).getCode(),
                "PLANNER 查外协检验 403（质量线独占）");
    }

    /** 质量线：5.10.2 过权限门、1/3/4 全 403 */
    @Test
    void inspectorLineOnlyInspection() {
        MoTestAuth.login("perm-qc", "ROLE_INSPECTOR");

        // 5.10.2 外协检验看板可读（走到查询）
        lotService.outsourceBoard(1, 10, null);

        // 5.10.1 工作台 403
        assertEquals(403, assertThrows(ServiceException.class,
                () -> dispatchService.workbench(null, null, null, null)).getCode(),
                "INSPECTOR 查外协订单工作台 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> dispatchService.maintainOsFields("x", null, null, null)).getCode(),
                "INSPECTOR 维护外协字段 403");

        // 5.10.3 发料单读写 403
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osmService.list(null, null, null)).getCode(), "INSPECTOR 查发料单 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osmService.create(Map.of("dispatchId", "x",
                        "lines", java.util.List.of(Map.of())))).getCode(), "INSPECTOR 创建发料 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osmService.post("x")).getCode(), "INSPECTOR 发料出库 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osmService.delete("x")).getCode(), "INSPECTOR 删发料草稿 403");

        // 5.10.4 对账读写 403（spec 场景「ROLE_INSPECTOR 调用对账单创建 → 403」）
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osrService.list(null, null, null)).getCode(), "INSPECTOR 查对账 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osrService.generate("PO-X")).getCode(), "INSPECTOR 生成对账 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osrService.delete("x")).getCode(), "INSPECTOR 删对账 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osrService.submit("x")).getCode(), "INSPECTOR 提交对账审批 403");
        assertEquals(403, assertThrows(ServiceException.class,
                () -> osrService.listAp(null, null)).getCode(), "INSPECTOR 查应付挂账 403");
    }

    /** 未认证：两线读写全 401 */
    @Test
    void unauthenticated401() {
        MoTestAuth.logout();
        assertEquals(401, assertThrows(ServiceException.class,
                () -> dispatchService.workbench(null, null, null, null)).getCode(), "未认证 5.10.1 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> lotService.outsourceBoard(1, 10, null)).getCode(), "未认证 5.10.2 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> osmService.list(null, null, null)).getCode(), "未认证 5.10.3 401");
        assertEquals(401, assertThrows(ServiceException.class,
                () -> osrService.list(null, null, null)).getCode(), "未认证 5.10.4 401");
    }
}
