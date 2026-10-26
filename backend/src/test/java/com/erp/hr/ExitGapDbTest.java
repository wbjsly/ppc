package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrExitDao;
import com.erp.entity.hr.HrExit;
import com.erp.entity.hr.HrSocialChange;
import com.erp.service.hr.HrExitService;
import com.erp.service.hr.HrSocialChangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离职缺口补齐 DbTest（add-transfer-followup：自助来源/通知期反馈/经济补偿金/挽留/减员豁免）。
 * 造数前缀 teg-。
 */
@SpringBootTest
class ExitGapDbTest {

    @Autowired
    private HrExitService exitService;
    @Autowired
    private com.erp.service.impl.hr.HrExitServiceImpl exitServiceImpl;
    @Autowired
    private HrSocialChangeService changeService;
    @Autowired
    private HrExitDao exitDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "junit-hr", null,
                org.springframework.security.core.authority.AuthorityUtils.createAuthorityList("ROLE_HR"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 共享库防残留（冒烟 generate 全库拾取）
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i ON t.APPR_ID=i.ID "
                + "WHERE i.BIZ_TYPE='HrExit' AND i.BIZ_ID LIKE 'teg-%'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='HrExit' AND BIZ_ID LIKE 'teg-%'");
        jdbc.update("DELETE FROM hr_social_change WHERE EMP_ID LIKE 'teg-%'");
        jdbc.update("DELETE FROM hr_exit WHERE EMP_ID LIKE 'teg-%' OR ID LIKE 'teg-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'teg-%' OR EMP_NO LIKE 'TEG-%'");
    }

    /** 造正式员工：入职 2 年 8 个月（经济补偿金 N=3 口径）、月薪 10000 */
    private String seedRegular(String suffix, LocalDate hire, BigDecimal salary) {
        String id = "teg-emp-" + suffix;
        LocalDate quit = hire.plusMonths(28);
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "BASE_SALARY, SOCIAL_PLACE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', ?, ?, 'DEMO市', 'junit', '0', 0)",
                id, "TEG-" + suffix.toUpperCase(), "离职缺口员工" + suffix, "TEGID-" + suffix, hire, salary);
        return id;
    }

    private HrExit applyAndToHandover(String empId, String source) {
        return applyAndToHandover(empId, source, LocalDate.now().plusDays(40));
    }

    private HrExit applyAndToHandover(String empId, String source, LocalDate quitDate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("quitDate", quitDate.toString());
        p.put("reason", "个人发展");
        p.put("source", source);
        HrExit x = exitService.apply(p);
        exitService.startApproval(x.getId());
        // 直接回调进入交接（审批链细节由既有 EmployeeTransferDbTest 覆盖）
        exitServiceImpl.onApproved(x.getId());
        return exitDao.selectById(x.getId());
    }

    // ---------- 自助来源 + 通知期 ----------

    @Test
    void selfSourceAndNoticeFeedback() {
        // 入职 10 天的正式员工提离职（通知期 30 天未满 → noticeOk=0 L4）
        String empId = seedRegular("n1", LocalDate.now().minusDays(10), new BigDecimal("10000"));
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("quitDate", LocalDate.now().plusDays(5).toString());
        p.put("reason", "自助提交");
        p.put("source", "SELF");
        HrExit x = exitService.apply(p);
        assertEquals("SELF", x.getSource(), "自助端来源标记");
        assertEquals("0", x.getNoticeOk(), "未满通知期 L4（不阻断）");
        // HR 代录缺省 HR
        String empId2 = seedRegular("n2", LocalDate.now().minusMonths(24), new BigDecimal("10000"));
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("empId", empId2);
        p2.put("quitDate", LocalDate.now().plusDays(40).toString());
        p2.put("reason", "HR 代录");
        assertEquals("HR", exitService.apply(p2).getSource(), "代录缺省 HR");
        // listByEmp 只返回本人单
        assertEquals(1, exitService.listByEmp(empId).size());
    }

    // ---------- 经济补偿金试算 ----------

    @Test
    void ecompAutoTrialAndOverride() {
        // 入职 2024-01-01、离职 2026-09-01（32 个月 → 满 2 年余 8 月 ≥6 → N=3）月薪 10000 → 30000
        String empId = seedRegular("e1", LocalDate.of(2024, 1, 1), new BigDecimal("10000"));
        HrExit x = applyAndToHandover(empId, "HR", LocalDate.of(2026, 9, 1));
        Map<String, Object> settle = new LinkedHashMap<>();
        settle.put("settleWage", "8000");
        HrExit settled = exitService.settle(x.getId(), settle);
        assertEquals(0, new BigDecimal("30000.00").compareTo(settled.getSettleEcomp()),
                "N 口径自动试算（3 × 10000）：" + settled.getSettleEcomp());
        assertTrue(settled.getSettleNote() != null && settled.getSettleNote().contains("经济补偿金自动试算"),
                "试算留痕：" + settled.getSettleNote());
        // HR 覆盖：金额改为 0（如主动离职无补偿）
        String empId2 = seedRegular("e2", LocalDate.of(2024, 1, 1), new BigDecimal("10000"));
        HrExit x2 = applyAndToHandover(empId2, "HR", LocalDate.of(2026, 9, 1));
        Map<String, Object> settle2 = new LinkedHashMap<>();
        settle2.put("settleEcomp", "0");
        assertEquals(0, new BigDecimal("0").compareTo(exitService.settle(x2.getId(), settle2).getSettleEcomp()),
                "HR 覆盖生效");
    }

    // ---------- 挽留意见 ----------

    @Test
    void retainNoteStampsAndKeepsFlow() {
        String empId = seedEmpQuick("r1");
        HrExit x = applyAndToHandover(empId, "HR");
        HrExit withNote = exitService.retainNote(x.getId(), "已同意调薪挽留");
        assertTrue(withNote.getRetainNote().contains("已同意调薪挽留"));
        assertEquals(HrExit.ST_HANDOVER, withNote.getStatus(), "挽留不阻断流转");
        // 二次挽留追加而非覆盖
        exitService.retainNote(x.getId(), "望重新考虑");
        assertTrue(exitDao.selectById(x.getId()).getRetainNote().contains("望重新考虑"));
        assertThrows(ServiceException.class, () -> exitService.retainNote(x.getId(), " "), "空意见拒绝");
    }

    // ---------- 减员单缺失阻断 + 豁免 ----------

    @Test
    void exitCertBlockedWithoutDoneRemoveOrWaiver() {
        String empId = seedEmpQuick("c1");
        HrExit x = applyAndToHandover(empId, "HR");
        exitService.settle(x.getId(), Map.of("settleWage", "9000"));
        // 四类交接完成（空台账直接确认）
        exitService.confirmAssetReturn(x.getId(), java.util.List.of());
        exitService.confirmAccountHandover(x.getId());
        exitService.registerHandoverItems(x.getId(), "doc",
                java.util.List.of(Map.of("item", "设计文档", "receiver", "teg-rec")));
        exitService.registerHandoverItems(x.getId(), "work",
                java.util.List.of(Map.of("item", "在办需求", "receiver", "teg-rec")));
        // 无 DONE 减员单且未豁免 → L1
        ServiceException e = assertThrows(ServiceException.class, () -> exitService.issueExitCert(x.getId()));
        assertTrue(e.getMessage().contains("社保减员未完成"), e.getMessage());
        // 手工豁免 → 放行
        HrExit waived = exitService.waiveReduce(x.getId(), "已线下办理减员");
        assertEquals("1", waived.getReduceWaived());
        assertTrue(waived.getReduceWaiveNote().contains("已线下办理减员"));
        HrExit cert = exitService.issueExitCert(x.getId());
        assertEquals(HrExit.ST_EXITED, cert.getStatus());
        // 豁免原因必填
        String empId2 = seedEmpQuick("c2");
        HrExit x2 = applyAndToHandover(empId2, "HR");
        assertThrows(ServiceException.class, () -> exitService.waiveReduce(x2.getId(), " "), "空原因拒绝");
    }

    @Test
    void doneRemoveUnblocksWithoutWaiver() {
        String empId = seedEmpQuick("c3");
        HrExit x = applyAndToHandover(empId, "HR");
        exitService.settle(x.getId(), Map.of("settleWage", "9000"));
        exitService.confirmAssetReturn(x.getId(), java.util.List.of());
        exitService.confirmAccountHandover(x.getId());
        exitService.registerHandoverItems(x.getId(), "doc",
                java.util.List.of(Map.of("item", "文档", "receiver", "teg-rec")));
        exitService.registerHandoverItems(x.getId(), "work",
                java.util.List.of(Map.of("item", "工作", "receiver", "teg-rec")));
        // 结算已自动开减员单（DRAFT）→ 申报登记 DONE → 凭证放行
        HrSocialChange rm = changeService.list(empId, HrSocialChange.TYPE_REMOVE, HrSocialChange.ST_DRAFT).get(0);
        changeService.declare(rm.getId(), Map.of("receiptNo", "TEG-RM-1"));
        assertEquals(HrExit.ST_EXITED, exitService.issueExitCert(x.getId()).getStatus(), "DONE 减员单放行");
    }

    private String seedEmpQuick(String suffix) {
        String id = "teg-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "BASE_SALARY, SOCIAL_PLACE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', ?, 10000, 'DEMO市', 'junit', '0', 0)",
                id, "TEG-" + suffix.toUpperCase(), "离职缺口员工" + suffix, "TEGID-" + suffix,
                LocalDate.now().minusMonths(24));
        return id;
    }
}
