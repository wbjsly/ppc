package com.erp.eam;

import com.erp.entity.eam.EamCipCost;
import com.erp.entity.eam.EamCipProject;
import com.erp.service.eam.EamCipService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * add-asset-capitalization：CIP 台账（spec asset-capitalization）。
 * 归集三类、非法类别 422、合计联动、完工验收状态机。
 */
@SpringBootTest
class EamCipDbTest {

    @Autowired
    private EamCipService cipService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void login() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_eam_cip_cost WHERE PROJECT_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_eam_cip_project WHERE NAME LIKE 'CQT%') t1)");
        jdbc.update("DELETE FROM erp_eam_capitalization WHERE SOURCE_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_eam_cip_project WHERE NAME LIKE 'CQT%') t2)");
        jdbc.update("DELETE FROM erp_eam_cip_project WHERE NAME LIKE 'CQT%'");
    }

    private EamCipProject mkProject(String name) {
        EamCipProject p = new EamCipProject();
        p.setName(name);
        p.setBudgetAmount(new BigDecimal("1000000"));
        return p;
    }

    private EamCipCost mkCost(String projectId, String type, String amount) {
        EamCipCost c = new EamCipCost();
        c.setProjectId(projectId);
        c.setCostType(type);
        c.setAmount(new BigDecimal(amount));
        c.setDescription("CQT 归集");
        return c;
    }

    @Test
    void costThreeTypesAndSummary() {
        EamCipProject p = cipService.createProject(mkProject("CQT CIP 归集"));
        assertTrue(p.getProjectNo().startsWith("CIP"), "编号自动生成：" + p.getProjectNo());
        cipService.addCost(mkCost(p.getId(), "ENGINGEE", "800000"));
        cipService.addCost(mkCost(p.getId(), "INSTALL", "80000"));
        cipService.addCost(mkCost(p.getId(), "INTEREST", "20000"));

        Map<String, Object> detail = cipService.detail(p.getId());
        assertEquals(0, new BigDecimal("900000")
                .compareTo((BigDecimal) detail.get("costTotal")), "归集合计 90 万");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) detail.get("costSummary");
        assertEquals(3, summary.size(), "三类固定输出");
        assertEquals(0, new BigDecimal("80000")
                .compareTo((BigDecimal) summary.get(1).get("amount")), "安装费 8 万");
    }

    @Test
    void invalidType422() {
        EamCipProject p = cipService.createProject(mkProject("CQT CIP 非法类别"));
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> cipService.addCost(mkCost(p.getId(), "OTHER", "100")));
        assertEquals(422, ex.getCode(), "非法类别 422");
    }

    @Test
    void completeStateMachine() {
        EamCipProject p = cipService.createProject(mkProject("CQT CIP 完工"));
        // 无归集不可完工
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> cipService.complete(p.getId(), null));
        assertEquals(422, ex.getCode(), "无归集完工 422");

        cipService.addCost(mkCost(p.getId(), "ENGINGEE", "500000"));
        EamCipProject done = cipService.complete(p.getId(), null);
        assertEquals("COMPLETED", done.getStatus(), "完工状态");
        assertNotNull(done.getCompletionDate(), "完工验收日期落库（超期预警基准）");
        // 幂等
        assertEquals("COMPLETED", cipService.complete(p.getId(), null).getStatus(), "重复完工幂等");
        // 完工后不可再归集
        var ex2 = assertThrows(com.erp.common.ServiceException.class,
                () -> cipService.addCost(mkCost(p.getId(), "INSTALL", "100")));
        assertEquals(422, ex2.getCode(), "完工后归集 422");
    }

    @Test
    void updateOnlyOpen() {
        EamCipProject p = cipService.createProject(mkProject("CQT CIP 修改"));
        EamCipProject upd = new EamCipProject();
        upd.setId(p.getId());
        upd.setName("CQT CIP 修改后");
        assertEquals("CQT CIP 修改后", cipService.updateProject(upd).getName(), "OPEN 可改");

        cipService.addCost(mkCost(p.getId(), "ENGINGEE", "1000"));
        cipService.complete(p.getId(), null);
        var ex = assertThrows(com.erp.common.ServiceException.class,
                () -> cipService.updateProject(upd));
        assertEquals(422, ex.getCode(), "完工后不可改 422");
    }
}
