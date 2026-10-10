package com.erp.plan;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpRun;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.MrpPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 权限矩阵（change add-mrp-demand-planning，spec mrp-demand-planning「权限与菜单可达性」，任务 7.2）：
 * PLANNER 全通过 / ADMIN 全通过 / 非计划员 403 / 未认证 401。
 */
@SpringBootTest
class MrpPermissionDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        PlanSeed.cleanup(jdbc);
        categoryCode = PlanSeed.mkCategory(jdbc);
        MdmItem item = PlanSeed.mkItem(itemService, categoryCode, "权限件", "BUY");
        PlanSeed.mkSo(jdbc, item.getItemCode(), "10", "0", LocalDate.now().plusDays(9));
    }

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        PlanSeed.cleanup(jdbc);
    }

    @Test
    void plannerCanRun() {
        PlanTestAuth.login("planner-tester", "ROLE_PLANNER");
        Map<String, Object> run = planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        assertEquals(MrpRun.ST_DONE, run.get("runStatus"), "PLANNER 运行全通过");
    }

    @Test
    void adminCanRun() {
        PlanTestAuth.login("admin-tester", "ROLE_ADMIN");
        Map<String, Object> run = planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE);
        assertEquals(MrpRun.ST_DONE, run.get("runStatus"), "ADMIN 全通过");
    }

    @Test
    void nonPlannerRejectedWith403() {
        PlanTestAuth.login("eng-tester", "ROLE_PROCESS_ENG");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE));
        assertEquals(403, ex.getCode(), "非计划员 403");
    }

    @Test
    void unauthenticatedRejectedWith401() {
        SecurityContextHolder.clearContext();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> planService.run(MrpRun.SCOPE_CATEGORY, PlanSeed.CAT_CODE));
        assertEquals(401, ex.getCode(), "未认证 401");
    }
}
