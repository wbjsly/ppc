package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrCompTimeBatchDao;
import com.erp.dao.hr.HrLeaveBalanceBatchDao;
import com.erp.dao.hr.HrOvertimeApplyDao;
import com.erp.dao.hr.HrOvertimeConfirmDao;
import com.erp.dao.hr.HrPunchRecordDao;
import com.erp.entity.hr.HrCompTimeBatch;
import com.erp.entity.hr.HrLeaveBalanceBatch;
import com.erp.entity.hr.HrOvertimeApply;
import com.erp.entity.hr.HrOvertimeConfirm;
import com.erp.service.hr.HrCompTimeService;
import com.erp.service.hr.HrOvertimeConfirmService;
import com.erp.service.hr.HrOvertimeService;
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
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** M10-4 加班调休 DbTest（overtime-comp-time：36h 阻断/MIN 认定/分流固化/到期清零/离职折算）。 */
@SpringBootTest
class OvertimeDbTest {

    @Autowired
    private HrOvertimeService overtimeService;
    @Autowired
    private HrOvertimeConfirmService confirmService;
    @Autowired
    private HrCompTimeService compTimeService;
    @Autowired
    private HrOvertimeApplyDao applyDao;
    @Autowired
    private HrOvertimeConfirmDao confirmDao;
    @Autowired
    private HrCompTimeBatchDao compTimeDao;
    @Autowired
    private HrLeaveBalanceBatchDao balanceDao;
    @Autowired
    private HrPunchRecordDao punchDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        loginRole("ROLE_HR");
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_overtime_apply WHERE OT_NO LIKE 'OT%' AND CREATE_BY LIKE 'junit%'");
        jdbc.update("DELETE FROM hr_overtime_confirm WHERE CONFIRM_NO LIKE 'OTC%' AND CREATE_BY IN "
                + "('junit','scan')");
        jdbc.update("DELETE FROM hr_comp_time_batch WHERE BATCH_NO LIKE 'CT%'");
        jdbc.update("DELETE FROM hr_leave_balance_batch WHERE BATCH_NO LIKE 'LBB%' OR BATCH_NO LIKE 'CT%'");
        jdbc.update("DELETE FROM hr_punch_record WHERE EMP_ID LIKE 'junit-ot-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'junit-ot-%' OR EMP_NO LIKE 'JOT-%'");
        jdbc.update("DELETE FROM hr_remind WHERE REMIND_TYPE IN ('COMP_TIME_EXPIRY','COMP_TIME_CLEARED') "
                + "AND EMP_ID LIKE 'junit-ot-%'");
        jdbc.update("DELETE FROM hr_remind WHERE REMIND_TYPE='OT_APPROVE_OVERDUE' AND CREATE_BY='scan'");
        jdbc.update("DELETE FROM hr_holiday_calendar WHERE ID LIKE 'junit-hd-%'");
    }

    private void loginRole(String role) {
        var auth = new UsernamePasswordAuthenticationToken("junit-" + role.toLowerCase(), null,
                AuthorityUtils.createAuthorityList(role));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    /** 造员工（可选月薪基数）。 */
    private String seedEmp(String suffix, String baseSalary) {
        String id = "junit-ot-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, BASE_SALARY, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'REGULAR', ?, 'junit', '0', 0)",
                id, "JOT-" + suffix.toUpperCase(), "加班员工" + suffix, "JOTID-" + suffix, baseSalary);
        return id;
    }

    private Map<String, Object> payload(String empId, LocalDate date, String hours, boolean emergency) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("empId", empId);
        p.put("otDate", date.toString());
        p.put("startTime", "18:00");
        p.put("endTime", "22:00");
        p.put("hours", hours);
        p.put("reason", "测试加班");
        if (emergency) {
            p.put("isEmergency", "1");
            p.put("emergencyNote", "主管书面说明");
        }
        return p;
    }

    // ================================================================
    // 4.5 组 4 场景
    // ================================================================

    @Test
    void cap36HoursBlocked() {
        String empId = seedEmp("cap", "21750"); // 月薪 21750 → 小时基数 100
        // 先申请 34h（审批通过计入累计）
        HrOvertimeApply a1 = overtimeService.submit(payload(empId, LocalDate.now().plusDays(1), "34", false));
        overtimeService.approve(a1.getId(), Map.of("approve", "true"));
        // 再申请 4h → 34+4=38 > 36 L1 阻断
        ServiceException e = assertThrows(ServiceException.class,
                () -> overtimeService.submit(payload(empId, LocalDate.now().plusDays(2), "4", false)));
        assertTrue(e.getMessage().contains("36"), e.getMessage());
    }

    @Test
    void emergencyWindowAndNote() {
        String empId = seedEmp("emg", null);
        // 补单超 1 工作日窗口 → 422
        Map<String, Object> p = payload(empId, LocalDate.now().minusDays(5), "2", true);
        ServiceException e = assertThrows(ServiceException.class, () -> overtimeService.submit(p));
        assertTrue(e.getMessage().contains("1 个工作日"), e.getMessage());
        // 缺主管书面说明 → 422
        Map<String, Object> p2 = payload(empId, LocalDate.now(), "2", true);
        p2.remove("emergencyNote");
        e = assertThrows(ServiceException.class, () -> overtimeService.submit(p2));
        assertTrue(e.getMessage().contains("书面说明"), e.getMessage());
        // 事前日期非补单 → 提示走补单
        Map<String, Object> p3 = payload(empId, LocalDate.now().minusDays(1), "2", false);
        e = assertThrows(ServiceException.class, () -> overtimeService.submit(p3));
        assertTrue(e.getMessage().contains("补单"), e.getMessage());
    }

    @Test
    void minDeterminationAndExcess() {
        String empId = seedEmp("min", "21750");
        LocalDate d = LocalDate.now().minusDays(1);
        HrOvertimeApply a = overtimeService.submit(payload(empId, d, "4", true));
        overtimeService.approve(a.getId(), Map.of("approve", "true"));
        // 打卡 18:05-21:10（3.08h < 申请 4h）→ MIN = 3.08
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('junit-ot-in-1',?,?,'18:05:00','IN','WEB','0','junit','0',0), "
                + "('junit-ot-out-1',?,?,'21:10:00','OUT','WEB','0','junit','0',0)",
                empId, d, empId, d);
        int created = confirmService.confirmScan();
        assertTrue(created >= 1);
        HrOvertimeConfirm c = confirmDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrOvertimeConfirm>()
                        .eq(HrOvertimeConfirm::getApplyId, a.getId())).get(0);
        assertEquals(0, new BigDecimal("3.08").compareTo(c.getValidHours()), "MIN(申请4, 打卡3.08)=3.08");
        assertEquals("0", c.getExcessFlag());
    }

    @Test
    void excessFlaggedAndResolved() {
        String empId = seedEmp("exc", "21750");
        LocalDate d = LocalDate.now().minusDays(1);
        HrOvertimeApply a = overtimeService.submit(payload(empId, d, "2", true));
        overtimeService.approve(a.getId(), Map.of("approve", "true"));
        // 打卡 18:00-22:00（4h > 申请 2h + 30min 容差）→ EXCESS
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('junit-ot-in-2',?,?,'18:00:00','IN','WEB','0','junit','0',0), "
                + "('junit-ot-out-2',?,?,'22:00:00','OUT','WEB','0','junit','0',0)",
                empId, d, empId, d);
        confirmService.confirmScan();
        HrOvertimeConfirm c = confirmDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrOvertimeConfirm>()
                        .eq(HrOvertimeConfirm::getApplyId, a.getId())).get(0);
        assertEquals("1", c.getExcessFlag(), "超申请标记");
        assertEquals(HrOvertimeConfirm.ST_EXCESS_PENDING, c.getStatus());
        // 追认 → 按打卡时长
        confirmService.resolveExcess(c.getId(), Map.of("approveExcess", "1"));
        HrOvertimeConfirm resolved = confirmDao.selectById(c.getId());
        assertEquals(0, new BigDecimal("4.00").compareTo(resolved.getValidHours()), "追认后按打卡");
        assertEquals("0", resolved.getExcessFlag());
    }

    @Test
    void noApplyNoRecognition() {
        String empId = seedEmp("noapp", null);
        LocalDate d = LocalDate.now().minusDays(1);
        // 只打卡无申请 → 不产生确认单（BR-4.7-16）
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('junit-ot-in-3',?,?,'18:00:00','IN','WEB','0','junit','0',0), "
                + "('junit-ot-out-3',?,?,'22:00:00','OUT','WEB','0','junit','0',0)",
                empId, d, empId, d);
        int created = confirmService.confirmScan();
        assertEquals(0, created, "仅打卡不认定加班");
    }

    @Test
    void splitEnforcementAndWageSolidify() {
        String empId = seedEmp("spl", "21750"); // 小时基数 100
        // 周末加班（下周六）
        LocalDate sat = LocalDate.now().minusDays(1); markRestday(sat);
        HrOvertimeApply a = overtimeService.submit(payload(empId, sat, "4", true));
        overtimeService.approve(a.getId(), Map.of("approve", "true"));
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('junit-ot-in-4',?,?,'18:00:00','IN','WEB','0','junit','0',0), "
                + "('junit-ot-out-4',?,?,'22:00:00','OUT','WEB','0','junit','0',0)",
                empId, sat, empId, sat);
        confirmService.confirmScan();
        HrOvertimeConfirm c = confirmDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrOvertimeConfirm>()
                        .eq(HrOvertimeConfirm::getApplyId, a.getId())).get(0);
        assertEquals(HrOvertimeConfirm.TYPE_WEEKEND, c.getOtType(), "周末 2x");
        assertEquals(0, new BigDecimal("2.0").compareTo(c.getMultiplier()));
        // 法定假强制工资：构造 HOLIDAY 确认单后试调休 → 422
        jdbc.update("UPDATE hr_overtime_confirm SET OT_TYPE='HOLIDAY', MULTIPLIER=3.0 WHERE ID=?", c.getId());
        HrOvertimeConfirm holiday = confirmDao.selectById(c.getId());
        holiday.setStatus(HrOvertimeConfirm.ST_PENDING);
        confirmDao.updateById(holiday);
        ServiceException e = assertThrows(ServiceException.class,
                () -> confirmService.split(c.getId(), Map.of("choice", "COMP_TIME")));
        assertTrue(e.getMessage().contains("不得选择调休"), e.getMessage());
        // 分流 WAGE → 金额固化 = 100 × 3 × 4 = 1200
        jdbc.update("UPDATE hr_overtime_confirm SET OT_TYPE='WEEKEND', MULTIPLIER=2.0 WHERE ID=?", c.getId());
        HrOvertimeConfirm back = confirmDao.selectById(c.getId());
        back.setStatus(HrOvertimeConfirm.ST_PENDING);
        confirmDao.updateById(back);
        HrOvertimeConfirm splitted = confirmService.split(c.getId(), Map.of("choice", "WAGE"));
        assertEquals(0, new BigDecimal("125.0000").compareTo(splitted.getHourlyBase()), "小时基数快照 21750/21.75/8=125");
        assertEquals(0, new BigDecimal("1000.00").compareTo(splitted.getWageAmount()), "2x4h 固化 125*2*4=1000");
    }

    @Test
    void missingBaseSalaryBlocksWage() {
        String empId = seedEmp("nos", null); // 无月薪
        LocalDate sat = LocalDate.now().minusDays(1); markRestday(sat);
        HrOvertimeApply a = overtimeService.submit(payload(empId, sat, "2", true));
        overtimeService.approve(a.getId(), Map.of("approve", "true"));
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('junit-ot-in-5',?,?,'18:00:00','IN','WEB','0','junit','0',0), "
                + "('junit-ot-out-5',?,?,'20:00:00','OUT','WEB','0','junit','0',0)",
                empId, sat, empId, sat);
        confirmService.confirmScan();
        HrOvertimeConfirm c = confirmDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrOvertimeConfirm>()
                        .eq(HrOvertimeConfirm::getApplyId, a.getId())).get(0);
        ServiceException e = assertThrows(ServiceException.class,
                () -> confirmService.split(c.getId(), Map.of("choice", "WAGE")));
        assertTrue(e.getMessage().contains("月薪基数"), e.getMessage());
        // 试算也不落库（返回 null 金额）
        Map<String, Object> trial = confirmService.trial(c.getId());
        assertEquals(null, trial.get("wageEstimate"));
    }

    @Test
    void trialNotPersisted() {
        String empId = seedEmp("trl", "21750");
        LocalDate sat = LocalDate.now().minusDays(1); markRestday(sat);
        HrOvertimeApply a = overtimeService.submit(payload(empId, sat, "4", true));
        overtimeService.approve(a.getId(), Map.of("approve", "true"));
        jdbc.update("INSERT INTO hr_punch_record (ID, EMP_ID, PUNCH_DATE, PUNCH_TIME, DIR, SOURCE, "
                + "SUSPICIOUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('junit-ot-in-6',?,?,'18:00:00','IN','WEB','0','junit','0',0), "
                + "('junit-ot-out-6',?,?,'22:00:00','OUT','WEB','0','junit','0',0)",
                empId, sat, empId, sat);
        confirmService.confirmScan();
        HrOvertimeConfirm c = confirmDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrOvertimeConfirm>()
                        .eq(HrOvertimeConfirm::getApplyId, a.getId())).get(0);
        Map<String, Object> trial = confirmService.trial(c.getId());
        assertNotNull(trial.get("wageEstimate"), "试算给出金额");
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) trial.get("wageEstimate")), "125*2*4");
        assertNotNull(trial.get("compTimeExpireAt"), "调休到期日");
        // 试算不落库：确认单未分流
        assertEquals(HrOvertimeConfirm.ST_PENDING, confirmDao.selectById(c.getId()).getStatus());
        assertEquals(null, confirmDao.selectById(c.getId()).getWageAmount());
    }

    @Test
    void compTimeExpiryClearAndResign() {
        String empId = seedEmp("exp", "21750");
        // 造一个已过期的调休余额批次（双写溯源锚一并）
        jdbc.update("INSERT INTO hr_comp_time_batch (ID, BATCH_NO, EMP_ID, HOURS, REMAIN, GENERATED_AT, "
                + "EXPIRED_AT, SOURCE_CONFIRM_ID, STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('junit-ot-ct-1','CT20260101-0001',?,4,4,'2026-01-01 10:00:00', DATE_SUB(CURDATE(), "
                + "INTERVAL 1 DAY), 'junit-ot-src', 'ACTIVE', 'junit','0',0)", empId);
        jdbc.update("INSERT INTO hr_leave_balance_batch (ID, BATCH_NO, EMP_ID, TYPE_CODE, QTY, REMAIN, "
                + "QTY_UNIT, SOURCE, SOURCE_BATCH_ID, GENERATED_AT, EXPIRED_AT, STATUS, CREATE_BY, "
                + "DEL_FLAG, VER_NO) VALUES (?, 'CT20260101-0001', ?, 'COMP_TIME', 4, 4, 'HOUR', 'OT', "
                + "'junit-ot-ct-1', '2026-01-01 10:00:00', DATE_SUB(CURDATE(), INTERVAL 1 DAY), "
                + "'ACTIVE', 'junit','0',0)", UUID.randomUUID().toString().replace("-", ""), empId);
        // 到期扫描 → 自动清零留痕
        Map<String, Object> out = compTimeService.expiryScan();
        assertTrue(((Number) out.get("expired")).intValue() >= 1, "过期批次清零");
        HrLeaveBalanceBatch lb = balanceDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<HrLeaveBalanceBatch>()
                        .eq(HrLeaveBalanceBatch::getEmpId, empId)
                        .eq(HrLeaveBalanceBatch::getTypeCode, "COMP_TIME")).get(0);
        assertEquals(0, BigDecimal.ZERO.compareTo(lvRemain(lb)), "余额清零");
        assertEquals(HrLeaveBalanceBatch.ST_EXPIRED, lb.getStatus());
        HrCompTimeBatch ct = compTimeDao.selectById("junit-ot-ct-1");
        assertEquals(HrCompTimeBatch.ST_EXPIRED, ct.getStatus(), "溯源锚同步");
        // 离职折算（清零后余额 0 → 金额 0，留痕字段在）
        Map<String, Object> settle = compTimeService.resignSettlement(empId);
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) settle.get("unusedHours")));
        assertNotNull(settle.get("note"));
    }

    // ================================================================
    // add-overtime-followup：GM 特批通道 + 审批超时催办
    // ================================================================

    /** 特批全链：未勾特批 422（提示可特批）→ 勾特批缺原因 422 → GM_SPECIAL 落库 → 非 GM 403 → GM 通过进确认链。 */
    @Test
    void gmSpecialChannelFlow() {
        String empId = seedEmp("gm", "21750");
        HrOvertimeApply a1 = overtimeService.submit(payload(empId, LocalDate.now().plusDays(1), "34", false));
        overtimeService.approve(a1.getId(), Map.of("approve", "true"));
        // 未勾特批 → 422 且提示可特批
        LocalDate d2 = LocalDate.now().plusDays(2);
        ServiceException e = assertThrows(ServiceException.class,
                () -> overtimeService.submit(payload(empId, d2, "4", false)));
        assertTrue(e.getMessage().contains("总经理特批"), e.getMessage());
        // 勾特批缺超限原因 → 422
        Map<String, Object> p = payload(empId, d2, "4", false);
        p.put("isSpecial", "1");
        e = assertThrows(ServiceException.class, () -> overtimeService.submit(p));
        assertTrue(e.getMessage().contains("超限原因"), e.getMessage());
        // 补原因 → GM_SPECIAL 落库（绕过硬阻断）
        p.put("specialReason", "项目攻坚");
        HrOvertimeApply a2 = overtimeService.submit(p);
        assertEquals(HrOvertimeApply.ST_GM_SPECIAL, a2.getStatus());
        assertEquals("1", a2.getIsSpecial());
        assertEquals("项目攻坚", a2.getSpecialReason());
        // GM_SPECIAL 在途计入月累计：34+4=38，再提交任意时长被阻断
        e = assertThrows(ServiceException.class,
                () -> overtimeService.submit(payload(empId, LocalDate.now().plusDays(3), "1", false)));
        assertTrue(e.getMessage().contains("36"), e.getMessage());
        // 非 GM 审批 GM_SPECIAL → 403
        e = assertThrows(ServiceException.class,
                () -> overtimeService.approve(a2.getId(), Map.of("approve", "true")));
        assertTrue(e.getMessage().contains("ROLE_GM"), e.getMessage());
        // GM 通过 + 意见留痕 → APPROVED（与普通单同构进确认链）
        loginRole("ROLE_GM");
        HrOvertimeApply approved = overtimeService.approve(a2.getId(),
                Map.of("approve", "true", "opinion", "同意特批"));
        assertEquals(HrOvertimeApply.ST_APPROVED, approved.getStatus());
        assertEquals("同意特批", approved.getApprOpinion());
        assertNotNull(approved.getApprId());
    }

    /** GM 驳回释放额度：GM_SPECIAL 驳回转 REJECTED 后月累计回落，可再正常申请。 */
    @Test
    void gmRejectionReleasesCap() {
        String empId = seedEmp("gmr", "21750");
        HrOvertimeApply a1 = overtimeService.submit(payload(empId, LocalDate.now().plusDays(1), "34", false));
        overtimeService.approve(a1.getId(), Map.of("approve", "true"));
        Map<String, Object> p = payload(empId, LocalDate.now().plusDays(2), "4", false);
        p.put("isSpecial", "1");
        p.put("specialReason", "生产旺季");
        HrOvertimeApply a2 = overtimeService.submit(p);
        loginRole("ROLE_GM");
        HrOvertimeApply rejected = overtimeService.approve(a2.getId(), Map.of("approve", "false", "opinion", "本月已超预算"));
        assertEquals(HrOvertimeApply.ST_REJECTED, rejected.getStatus());
        assertEquals("本月已超预算", rejected.getApprOpinion());
        // 34h 已批 + 4h 已驳回（不计）→ 34+2=36 未超，可正常提交
        HrOvertimeApply a3 = overtimeService.submit(payload(empId, LocalDate.now().plusDays(3), "2", false));
        assertEquals(HrOvertimeApply.ST_DRAFT, a3.getStatus());
    }

    /** 催办扫描：超时 DRAFT 按主管×日聚合写 hr_remind；同日重跑不重复；审批后停止。 */
    @Test
    void approvalRemindScanAggregatesAndStops() {
        String empId = seedEmp("rem", null);
        HrOvertimeApply a1 = overtimeService.submit(payload(empId, LocalDate.now().plusDays(1), "2", false));
        HrOvertimeApply a2 = overtimeService.submit(payload(empId, LocalDate.now().plusDays(2), "3", false));
        // 把两单创建日回拨 7 个工作日口径（远超默认 2 天）
        jdbc.update("UPDATE hr_overtime_apply SET CREATE_DATE=DATE_SUB(NOW(), INTERVAL 7 DAY) "
                + "WHERE ID IN (?,?)", a1.getId(), a2.getId());
        int n = overtimeService.approvalRemindScan();
        assertTrue(n >= 1, "应生成催办");
        // 无部门主管 → 归口 HR，聚合一条含两单号
        var reminds = jdbc.queryForList("SELECT EMP_ID, DETAIL FROM hr_remind "
                + "WHERE REMIND_TYPE='OT_APPROVE_OVERDUE' AND CREATE_BY='scan'");
        assertEquals(1, reminds.size(), "同主管同日聚合一条");
        String detail = String.valueOf(reminds.get(0).get("DETAIL"));
        assertTrue(detail.contains(a1.getOtNo()) && detail.contains(a2.getOtNo()), detail);
        // 同日重跑 → 不新增
        assertEquals(0, overtimeService.approvalRemindScan(), "同日幂等");
        // 审批其中一单 → 次日扫描不再为它催（当日已有记录，条数不变）
        overtimeService.approve(a1.getId(), Map.of("approve", "true"));
        assertEquals(0, overtimeService.approvalRemindScan());
    }

    // ================================================================
    // helpers
    // ================================================================

    private BigDecimal lvRemain(HrLeaveBalanceBatch lb) {
        return lb.getRemain();
    }

    /** 构造休息日（假日日历 RESTDAY 行，任意运行日均可得 2x 周末倍数）。 */
    private void markRestday(LocalDate d) {
        jdbc.update("DELETE FROM hr_holiday_calendar WHERE HOLIDAY_DATE=?", d);
        jdbc.update("INSERT INTO hr_holiday_calendar (ID, HOLIDAY_DATE, NAME, TYPE, CREATE_BY, "
                + "DEL_FLAG, VER_NO) VALUES (?, ?, '测试休息日', 'RESTDAY', 'junit', '0', 0)",
                "junit-hd-" + d, d);
    }
}
