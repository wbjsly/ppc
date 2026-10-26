package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.service.hr.HrSocialInsuranceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 社保+个税 DbTest（payroll-calculation：封顶保底/未配置 L1/年度复制 + 累计预扣/年终奖/专项附加）。 */
@SpringBootTest
class SocialIitDbTest {

    @Autowired
    private HrSocialInsuranceService socialService;
    @Autowired
    private com.erp.service.hr.HrIitService iitService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'junit-si-%' OR EMP_NO LIKE 'JSI-%'");
        jdbc.update("DELETE FROM hr_social_policy WHERE YEAR IN (2098, 2099)");
        jdbc.update("DELETE c FROM hr_iit_cumulative c WHERE c.EMP_ID LIKE 'junit-si-%'");
        jdbc.update("DELETE FROM hr_iit_special_deduction WHERE EMP_ID LIKE 'junit-si-%'");
    }

    private String seedEmp(String suffix, String salary, String place) {
        String id = "junit-si-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, BASE_SALARY, "
                + "SOCIAL_PLACE, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, ?, 'REGULAR', ?, ?, 'junit', '0', 0)",
                id, "JSI-" + suffix.toUpperCase(), "社保员工" + suffix, "JSIID-" + suffix, salary, place);
        return id;
    }

    /** 封顶保底：月薪 50000 超 DEMO市上限 35000 → 按 35000 计。 */
    @Test
    void calcCapsAtPolicyMax() {
        String empId = seedEmp("cap", "50000", "DEMO市");
        Map<String, Object> out = socialService.calc(empId, 2026);
        assertEquals("DEMO市", out.get("city"));
        BigDecimal eeTotal = (BigDecimal) out.get("eeTotal");
        // 个人：养老 35000×8%=2800 + 医疗 35000×2%=700 + 失业 35000×0.5%=175 + 公积金 35000×7%=2450 = 6125
        assertEquals(0, new BigDecimal("6125.00").compareTo(eeTotal), "个人合计封顶口径");
        BigDecimal empTotal = (BigDecimal) out.get("empTotal");
        // 单位：16%+9.5%+0.5%+0.4%+0.8%+7% = 34.2% × 35000 = 12, 34.2%*35000=11970
        assertEquals(0, new BigDecimal("11970.00").compareTo(empTotal), "单位合计");
    }

    /** 保底：月薪 2000 低于下限 → 各险种按自身下限计（五险 4000、公积金 2400）。 */
    @Test
    void calcFloorsAtPolicyMin() {
        String empId = seedEmp("floor", "2000", "DEMO市");
        Map<String, Object> out = socialService.calc(empId, 2026);
        BigDecimal eeTotal = (BigDecimal) out.get("eeTotal");
        // 五险下限 4000：(8%+2%+0.5%)×4000=420；公积金下限 2400：7%×2400=168 → 合计 588
        assertEquals(0, new BigDecimal("588.00").compareTo(eeTotal),
                "个人合计保底口径（各险种自身下限）");
    }

    /** 未配置城市 → L1 422（含险种名）。 */
    @Test
    void calcUnconfiguredCityBlocked() {
        String empId = seedEmp("nocfg", "10000", "未配置城");
        ServiceException e = assertThrows(ServiceException.class, () -> socialService.calc(empId, 2026));
        assertTrue(e.getMessage().contains("未配置"), e.getMessage());
        assertTrue(e.getMessage().contains("PENSION"), "提示缺哪一险种");
    }

    /** 无缴纳地/无月薪基数 → 422。 */
    @Test
    void calcRequiresPlaceAndSalary() {
        String noPlace = seedEmp("np", "10000", null);
        ServiceException e = assertThrows(ServiceException.class, () -> socialService.calc(noPlace, 2026));
        assertTrue(e.getMessage().contains("缴纳地"), e.getMessage());
        String noSalary = seedEmp("ns", null, "DEMO市");
        e = assertThrows(ServiceException.class, () -> socialService.calc(noSalary, 2026));
        assertTrue(e.getMessage().contains("月薪"), e.getMessage());
    }

    /** 年度复制：DEMO市 2026 → 2099，跳过已存在。 */
    @Test
    void copyYearSkipsExisting() {
        Map<String, Object> out = socialService.copyYear(2026, 2099);
        assertTrue(((Number) out.get("copied")).intValue() >= 6, "至少复制 6 险种");
        Map<String, Object> again = socialService.copyYear(2026, 2099);
        assertEquals(0, ((Number) again.get("copied")).intValue(), "幂等跳过");
        Long cnt = jdbc.queryForObject("SELECT COUNT(*) FROM hr_social_policy WHERE CITY='DEMO市' AND YEAR=2099",
                Long.class);
        assertEquals(6, cnt);
    }

    // ================================================================
    // 个税：累计预扣跨月递进
    // ================================================================

    @Test
    void iitCumulativeMonthOverMonth() {
        String empId = seedEmp("iit", "20000", "DEMO市");
        // 1 月：应发 20000，专项扣除 2000，专项附加 2000
        // 累计所得 = 20000-2000-2000-5000 = 11000 → 级距1（≤36000）3% → 税 330
        Map<String, Object> jan = iitService.calcMonthly(empId, 2026, 1,
                new BigDecimal("20000"), new BigDecimal("2000"), new BigDecimal("2000"));
        assertEquals(0, new BigDecimal("330.00").compareTo((BigDecimal) jan.get("tax")), "1 月税 330");
        // 2 月同口径：累计所得 22000 → 税 660，本期 = 660-330 = 330
        Map<String, Object> feb = iitService.calcMonthly(empId, 2026, 2,
                new BigDecimal("20000"), new BigDecimal("2000"), new BigDecimal("2000"));
        assertEquals(0, new BigDecimal("330.00").compareTo((BigDecimal) feb.get("tax")), "2 月本期税 330");
        // 累计已预扣 660
        Map<String, Object> cum = iitService.cumulative(empId, 2026);
        assertEquals(Boolean.TRUE, cum.get("exists"));
        assertEquals(0, new BigDecimal("660.00").compareTo((BigDecimal) cum.get("cumTaxPaid")));
    }

    /** 扣除后累计为负 → 税 0，台账继续滚动。 */
    @Test
    void iitNegativeTaxableFloorsAtZero() {
        String empId = seedEmp("iit0", "5000", "DEMO市");
        // 应发 3000，专项扣除 800：累计 3000-800-0-5000 = -2800 → 税 0
        Map<String, Object> out = iitService.calcMonthly(empId, 2026, 1,
                new BigDecimal("3000"), new BigDecimal("800"), null);
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) out.get("tax")));
    }

    /** 年终奖单独计税：60000÷12=5000 → 级距2（36000~144000）10% - 210（2520÷12）= 5790。 */
    @Test
    void bonusSingleTaxCalc() {
        String empId = seedEmp("bonus", "20000", "DEMO市");
        Map<String, Object> out = iitService.bonusTax(empId, new BigDecimal("60000"), "SINGLE");
        assertEquals("SINGLE", out.get("choice"));
        assertEquals(0, new BigDecimal("5790.00").compareTo((BigDecimal) out.get("tax")), "单独计税 5790");
        assertEquals(0, new BigDecimal("54210.00").compareTo((BigDecimal) out.get("afterTax")));
        // 并入综合：预估 0（实际随当月累计吸收）
        Map<String, Object> comb = iitService.bonusTax(empId, new BigDecimal("60000"), "COMBINED");
        assertEquals(0, new BigDecimal("0.00").compareTo((BigDecimal) comb.get("tax")));
        // 非法 choice
        assertThrows(ServiceException.class, () -> iitService.bonusTax(empId, new BigDecimal("1000"), "XXX"));
    }

    /** 专项附加扣除 upsert + 月度合计 + 期初结转幂等。 */
    @Test
    void specialDeductionUpsertAndCarryOver() {
        String empId = seedEmp("sd", "20000", "DEMO市");
        Map<String, Object> sd = new LinkedHashMap<>();
        sd.put("empId", empId);
        sd.put("deductType", "HOUSING_RENT");
        sd.put("monthlyAmount", 1500);
        iitService.saveSpecialDeduction(sd);
        // upsert 同类型覆盖
        sd.put("monthlyAmount", 1800);
        iitService.saveSpecialDeduction(sd);
        assertEquals(1, iitService.specialDeductions(empId).size());
        assertEquals(0, new BigDecimal("1800").compareTo(iitService.monthlySpecialTotal(empId)));
        // 期初结转 + 重复结转阻断
        iitService.carryOver(empId, 2098, new BigDecimal("240000"), new BigDecimal("24000"),
                new BigDecimal("24000"), new BigDecimal("3600"));
        Map<String, Object> cum = iitService.cumulative(empId, 2098);
        assertEquals(Boolean.TRUE, cum.get("exists"));
        assertThrows(ServiceException.class, () -> iitService.carryOver(empId, 2098,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
