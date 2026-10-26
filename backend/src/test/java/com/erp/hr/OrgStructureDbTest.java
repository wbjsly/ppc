package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrChangeLogDao;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrGradeDao;
import com.erp.dao.hr.HrOrgChangeDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrGrade;
import com.erp.entity.hr.HrOrgChange;
import com.erp.entity.hr.HrPosition;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.hr.EmployeeArchiveService;
import com.erp.service.hr.HrGradeService;
import com.erp.service.hr.HrOrgChangeService;
import com.erp.service.hr.HrOrgService;
import com.erp.service.hr.HrReportService;
import com.erp.service.impl.hr.HrOrgChangeServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10-2 组织管理 DbTest（org-structure-management：职级/部门/岗位/汇报/合并级联）。 */
@SpringBootTest
class OrgStructureDbTest {

    @Autowired
    private HrGradeService gradeService;
    @Autowired
    private HrOrgService orgService;
    @Autowired
    private HrReportService reportService;
    @Autowired
    private HrOrgChangeService orgChangeService;
    @Autowired
    private EmployeeArchiveService archiveService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private HrGradeDao gradeDao;
    @Autowired
    private HrDeptDao deptDao;
    @Autowired
    private HrPositionDao posDao;
    @Autowired
    private HrEmployeeDao empDao;
    @Autowired
    private HrOrgChangeDao changeDao;
    @Autowired
    private HrChangeLogDao logDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_grade WHERE CREATE_BY LIKE 'junit%' OR CREATE_BY IN ('approval','org-change')");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE = 'HrOrgChange'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'HrOrgChange'");
        jdbc.update("DELETE FROM hr_org_change WHERE CREATE_BY LIKE 'junit%' OR CREATE_BY = 'approval'");
        jdbc.update("DELETE FROM hr_change_log WHERE CHANGE_BY LIKE 'junit%'");
        jdbc.update("DELETE FROM hr_onboarding WHERE OFFER_NO LIKE 'ORG-%'");
        jdbc.update("DELETE FROM hr_employee WHERE EMP_NO LIKE 'ORG-%' OR EMP_NO LIKE 'ORGOUT%' "
                + "OR EMP_NO LIKE 'ORGMGR%' OR EMP_NO LIKE 'ORGE%' OR ID LIKE 'org-emp-%'");
        jdbc.update("DELETE FROM hr_position WHERE NAME LIKE '组织测试%' OR NAME LIKE '合并目标%' OR NAME LIKE '链路%'");
        jdbc.update("DELETE FROM hr_dept WHERE NAME LIKE '组织测试%' OR NAME LIKE '合并目标%'");
    }

    private void loginRole(String role) {
        loginRole(role, "junit-" + role.replace("ROLE_", "").toLowerCase());
    }

    private void loginRole(String role, String user) {
        var auth = new UsernamePasswordAuthenticationToken(user, null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void loginAdmin() {
        loginRole("ROLE_ADMIN", "junit-admin");
    }

    private void passApproval(String bizType, String bizId) {
        loginAdmin();
        for (int i = 0; i < 6; i++) {
            var tasks = jdbc.queryForList(
                    "SELECT t.ID FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                            + "WHERE i.BIZ_TYPE=? AND i.BIZ_ID=? AND t.STATUS='ACTIVE'",
                    bizType, bizId);
            if (tasks.isEmpty()) {
                return;
            }
            approvalEngine.pass(String.valueOf(tasks.get(0).get("ID")), "同意（junit）");
        }
    }

    // ================================================================
    // 2.4 职级/部门/岗位场景
    // ================================================================

    @Test
    void gradeBandValidation() {
        // 带宽下限 >= 上限 422
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("name", "坏职级");
        bad.put("series", "M");
        bad.put("salMin", "20000");
        bad.put("salMax", "10000");
        ServiceException e = assertThrows(ServiceException.class, () -> gradeService.create(bad));
        assertTrue(e.getMessage().contains("下限"), e.getMessage());
        // 非法序列 422
        bad.put("salMin", "10000");
        bad.put("salMax", "20000");
        bad.put("series", "X");
        e = assertThrows(ServiceException.class, () -> gradeService.create(bad));
        assertTrue(e.getMessage().contains("序列"), e.getMessage());
        // 正常创建
        HrGrade g = gradeService.create(Map.of("name", "中级工程师", "series", "M",
                "salMin", "15000", "salMax", "25000"));
        assertTrue(g.getGradeNo().startsWith("G"), g.getGradeNo());
    }

    @Test
    void gradeBandChangeGoesThroughApproval() {
        HrGrade g = gradeService.create(Map.of("name", "带宽变更职级", "series", "M",
                "salMin", "10000", "salMax", "20000"));
        // 带宽变更 → 调整单审批（GRADE_BAND），职级带宽不变直到审批通过
        Map<String, Object> created = orgChangeService.createBandChange(g.getId(),
                Map.of("salMin", "12000", "salMax", "26000"));
        assertNotNull(created.get("chgNo"));
        String chgId = (String) created.get("id");
        // 审批前：带宽未变
        assertEquals(0, new BigDecimal("10000")
                .compareTo(gradeDao.selectById(g.getId()).getSalMin()), "审批前带宽不变");
        passApproval(HrOrgChangeServiceImpl.BIZ_TYPE, chgId);
        // 审批后：带宽应用 + 调整单 EFFECTED 留痕
        HrGrade after = gradeDao.selectById(g.getId());
        assertEquals(0, new BigDecimal("12000").compareTo(after.getSalMin()), "审批后带宽应用");
        assertEquals(HrOrgChange.ST_EFFECTED, changeDao.selectById(chgId).getStatus());
        String payload = changeDao.selectById(chgId).getPayloadJson();
        assertTrue(payload.contains("10000") && payload.contains("12000"), "前后快照留痕");
    }

    @Test
    void deptManagerMustBeInDeptActive() {
        HrDept dept = orgService.createDept(Map.of("name", "组织测试部门"));
        String otherEmpId = "org-emp-outside";
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, 'ORGOUT-1', '外部门员工', 'ORGID-OUT', ?, "
                + "'REGULAR', 'junit', '0', 0)", otherEmpId, "none");
        // 非本部门在职 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> orgChangeService.createManagerChange(dept.getId(), otherEmpId));
        assertTrue(e.getMessage().contains("本部门"), e.getMessage());
        // 本部门在职 → 提交调整单
        String mgrId = "org-emp-mgr";
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, 'ORGMGR-1', '本部门主管', 'ORGID-MGR', ?, "
                + "'REGULAR', 'junit', '0', 0)", mgrId, dept.getId());
        Map<String, Object> created = orgChangeService.createManagerChange(dept.getId(), mgrId);
        passApproval(HrOrgChangeServiceImpl.BIZ_TYPE, String.valueOf(created.get("id")));
        assertEquals(mgrId, deptDao.selectById(dept.getId()).getManagerEmpId(), "审批后主管应用");
    }

    @Test
    void deptDeactivateDoubleBlocked() {
        HrDept dept = orgService.createDept(Map.of("name", "组织测试停用部门"));
        // ① 在职员工阻断
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('org-emp-active', 'ORGACT-1', '在职张三', "
                + "'ORGID-ACT', ?, 'REGULAR', 'junit', '0', 0)", dept.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> orgService.deactivateDept(dept.getId()));
        assertTrue(e.getMessage().contains("在职员工"), e.getMessage());
        jdbc.update("DELETE FROM hr_employee WHERE ID = 'org-emp-active'");
        // ② 启用子部门阻断
        HrDept child = orgService.createDept(Map.of("name", "组织测试子部门", "parentId", dept.getId()));
        e = assertThrows(ServiceException.class, () -> orgService.deactivateDept(dept.getId()));
        assertTrue(e.getMessage().contains("子部门"), e.getMessage());
        // ③ 清空子部门后可停用
        deptDao.deleteById(child.getId());
        HrDept off = orgService.deactivateDept(dept.getId());
        assertEquals("0", off.getStatus());
    }

    @Test
    void positionGradeBandChain() {
        HrGrade g = gradeService.create(Map.of("name", "链路职级", "series", "M",
                "salMin", "10000", "salMax", "20000"));
        HrDept dept = orgService.createDept(Map.of("name", "组织测试链路部门"));
        HrPosition p = orgService.createPosition(Map.of("name", "组织测试链路岗",
                "deptId", dept.getId()));
        // 未关联职级 → 跳过标注
        Map<String, Object> unresolved = gradeService.resolveBand(p.getId());
        assertEquals(Boolean.FALSE, unresolved.get("resolved"), "未关联职级不解析");
        // 关联职级后可解析
        orgService.updatePositionMeta(p.getId(), g.getId(), "{\"duties\":\"开发\"}");
        Map<String, Object> resolved = gradeService.resolveBand(p.getId());
        assertEquals(Boolean.TRUE, resolved.get("resolved"));
        assertEquals(0, new BigDecimal("20000").compareTo((BigDecimal) resolved.get("salMax")));
        // 停用职级不可关联（用未被引用的另一职级验证停用拦截，被引用职级的停用本身也被 422 拦截）
        ServiceException e = assertThrows(ServiceException.class,
                () -> gradeService.toggleStatus(g.getId(), "0"));
        assertTrue(e.getMessage().contains("引用"), e.getMessage());
        HrGrade g2 = gradeService.create(Map.of("name", "停用测试职级", "series", "P",
                "salMin", "5000", "salMax", "8000"));
        gradeService.toggleStatus(g2.getId(), "0");
        e = assertThrows(ServiceException.class,
                () -> orgService.updatePositionMeta(p.getId(), g2.getId(), null));
        assertTrue(e.getMessage().contains("停用"), e.getMessage());
    }

    // ================================================================
    // 3.2 汇报关系场景
    // ================================================================

    @Test
    void reportSelfAndCycleRejected() {
        String a = seedEmp("a", "汇报A");
        String b = seedEmp("b", "汇报B");
        // 自汇报 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportService.setReportTo(a, a, "test"));
        assertTrue(e.getMessage().contains("本人"), e.getMessage());
        // A→B 设置成功
        reportService.setReportTo(a, b, "初始汇报");
        // 两节点环：B→A 422
        e = assertThrows(ServiceException.class,
                () -> reportService.setReportTo(b, a, "成环测试"));
        assertTrue(e.getMessage().contains("成环"), e.getMessage());
    }

    @Test
    void reportDepthExceededRejected() {
        // 链 0→1→…→11（深度上限 10），将 11 的下属挂到链首外新节点会超限——
        // 构造：seed 12 个员工串成链，深度 10 上限下第 11 跳被拒
        String[] ids = new String[12];
        for (int i = 0; i < 12; i++) {
            ids[i] = seedEmp("d" + i, "深链" + i);
        }
        // ids[0] 无汇报人；ids[1]→ids[0]；…ids[10]→ids[9]（深度 10，OK）
        for (int i = 1; i <= 10; i++) {
            reportService.setReportTo(ids[i], ids[i - 1], "链");
        }
        // ids[11]→ids[10]：上溯 11 跳 > 上限 10 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportService.setReportTo(ids[11], ids[10], "超深"));
        assertTrue(e.getMessage().contains("深度"), e.getMessage());
    }

    @Test
    void reportChangeLoggedAndTreeQuery() {
        String a = seedEmp("log-a", "留痕甲");
        String b = seedEmp("log-b", "留痕乙");
        Map<String, Object> out = reportService.setReportTo(a, b, "汇报变更原因");
        assertNotNull(out.get("logId"));
        HrChangeLog cl = logDao.selectById(String.valueOf(out.get("logId")));
        assertNotNull(cl);
        assertEquals("直接汇报人", cl.getFieldName());
        assertTrue(cl.getReason().contains("汇报变更原因"), cl.getReason());
        // 祖先链与下属
        Map<String, Object> anc = reportService.ancestors(a);
        assertEquals(1, ((List<?>) anc.get("ancestors")).size());
        List<Map<String, Object>> subs = reportService.directReports(b);
        assertEquals(1, subs.size());
        assertEquals(a, subs.get(0).get("empId"));
    }

    // ================================================================
    // 4.3 合并级联场景
    // ================================================================

    @Test
    void mergeCascadeMigratesAll() {
        // 源/目标部门 + 源主管 + 员工 + 岗位 + 子部门
        HrDept source = orgService.createDept(Map.of("name", "组织测试源部门"));
        HrDept target = orgService.createDept(Map.of("name", "合并目标部门"));
        String srcMgr = seedEmp("sm", "源主管");
        String tgtMgr = seedEmp("tm", "目标主管");
        jdbc.update("UPDATE hr_dept SET MANAGER_EMP_ID=? WHERE ID=?", srcMgr, source.getId());
        jdbc.update("UPDATE hr_dept SET MANAGER_EMP_ID=? WHERE ID=?", tgtMgr, target.getId());
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, "
                + "REPORT_TO_EMP_ID, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('org-emp-e1','ORGE1','被迁员工','ORGID-E1',?, 'REGULAR', ?, 'junit','0',0)",
                source.getId(), srcMgr);
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, DEPT_ID, EMP_STATUS, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES ('org-emp-e2','ORGE2','源主管本人',"
                + "'ORGID-E2',?, 'REGULAR', 'junit','0',0)", source.getId());
        HrPosition p = orgService.createPosition(Map.of("name", "合并目标岗", "deptId", source.getId()));
        HrDept child = orgService.createDept(Map.of("name", "组织测试源子部门", "parentId", source.getId()));

        Map<String, Object> created = orgChangeService.createMerge(
                Map.of("sourceId", source.getId(), "targetId", target.getId()));
        passApproval(HrOrgChangeServiceImpl.BIZ_TYPE, String.valueOf(created.get("id")));

        // 三迁移 + 子部门 + 源停用
        assertEquals(target.getId(), empDao.selectById("org-emp-e1").getDeptId(), "员工换绑");
        assertEquals(target.getId(), empDao.selectById("org-emp-e2").getDeptId(), "主管换绑");
        assertEquals(tgtMgr, empDao.selectById("org-emp-e1").getReportToEmpId(), "汇报上移至目标主管");
        assertEquals(target.getId(), posDao.selectById(p.getId()).getDeptId(), "岗位换绑");
        assertEquals(target.getId(), deptDao.selectById(child.getId()).getParentId(), "子部门上移");
        assertEquals("0", deptDao.selectById(source.getId()).getStatus(), "源停用");
        // 快照留痕
        String payload = changeDao.selectById(String.valueOf(created.get("id"))).getPayloadJson();
        assertTrue(payload.contains("empMoved"), "结果计数留痕");
        assertTrue(payload.contains("sourceId"), "前后快照留痕");
    }

    @Test
    void mergeIdempotentAndDuplicateBlocked() {
        HrDept source = orgService.createDept(Map.of("name", "组织测试幂等源"));
        HrDept target = orgService.createDept(Map.of("name", "合并目标部门二"));
        Map<String, Object> created = orgChangeService.createMerge(
                Map.of("sourceId", source.getId(), "targetId", target.getId()));
        String chgId = (String) created.get("id");
        // 审批通过执行一次
        passApproval(HrOrgChangeServiceImpl.BIZ_TYPE, chgId);
        assertEquals(HrOrgChange.ST_EFFECTED, changeDao.selectById(chgId).getStatus());
        // 幂等重入：再执行不报错不改状态
        orgChangeService.execute(chgId);
        assertEquals(HrOrgChange.ST_EFFECTED, changeDao.selectById(chgId).getStatus());
        // 源已停用后再建新合并单 → 被防重复/停用拦截（先清防重复再验停用承接）
        jdbc.update("UPDATE hr_dept SET STATUS='1' WHERE ID=?", source.getId()); // 模拟复开
        // 造一个进行中的重复单 → 防重复 422
        Map<String, Object> dup = orgChangeService.createMerge(
                Map.of("sourceId", source.getId(), "targetId", target.getId()));
        ServiceException e = assertThrows(ServiceException.class, () -> orgChangeService.createMerge(
                Map.of("sourceId", source.getId(), "targetId", target.getId())));
        assertTrue(e.getMessage().contains("防重复"), e.getMessage());
        // 清掉进行中单后：源停用态下重跑合并 → 幂等跳过
        loginAdmin();
        jdbc.update("UPDATE erp_sys_approval SET STATUS='REJECTED' WHERE BIZ_ID=?", dup.get("id"));
        jdbc.update("UPDATE hr_org_change SET STATUS='REJECTED' WHERE ID=?", dup.get("id"));
        jdbc.update("UPDATE hr_dept SET STATUS='0' WHERE ID=?", source.getId());
    }

    @Test
    void mergeIllegalDirectionBlocked() {
        HrDept parent = orgService.createDept(Map.of("name", "组织测试上级"));
        HrDept child = orgService.createDept(Map.of("name", "组织测试下级", "parentId", parent.getId()));
        // 源=父、目标=子 → 非法方向 L1
        ServiceException e = assertThrows(ServiceException.class, () -> orgChangeService.createMerge(
                Map.of("sourceId", parent.getId(), "targetId", child.getId())));
        assertTrue(e.getMessage().contains("非法合并方向"), e.getMessage());
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 造在职员工（直接 INSERT 避免建档六步噪音） */
    private String seedEmp(String suffix, String name) {
        String id = "org-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, CREATE_BY, "
                + "DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'REGULAR', 'junit', '0', 0)",
                id, "ORG-" + suffix.toUpperCase(), name, "ORGID-" + suffix.toUpperCase());
        return id;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
