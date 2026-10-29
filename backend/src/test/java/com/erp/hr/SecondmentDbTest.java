package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrSecondmentDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrSecondment;
import com.erp.service.hr.HrSecondmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外派/借调 DbTest（secondment-management：发起/确认锁定/合规/复核/生效分叉/延期/返岗/提醒）。
 * 造数前缀 tsec-。
 */
@SpringBootTest
class SecondmentDbTest {

    @Autowired
    private HrSecondmentService secService;
    @Autowired
    private HrEmployeeDao empDao;
    @Autowired
    private HrSecondmentDao secDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
        // 两个测试法人主体（注册地不同，供 C-4.7-12 校验）
        jdbc.update("INSERT IGNORE INTO erp_mdm_legal_entity (ID, LE_CODE, LE_NAME, USCC, STATUS, "
                + "BOOKKEEPING_CURRENCY, REG_PLACE, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('tsec-le-a','TSEC-A','外派借调A公司','USCC-A','1','CNY','上海','junit','0',0), "
                + "('tsec-le-b','TSEC-B','外派借调B公司','USCC-B','1','CNY','深圳','junit','0',0)");
    }

    @AfterEach
    void tearDown() {
        // 共享库防残留
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_ID LIKE 'tsec-%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_ID LIKE 'tsec-%'");
        jdbc.update("DELETE FROM hr_social_change WHERE EMP_ID LIKE 'tsec-%'");
        jdbc.update("DELETE FROM hr_remind WHERE EMP_ID LIKE 'tsec-%'");
        jdbc.update("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID LIKE 'tsec-%'; SET @audit_bypass=0");
        jdbc.update("DELETE FROM hr_secondment WHERE EMP_ID LIKE 'tsec-%' OR ID LIKE 'tsec-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tsec-%' OR EMP_NO LIKE 'TSEC-%'");
        jdbc.update("DELETE FROM erp_mdm_legal_entity WHERE ID LIKE 'tsec-le-%'");
    }

    private void loginRole(String role) {
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "junit-" + role.toLowerCase(), null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList(role));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private String seedEmp(String suffix) {
        String id = "tsec-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "BASE_SALARY, SOCIAL_PLACE, LEGAL_ENTITY, COST_CENTER, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', ?, 20000, 'DEMO市', 'TSEC-A', 'CC-A', 'junit', '0', 0)",
                id, "TSEC-" + suffix.toUpperCase(), "外派借调员工" + suffix, "TSECID-" + suffix,
                LocalDate.now().minusMonths(24));
        return id;
    }

    /** 发起借调并快进到指定状态 */
    private HrSecondment seedTo(String suffix, String status) {
        String empId = seedEmp(suffix);
        Map<String, Object> p = basePayload(empId, "TSEC-B",
                LocalDate.now().plusDays(1), LocalDate.now().plusMonths(6));
        p.put("costSharePct", 100);
        p.put("agreementNo", "SA-" + suffix);
        p.put("agreementDate", LocalDate.now().toString());
        HrSecondment s = (HrSecondment) secService.submit(p).get("secondment");
        loginRole("ROLE_HR");
        if (HrSecondment.ST_DRAFT.equals(status)) {
            return s;
        }
        secService.confirmOut(s.getId(), "同意调出", true);
        if (HrSecondment.ST_CONFIRMED_OUT.equals(status)) {
            return secDao.selectById(s.getId());
        }
        secService.confirmIn(s.getId(), "同意调入", true);
        if (HrSecondment.ST_CONFIRMED_IN.equals(status)) {
            return secDao.selectById(s.getId());
        }
        secService.hrReview(s.getId(), "合规通过");
        if (HrSecondment.ST_REVIEWING.equals(status)) {
            return secDao.selectById(s.getId());
        }
        // 双人复核两签一致 → APPROVING
        loginRole("ROLE_HR_MGR");
        secService.signReview(s.getId(), reviewElems(s));
        loginRole("ROLE_HR");
        secService.signReview(s.getId(), reviewElems(s));
        if (HrSecondment.ST_APPROVING.equals(status)) {
            return secDao.selectById(s.getId());
        }
        loginRole("ROLE_HR_MGR");
        secService.approve(s.getId(), true, "同意");
        if (HrSecondment.ST_APPROVED.equals(status)) {
            return secDao.selectById(s.getId());
        }
        // EFFECTED：提前生效日以便 effect 直接执行
        jdbc.update("UPDATE hr_secondment SET START_DATE=DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE ID=?", s.getId());
        loginRole("ROLE_HR");
        secService.effect(s.getId());
        return secDao.selectById(s.getId());
    }

    private Map<String, Object> basePayload(String empId, String targetLe, LocalDate start, LocalDate end) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("chgType", "SECOND");
        p.put("targetLe", targetLe);
        p.put("targetPlace", "DEMO市");
        p.put("costCenter", "CC-B");
        p.put("startDate", start.toString());
        p.put("endDate", end.toString());
        return p;
    }

    private Map<String, Object> reviewElems(HrSecondment s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("targetLe", s.getTargetLe());
        m.put("costSharePct", s.getCostSharePct() == null ? "null" : s.getCostSharePct().toPlainString());
        m.put("salaryPlace", s.getTargetPlace());
        m.put("costCenter", s.getCostCenter());
        m.put("startDate", s.getStartDate().toString());
        return m;
    }

    // ---------- 发起 ----------

    @Test
    void submitSecondCollectsAndRequiresAgreement() {
        String empId = seedEmp("s1");
        // 缺协议 → 422
        Map<String, Object> bad = basePayload(empId, "TSEC-B", LocalDate.now().plusDays(1),
                LocalDate.now().plusMonths(6));
        bad.put("costSharePct", 100);
        assertThrows(ServiceException.class, () -> secService.submit(bad));
        // 正常发起
        Map<String, Object> ok = basePayload(empId, "TSEC-B", LocalDate.now().plusDays(1),
                LocalDate.now().plusMonths(6));
        ok.put("costSharePct", 80);
        ok.put("agreementNo", "SA-001");
        ok.put("agreementDate", LocalDate.now().toString());
        Map<String, Object> out = secService.submit(ok);
        HrSecondment s = (HrSecondment) out.get("secondment");
        assertEquals("SECOND", s.getChgType());
        assertEquals(new BigDecimal("80"), s.getCostSharePct());
        assertEquals("TSEC-A", s.getSrcLe(), "调出主体取主档法人");
        // 在途防重
        assertThrows(ServiceException.class, () -> secService.submit(ok));
    }

    @Test
    void submitLongTermWarnsL4() {
        String empId = seedEmp("s2");
        Map<String, Object> p = basePayload(empId, "TSEC-B", LocalDate.now().plusDays(1),
                LocalDate.now().plusMonths(15));
        p.put("costSharePct", 100);
        p.put("agreementNo", "SA-LONG");
        p.put("agreementDate", LocalDate.now().toString());
        Map<String, Object> out = secService.submit(p);
        @SuppressWarnings("unchecked")
        var warnings = (List<String>) out.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("超过 12 个月") && w.contains("L4")),
                "超 12 月 L4 警示：" + warnings);
    }

    // ---------- 确认与锁定 ----------

    @Test
    void confirmLocksShareAndRejectPath() {
        HrSecondment s = seedTo("c1", HrSecondment.ST_DRAFT);
        secService.confirmOut(s.getId(), "同意", true);
        assertEquals(HrSecondment.ST_CONFIRMED_OUT, secDao.selectById(s.getId()).getStatus());
        // 比例未锁定前可改
        jdbc.update("UPDATE hr_secondment SET COST_SHARE_PCT=50 WHERE ID=?", s.getId());
        HrSecondment afterIn = secService.confirmIn(s.getId(), "同意", true);
        assertEquals("1", afterIn.getShareLocked(), "双方确认完成即锁定");
        assertEquals(0, new BigDecimal("50").compareTo(afterIn.getCostSharePct()));
        // 调入否决路径
        HrSecondment s2 = seedTo("c2", HrSecondment.ST_CONFIRMED_OUT);
        HrSecondment rejected = secService.confirmIn(s2.getId(), "编制已满", false);
        assertEquals(HrSecondment.ST_REJECTED, rejected.getStatus());
    }

    // ---------- HR 合规 ----------

    @Test
    void hrReviewBlocksUnconfiguredPlaceAndWarnsMismatch() {
        HrSecondment s = seedTo("h1", HrSecondment.ST_CONFIRMED_IN);
        // 变更缴纳地到未配置城市 → L1
        jdbc.update("UPDATE hr_secondment SET PLACE_CHANGE='1', TARGET_PLACE='未配置城' WHERE ID=?", s.getId());
        ServiceException e = assertThrows(ServiceException.class,
                () -> secService.hrReview(s.getId(), "审"));
        assertTrue(e.getMessage().contains("属地政策未配置"), e.getMessage());
        // 缴纳地(DEMO市) 与用工主体注册地(深圳) 不一致 → L4 警示（不阻断）
        jdbc.update("UPDATE hr_secondment SET TARGET_PLACE='DEMO市' WHERE ID=?", s.getId());
        Map<String, Object> out = secService.hrReview(s.getId(), "合规通过");
        assertEquals(HrSecondment.ST_REVIEWING, ((HrSecondment) out.get("secondment")).getStatus());
        @SuppressWarnings("unchecked")
        var warnings = (java.util.List<String>) out.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("不一致")), "C-4.7-12 L4：" + warnings);
    }

    // ---------- 双人复核 ----------

    @Test
    void doubleReviewConsistentAndDiff() {
        HrSecondment s = seedTo("r1", HrSecondment.ST_REVIEWING);
        loginRole("ROLE_HR_MGR");
        secService.signReview(s.getId(), reviewElems(s));
        assertEquals(HrSecondment.ST_REVIEWING, secDao.selectById(s.getId()).getStatus());
        // 同人不可双签
        assertThrows(ServiceException.class, () -> secService.signReview(s.getId(), reviewElems(s)));
        // 第二人一致 → APPROVING
        loginRole("ROLE_HR");
        secService.signReview(s.getId(), reviewElems(s));
        assertEquals(HrSecondment.ST_APPROVING, secDao.selectById(s.getId()).getStatus());
        // 不一致路径
        HrSecondment s2 = seedTo("r2", HrSecondment.ST_REVIEWING);
        loginRole("ROLE_HR_MGR");
        secService.signReview(s2.getId(), reviewElems(s2));
        loginRole("ROLE_HR");
        Map<String, Object> diffElems = reviewElems(s2);
        diffElems.put("costSharePct", "30");
        HrSecondment diff = secService.signReview(s2.getId(), diffElems);
        assertEquals(HrSecondment.ST_REVIEW_DIFF, diff.getStatus());
        // 裁决通过 → APPROVING
        loginRole("ROLE_HR_MGR");
        assertEquals(HrSecondment.ST_APPROVING,
                secService.adjudicateDiff(s2.getId(), "APPROVE", "核实后按 100% 执行").getStatus());
    }

    // ---------- 生效分叉 ----------

    @Test
    void effectSecondKeepsMasterUntouched() {
        HrSecondment s = seedTo("e1", HrSecondment.ST_APPROVED);
        // 快进生效日（seedTo 对 APPROVED 提前返回，未做快进）
        jdbc.update("UPDATE hr_secondment SET START_DATE=DATE_SUB(CURDATE(), INTERVAL 1 DAY) WHERE ID=?",
                s.getId());
        HrEmployee before = empDao.selectById(s.getEmpId());
        HrSecondment eff = secService.effect(s.getId());
        assertEquals(HrSecondment.ST_EFFECTED, eff.getStatus());
        HrEmployee after = empDao.selectById(s.getEmpId());
        assertEquals(before.getLegalEntity(), after.getLegalEntity(), "借调主档法人不动");
        assertEquals(before.getEmpStatus(), after.getEmpStatus(), "EMP_STATUS 不动");
        assertEquals(before.getCostCenter(), after.getCostCenter(), "成本中心不动");
        // 幂等
        assertEquals(HrSecondment.ST_EFFECTED, secService.effect(s.getId()).getStatus());
    }

    @Test
    void effectDispatchWritesBackMasterAndOpensSocialDocs() {
        String empId = seedEmp("e2");
        Map<String, Object> p = basePayload(empId, "TSEC-B",
                LocalDate.now().minusDays(1), LocalDate.now().plusMonths(6));
        p.put("chgType", "DISPATCH");
        p.put("placeChange", "1");
        p.put("targetPlace", "DEMO市");
        HrSecondment s = (HrSecondment) secService.submit(p).get("secondment");
        loginRole("ROLE_HR");
        secService.confirmOut(s.getId(), "同意", true);
        secService.confirmIn(s.getId(), "同意", true);
        secService.hrReview(s.getId(), "通过");
        loginRole("ROLE_HR_MGR");
        secService.signReview(s.getId(), reviewElems(s));
        loginRole("ROLE_HR");
        secService.signReview(s.getId(), reviewElems(s));
        loginRole("ROLE_HR_MGR");
        secService.approve(s.getId(), true, "同意");
        loginRole("ROLE_HR");
        secService.effect(s.getId());
        HrEmployee e = empDao.selectById(empId);
        assertEquals("TSEC-B", e.getLegalEntity(), "外派写回法人主体");
        Long chgLog = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE EMP_ID=? AND FIELD_NAME='所属法人主体'",
                Long.class, empId);
        assertEquals(1, chgLog, "change_log 留痕");
        // 社保双单（原主体 REMOVE + 新主体 ADD）
        Long removes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_social_change WHERE EMP_ID=? AND CHG_TYPE='REMOVE' AND SRC_ID=?",
                Long.class, empId, s.getSecNo());
        Long adds = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_social_change WHERE EMP_ID=? AND CHG_TYPE='ADD' AND SRC_ID=?",
                Long.class, empId, s.getSecNo());
        assertEquals(1, removes);
        assertEquals(1, adds);
    }

    // ---------- 延期 / 返岗 / 提醒 ----------

    @Test
    void extendCapReturnAndRemind() {
        HrSecondment s = seedTo("x1", HrSecondment.ST_EFFECTED);
        // 累计 24 月内可延：原 6 月 + 延 12 = 18 OK；再延 12 = 30 > 24 L1
        loginRole("ROLE_HR");
        secService.extend(s.getId(), 12, "项目延续");
        loginRole("ROLE_HR_MGR");
        HrSecondment extended = secService.approveExtend(s.getId(), true, "同意延期");
        assertEquals(HrSecondment.ST_EFFECTED, extended.getStatus());
        assertEquals(12, extended.getExtendMonthsTotal());
        loginRole("ROLE_HR");
        ServiceException e = assertThrows(ServiceException.class,
                () -> secService.extend(s.getId(), 12, "再延"));
        assertTrue(e.getMessage().contains("24 个月"), e.getMessage());
        // 返岗
        HrSecondment returned = secService.returnPost(s.getId(), "项目结束返岗");
        assertEquals(HrSecondment.ST_RETURNED, returned.getStatus());
        assertNotNull(returned.getReturnAt());
        // 提醒：到期 6 天的在途单 → SECONDMENT_RETURN
        HrSecondment s2 = seedTo("x2", HrSecondment.ST_EFFECTED);
        jdbc.update("UPDATE hr_secondment SET END_DATE=DATE_ADD(CURDATE(), INTERVAL 6 DAY), "
                + "REMIND_FLAG='0' WHERE ID=?", s2.getId());
        Map<String, Object> scan = secService.remindScan();
        assertTrue(((Number) scan.get("reminded")).intValue() >= 1, "15 天内提醒：" + scan);
        Long remind = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_remind WHERE EMP_ID=? AND REMIND_TYPE='SECONDMENT_RETURN'",
                Long.class, s2.getEmpId());
        assertEquals(1, remind);
        // 防重：再扫不重复
        secService.remindScan();
        Long remind2 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_remind WHERE EMP_ID=? AND REMIND_TYPE='SECONDMENT_RETURN'",
                Long.class, s2.getEmpId());
        assertEquals(1, remind2, "同日防重");
        // 过期告警
        jdbc.update("UPDATE hr_secondment SET END_DATE=DATE_SUB(CURDATE(), INTERVAL 3 DAY), "
                + "STATUS='EFFECTED' WHERE ID=?", s2.getId());
        Map<String, Object> scan2 = secService.remindScan();
        assertTrue(((Number) scan2.get("expiredAlerted")).intValue() >= 1, "过期告警：" + scan2);
        assertEquals(HrSecondment.ST_EXPIRED_ALERT, secDao.selectById(s2.getId()).getStatus());
    }

    /** in-flight flags 视图标记 */
    @Test
    void inFlightFlagsVisible() {
        HrSecondment s = seedTo("f1", HrSecondment.ST_EFFECTED);
        Map<String, String> flags = secService.inFlightFlags();
        assertTrue(flags.get(s.getEmpId()) != null && flags.get(s.getEmpId()).contains("借调中·"),
                "在途标记：" + flags.get(s.getEmpId()));
        // 返岗后标记消失
        loginRole("ROLE_HR");
        secService.returnPost(s.getId(), "返岗");
        assertNull(secService.inFlightFlags().get(s.getEmpId()));
    }
}
