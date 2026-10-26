package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.system.ApprovalInstance;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单权限矩阵库测（task 6.4 + spec「角色与菜单权限」场景）：
 * PLANNER 越权签署 403、未认证 401、PLAN_MGR 签署成功、非计划员角色写 403。
 */
@SpringBootTest
class MoPermissionDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("mo-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        MoSeed.cleanup(jdbc);
    }

    private MrpMo createMo() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "权限产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "权限子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal("10"));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(7));
        return (MrpMo) moService.create(h).get("mo");
    }

    private String activeTaskId(String moId) {
        return jdbc.queryForObject(
                "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval a ON t.APPR_ID=a.ID " +
                        "WHERE a.BIZ_TYPE='MoApprove' AND a.BIZ_ID=? AND t.STATUS='ACTIVE'",
                String.class, moId);
    }

    @Test
    void plannerCannotSignApproval() {
        MrpMo mo = createMo();
        moService.submit(mo.getId());
        String taskId = activeTaskId(mo.getId());
        // 仅 PLANNER（签署节点 = PLAN_MGR）→ 403
        MoTestAuth.login("eng-only", "ROLE_PLANNER");
        ServiceException e = assertThrows(ServiceException.class,
                () -> approvalEngine.pass(taskId, "我想通过"));
        assertEquals(403, e.getCode(), "PLANNER 越权签署 403");
    }

    @Test
    void unauthenticatedReadAndWrite401() {
        MoTestAuth.logout();
        ServiceException read = assertThrows(ServiceException.class,
                () -> moService.list(null, null, null));
        assertEquals(401, read.getCode(), "未认证读 401");
        MrpMo probe = new MrpMo();
        probe.setProductCode("X");
        probe.setQty(BigDecimal.ONE);
        probe.setPlanEndDate(LocalDate.now().plusDays(1));
        ServiceException write = assertThrows(ServiceException.class,
                () -> moService.create(probe));
        assertEquals(401, write.getCode(), "未认证写 401");
    }

    @Test
    void nonPlannerRoleWrite403() {
        MoTestAuth.login("viewer", "ROLE_USER");
        MrpMo probe = new MrpMo();
        probe.setProductCode("X");
        probe.setQty(BigDecimal.ONE);
        probe.setPlanEndDate(LocalDate.now().plusDays(1));
        ServiceException e = assertThrows(ServiceException.class, () -> moService.create(probe));
        assertEquals(403, e.getCode(), "角色不符写 403");
    }

    @Test
    void planManagerSignsSuccessfully() {
        MrpMo mo = createMo();
        moService.submit(mo.getId());
        String taskId = activeTaskId(mo.getId());
        MoTestAuth.login("mgr-tester", "ROLE_PLAN_MGR");
        approvalEngine.pass(taskId, "同意");
        ApprovalInstance inst = approvalEngine.findByBiz(MoService.BIZ_APPROVE, mo.getId());
        assertEquals("APPROVED", inst.getStatus(), "计划主管签署成功（回调链置 CONFIRMED）");
        assertTrue(moService.list(null, null, null).stream()
                        .anyMatch(m -> m.getId().equals(mo.getId())
                                && MrpMo.ST_CONFIRMED.equals(m.getStatus())),
                "工单已 CONFIRMED");
    }
}
