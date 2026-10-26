package com.erp.pm;

import com.erp.common.ServiceException;
import com.erp.entity.pm.PmProject;
import com.erp.service.pm.PmProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.core.context.SecurityContextHolder.getContext;

/**
 * 项目立项 DbTest（project-initiation spec：项目创建与双路径合同门槛）。
 * 造数前缀 tpi- / 项目名 TPI%。
 */
@SpringBootTest
class ProjectInitiationDbTest {

    @Autowired
    private PmProjectService projectService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        seedUser("tpi-user-pm", "tpipm", "TPI 项目经理", "1");
        seedUser("tpi-user-pm2", "tpipm2", "TPI 项目经理2", "1");
        seedUser("tpi-user-off", "tpioff", "TPI 停用经理", "0");
        seedContract("tpi-con-signed", "TPIC001", "SIGNED");
        seedContract("tpi-con-draft", "TPIC002", "DRAFT");
        authAs("tpi-junit", "ROLE_PMO");
    }

    @AfterEach
    void tearDown() {
        purge();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE IN ('PmBaseline','PmBudgetChange') "
                + "AND (i.BIZ_ID IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%') "
                + " OR i.BIZ_ID IN (SELECT ID FROM erp_pm_budget_change WHERE PROJECT_ID "
                + "   IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%')))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('PmBaseline','PmBudgetChange') "
                + "AND (BIZ_ID IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%') "
                + " OR BIZ_ID IN (SELECT ID FROM erp_pm_budget_change WHERE PROJECT_ID "
                + "   IN (SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%')))");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%')");
        jdbc.update("DELETE FROM erp_pm_budget_change WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%')");
        jdbc.update("DELETE FROM erp_pm_budget_line WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%')");
        jdbc.update("DELETE FROM erp_pm_wbs WHERE PROJECT_ID IN "
                + "(SELECT ID FROM erp_pm_project WHERE NAME LIKE 'TPI%')");
        jdbc.update("DELETE FROM erp_pm_project WHERE NAME LIKE 'TPI%'");
        jdbc.update("DELETE FROM erp_pm_capacity WHERE USER_ID LIKE 'tpi-user-%'");
        jdbc.update("DELETE FROM erp_admin_user_role WHERE USER_ID LIKE 'tpi-user-%'");
        jdbc.update("DELETE FROM erp_admin_user WHERE ID LIKE 'tpi-user-%'");
        jdbc.update("DELETE FROM erp_crm_contract WHERE ID LIKE 'tpi-con-%'");
    }

    private void seedUser(String id, String username, String nick, String status) {
        jdbc.update("INSERT INTO erp_admin_user (ID, USERNAME, PASSWORD, NICK_NAME, STATUS, "
                + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'x', ?, ?, 'junit', NOW(), '0', 0)",
                id, username, nick, status);
    }

    private void seedContract(String id, String no, String status) {
        jdbc.update("INSERT INTO erp_crm_contract (ID, CONTRACT_NO, CUSTOMER_ID, TITLE, AMOUNT, STATUS, "
                + "VERSION_NO, CHANGE_COUNT, SO_COUNT, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'tpi-cust', 'TPI 合同', 100000, ?, 1, 0, 0, '0', 0)", id, no, status);
    }

    private void authAs(String user, String... roles) {
        getContext().setAuthentication(new org.springframework.security.authentication
                .UsernamePasswordAuthenticationToken(user, null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(roles)));
    }

    private Map<String, Object> extPayload(String name, String contractId) {
        return Map.of("name", name, "projType", "研发", "deliveryPath", "EXT",
                "contractId", contractId, "pmUserId", "tpi-user-pm",
                "startDate", "2026-11-01", "endDate", "2027-05-31");
    }

    private Map<String, Object> internalPayload(String name, String formNo) {
        return Map.of("name", name, "projType", "研发", "deliveryPath", "INTERNAL",
                "internalFormNo", formNo, "pmUserId", "tpi-user-pm",
                "startDate", "2026-11-01", "endDate", "2027-05-31");
    }

    @Test
    void extWithSignedContractCreatesDraftWithoutNumber() {
        PmProject p = projectService.create(extPayload("TPI 签约项目", "tpi-con-signed"));
        assertNotNull(p.getId());
        assertEquals(PmProject.ST_DRAFT, p.getStatus(), "创建即草稿");
        assertNull(p.getProjectNo(), "编号不得在创建时生成");
        assertEquals("TPIC001", p.getContractNo(), "带出合同号");
        assertEquals("0", p.getBaselineLocked());
    }

    @Test
    void extWithUnsignedOrMissingContractRejected() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> projectService.create(extPayload("TPI 未签项目", "tpi-con-draft")));
        assertEquals(422, e1.getCode());
        assertTrue(e1.getMessage().contains("合同"), e1.getMessage());
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> projectService.create(extPayload("TPI 无合同项目", "tpi-con-missing")));
        assertEquals(422, e2.getCode());
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> projectService.create(Map.of("name", "TPI 缺合同号", "projType", "研发",
                        "deliveryPath", "EXT", "pmUserId", "tpi-user-pm",
                        "startDate", "2026-11-01", "endDate", "2027-05-31")));
        assertEquals(422, e3.getCode());
    }

    @Test
    void internalRequiresFormNo() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> projectService.create(Map.of("name", "TPI 内部缺单号", "projType", "内部研发",
                        "deliveryPath", "INTERNAL", "pmUserId", "tpi-user-pm",
                        "startDate", "2026-11-01", "endDate", "2027-05-31")));
        assertEquals(422, e.getCode());
        PmProject p = projectService.create(internalPayload("TPI 内部项目", "IF-2026-001"));
        assertEquals("IF-2026-001", p.getInternalFormNo());
        assertNull(p.getContractId(), "内部项目不关联合同");
    }

    @Test
    void pmUserMustExistAndBeEnabled() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> projectService.create(Map.of("name", "TPI 无效经理", "projType", "研发",
                        "deliveryPath", "INTERNAL", "internalFormNo", "IF-X",
                        "pmUserId", "tpi-user-missing",
                        "startDate", "2026-11-01", "endDate", "2027-05-31")));
        assertEquals(422, e1.getCode());
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> projectService.create(Map.of("name", "TPI 停用经理", "projType", "研发",
                        "deliveryPath", "INTERNAL", "internalFormNo", "IF-X",
                        "pmUserId", "tpi-user-off",
                        "startDate", "2026-11-01", "endDate", "2027-05-31")));
        assertEquals(422, e2.getCode());
    }

    @Test
    void pmDataScopeTrimAndDraftOnlyUpdate() {
        PmProject a = projectService.create(internalPayload("TPI 甲项目", "IF-A"));
        authAs("tpi-user-pm2", "ROLE_PM");
        projectService.create(Map.of("name", "TPI 乙项目", "projType", "研发", "deliveryPath", "INTERNAL",
                "internalFormNo", "IF-B", "pmUserId", "tpi-user-pm2",
                "startDate", "2026-11-01", "endDate", "2027-05-31"));
        // PM 仅见自己的项目
        List<Map<String, Object>> asPm2 = projectService.list(null, null, "TPI");
        assertEquals(1, asPm2.size(), "PM 数据裁剪");
        assertEquals("TPI 乙项目", asPm2.get(0).get("name"));
        // PMO 全量
        authAs("tpi-junit", "ROLE_PMO");
        assertEquals(2, projectService.list(null, null, "TPI").size());
        // 非草稿不可改
        jdbc.update("UPDATE erp_pm_project SET STATUS='ACTIVE' WHERE ID=?", a.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> projectService.update(a.getId(), Map.of("name", "TPI 改名")));
        assertEquals(422, e.getCode());
    }
}
