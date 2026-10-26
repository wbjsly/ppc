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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单审批库测（task 5.1）：提交生成待办、重复提交 422、通过后状态与留痕、驳回回 PLANNED 带意见。
 * 底座事务链：pass/reject → 回调（MoApprovalCallback）→ MoService.onApproved/onRejected 同事务。
 */
@SpringBootTest
class MoPublishDbTest {

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
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "审批产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "审批子件", "BUY");
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
    void submitCreatesApprovalAndTodo() {
        MrpMo mo = createMo();
        MrpMo submitted = moService.submit(mo.getId());
        assertEquals(MrpMo.ST_PENDING, submitted.getStatus(), "提交后 PENDING");
        assertNotNull(submitted.getSubmitBy(), "提交人留痕");
        ApprovalInstance inst = approvalEngine.findByBiz(MoService.BIZ_APPROVE, mo.getId());
        assertNotNull(inst, "审批实例已挂");
        assertEquals("ROLE_PLAN_MGR", jdbc.queryForObject(
                "SELECT ROLE_REQUIRED FROM erp_sys_approval_task WHERE ID=?",
                String.class, activeTaskId(mo.getId())), "签署节点 = 计划主管");
        // 主管视角待办可见
        MoTestAuth.login("mgr-tester", "ROLE_PLAN_MGR");
        assertTrue(approvalEngine.todo().stream().anyMatch(t ->
                String.valueOf(t.getOrDefault("bizId", "")).equals(mo.getId())),
                "计划主管待办含本单");
    }

    @Test
    void duplicateSubmitRejected() {
        MrpMo mo = createMo();
        moService.submit(mo.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> moService.submit(mo.getId()));
        assertEquals(422, e.getCode(), "重复提交 422");
        assertTrue(e.getMessage().contains("重复提交") || e.getMessage().contains("计划状态"),
                e.getMessage());
    }

    @Test
    void passConfirmsWithTrace() {
        MrpMo mo = createMo();
        moService.submit(mo.getId());
        String taskId = activeTaskId(mo.getId());
        MoTestAuth.login("mgr-tester", "ROLE_PLAN_MGR");
        approvalEngine.pass(taskId, "同意发布");
        MrpMo after = moService.list(null, mo.getProductCode(), null).stream()
                .filter(m -> m.getId().equals(mo.getId())).findFirst().orElseThrow();
        assertEquals(MrpMo.ST_CONFIRMED, after.getStatus(), "通过 → CONFIRMED（回调链）");
        assertNotNull(after.getApproveBy(), "审批人留痕");
        assertNotNull(after.getApproveAt(), "审批时间留痕");
    }

    @Test
    void rejectReturnsToPlannedWithOpinion() {
        MrpMo mo = createMo();
        moService.submit(mo.getId());
        String taskId = activeTaskId(mo.getId());
        MoTestAuth.login("mgr-tester", "ROLE_PLAN_MGR");
        approvalEngine.reject(taskId, "数量需复核");
        MrpMo after = moService.list(null, mo.getProductCode(), null).stream()
                .filter(m -> m.getId().equals(mo.getId())).findFirst().orElseThrow();
        assertEquals(MrpMo.ST_PLANNED, after.getStatus(), "驳回回 PLANNED");
        assertEquals("数量需复核", after.getRejectReason(), "意见回填工单");
        // 驳回后可再次提交
        MoTestAuth.login("mo-tester", "ROLE_PLANNER", "ROLE_ADMIN");
        assertEquals(MrpMo.ST_PENDING, moService.submit(mo.getId()).getStatus(), "可重新提交");
    }
}
