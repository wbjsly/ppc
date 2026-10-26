package com.erp.hr;

import com.erp.dao.hr.HrSecondmentDao;
import com.erp.entity.hr.HrSecondment;
import com.erp.service.hr.HrSecondmentVoucherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 借调成本月度分摊凭证 DbTest（secondment-cost-allocation：成对凭证/幂等/折算/估算/过期跳过/返岗停摊）。
 * 造数前缀 tsv-，核算月 209911。
 */
@SpringBootTest
class SecondmentVoucherDbTest {

    private static final String YM = "209911";

    @Autowired
    private HrSecondmentVoucherService voucherService;
    @Autowired
    private HrSecondmentDao secDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
    }

    @AfterEach
    void tearDown() {
        purge(); // 共享库防残留
    }

    private void purge() {
        jdbc.update("DELETE l FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID "
                + "WHERE v.SOURCE_TYPE='SECONDMENT'");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='SECONDMENT'");
        jdbc.update("DELETE FROM hr_social_change WHERE EMP_ID LIKE 'tsv-%'");
        jdbc.update("DELETE FROM hr_secondment WHERE EMP_ID LIKE 'tsv-%' OR ID LIKE 'tsv-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tsv-%' OR EMP_NO LIKE 'TSV-%'");
    }

    /** 造在途借调单（EFFECTED，整月在途） */
    private HrSecondment seedActive(String suffix, LocalDate start, LocalDate end, String pct) {
        String empId = "tsv-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, HIRE_DATE, "
                + "BASE_SALARY, SOCIAL_PLACE, LEGAL_ENTITY, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', ?, 21750, 'DEMO市', 'TSV-A', 'junit', '0', 0)",
                empId, "TSV-" + suffix.toUpperCase(), "分摊员工" + suffix, "TSVID-" + suffix,
                LocalDate.now().minusMonths(24));
        String id = "tsv-sec-" + suffix;
        jdbc.update("INSERT INTO hr_secondment (ID, SEC_NO, CHG_TYPE, EMP_ID, EMP_NAME, EMP_NO, "
                + "SRC_LE, TARGET_LE, COST_SHARE_PCT, SHARE_LOCKED, STATUS, START_DATE, END_DATE, "
                + "REMIND_FLAG, EXTEND_MONTHS_TOTAL, LINK_PENDING, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, 'SECOND', ?, ?, ?, 'TSV-A', 'TSV-B', ?, '1', 'EFFECTED', ?, ?, '0', 0, '0', 'junit', '0', 0)",
                id, "SEC2099-T" + suffix.toUpperCase(), empId, "分摊员工" + suffix, "TSV-" + suffix.toUpperCase(),
                new BigDecimal(pct), start, end);
        return secDao.selectById(id);
    }

    private int voucherCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM erp_fin_voucher WHERE SOURCE_TYPE='SECONDMENT'",
                Integer.class);
        return n == null ? 0 : n;
    }

    private BigDecimal voucherSum(String direction) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(l.AMOUNT),0) FROM erp_fin_voucher_line l "
                        + "JOIN erp_fin_voucher v ON l.VOUCHER_ID=v.ID "
                        + "WHERE v.SOURCE_TYPE='SECONDMENT' AND l.DIRECTION=?",
                BigDecimal.class, direction);
    }

    @Test
    void fullMonthPairedVouchersIdempotent() {
        seedActive("a", LocalDate.of(2099, 11, 1), LocalDate.of(2099, 12, 31), "100");
        Map<String, Object> out = voucherService.monthlyGenerate(YM);
        assertEquals(1, ((Number) out.get("generated")).intValue(), "生成 1 单：" + out);
        // 成对凭证：调出 Dr/Cr 各一张 + 调入 Dr/Cr 各一张 = 4 行（两凭证各 2 行）
        assertEquals(2, voucherCount(), "成对两张凭证");
        assertEquals(0, voucherSum("Dr").compareTo(voucherSum("Cr")), "借贷平衡");
        // 幂等
        Map<String, Object> again = voucherService.monthlyGenerate(YM);
        assertEquals(0, ((Number) again.get("generated")).intValue(), "幂等跳过");
        assertEquals(2, voucherCount());
        // 科目：122101/221101/660202/224102 各一行
        Long recv = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_fin_voucher_line WHERE ACCOUNT_CODE='122101' AND DIRECTION='Dr'",
                Long.class);
        assertEquals(1, recv, "调出方其他应收款");
    }

    @Test
    void midMonthProrationAndEstimate() {
        // 11/16 生效 → 15 在途日（16~30）/30 天折算；无已审批批次 → 基数估算标注
        seedActive("b", LocalDate.of(2099, 11, 16), LocalDate.of(2099, 12, 31), "100");
        Map<String, Object> out = voucherService.monthlyGenerate(YM);
        assertEquals(1, ((Number) out.get("generated")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) out.get("details");
        Map<String, Object> d = details.get(0);
        assertEquals(15, ((Number) d.get("inDays")).intValue(), "在途 15 天");
        assertTrue(String.valueOf(d.get("estimateNote")).contains("基数估算"), "估算标注：" + d.get("estimateNote"));
        // 分摊额 = (21750 + 单位社保) × 100% × 15/30 —— 与预览一致
        List<Map<String, Object>> pv = voucherService.preview(YM);
        assertEquals(1, pv.size());
        assertEquals(0, ((BigDecimal) d.get("allocAmount")).compareTo((BigDecimal) pv.get(0).get("allocAmount")));
    }

    @Test
    void expiredAlertSkippedAndReturnedStops() {
        // 过期告警单：跳过 + 计数
        HrSecondment expired = seedActive("c", LocalDate.of(2099, 10, 1), LocalDate.of(2099, 10, 31), "100");
        jdbc.update("UPDATE hr_secondment SET STATUS='EXPIRED_ALERT' WHERE ID=?", expired.getId());
        Map<String, Object> out = voucherService.monthlyGenerate(YM);
        assertTrue(((Number) out.get("skippedExpired")).intValue() >= 1, "过期跳过计数：" + out);
        // 返岗单：返岗当月按返岗日折算，次月不再生成
        HrSecondment returned = seedActive("d", LocalDate.of(2099, 11, 1), LocalDate.of(2099, 12, 31), "100");
        jdbc.update("UPDATE hr_secondment SET STATUS='RETURNED', RETURN_AT='2099-11-10 12:00:00' "
                + "WHERE ID=?", returned.getId());
        Map<String, Object> nov = voucherService.monthlyGenerate(YM);
        assertEquals(1, ((Number) nov.get("generated")).intValue(), "返岗当月按 10/30 折算生成");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) nov.get("details");
        assertEquals(10, ((Number) details.get(0).get("inDays")).intValue());
        Map<String, Object> dec = voucherService.monthlyGenerate("209912");
        assertEquals(0, ((Number) dec.get("generated")).intValue(), "返岗次月停摊");
    }
}
