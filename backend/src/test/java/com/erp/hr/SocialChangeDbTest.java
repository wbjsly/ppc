package com.erp.hr;

import com.erp.common.ServiceException;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrSocialChangeDao;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrSocialChange;
import com.erp.service.hr.HrSocialChangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社保增减员单据 DbTest（social-insurance-ops：自动开单三触点/防重/补开/申报/作废）。
 * 造数前缀 tsc-。
 */
@SpringBootTest
class SocialChangeDbTest {

    @Autowired
    private HrSocialChangeService changeService;
    @Autowired
    private HrEmployeeDao empDao;
    @Autowired
    private HrSocialChangeDao changeDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 共享库防残留（冒烟 generate 全库拾取）
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM hr_social_change WHERE EMP_ID LIKE 'tsc-%' OR ID LIKE 'tsc-%'");
        jdbc.update("DELETE FROM hr_employee WHERE ID LIKE 'tsc-%' OR EMP_NO LIKE 'TSC-%'");
    }

    private String seedEmp(String suffix, String place) {
        String id = "tsc-emp-" + suffix;
        jdbc.update("INSERT INTO hr_employee (ID, EMP_NO, NAME, ID_CARD_NO, EMP_STATUS, BASE_SALARY, "
                + "SOCIAL_PLACE, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES (?, ?, ?, ?, 'REGULAR', 15000, ?, 'junit', '0', 0)",
                id, "TSC-" + suffix.toUpperCase(), "社保单员工" + suffix, "TSCID-" + suffix, place);
        return id;
    }

    // ---------- 自动开单与防重 ----------

    @Test
    void autoOpenThreeSourcesAndDedup() {
        String empId = seedEmp("a1", "DEMO市");
        HrSocialChange add = changeService.autoOpen(empId, HrSocialChange.TYPE_ADD,
                HrSocialChange.SRC_ONBOARDING, "ONB-1", null, null);
        assertNotNull(add, "入职过审自动开增员单");
        assertEquals(HrSocialChange.ST_DRAFT, add.getStatus());
        assertEquals("DEMO市", add.getTargetPlace(), "缴纳地缺省取主档");
        // 防重：在途 DRAFT → 再触发不重复开立
        assertNull(changeService.autoOpen(empId, HrSocialChange.TYPE_ADD,
                HrSocialChange.SRC_ONBOARDING, "ONB-2", null, null), "在途防重");
        // DONE 后也不再挡（同类型重新入职场景可再开）
        changeService.declare(add.getId(), Map.of("receiptNo", "R-A1"));
        HrSocialChange again = changeService.autoOpen(empId, HrSocialChange.TYPE_ADD,
                HrSocialChange.SRC_ONBOARDING, "ONB-3", null, null);
        assertNotNull(again, "DONE 不挡重开（重新入职）");
    }

    @Test
    void exitSettleAndTransferSources() {
        String empId = seedEmp("a2", "DEMO市");
        HrSocialChange rm = changeService.autoOpen(empId, HrSocialChange.TYPE_REMOVE,
                HrSocialChange.SRC_EXIT, "EXIT-1", null, LocalDate.of(2026, 10, 20));
        assertEquals(LocalDate.of(2026, 10, 20), rm.getChangeDate(), "减员生效日取离职日");
        HrSocialChange tr = changeService.autoOpen(empId, HrSocialChange.TYPE_TRANSFER,
                HrSocialChange.SRC_TRANSFER, "TRF-1", "深圳市", LocalDate.of(2026, 11, 1));
        assertEquals("深圳市", tr.getTargetPlace());
        assertEquals(HrSocialChange.TYPE_TRANSFER, tr.getChgType());
    }

    // ---------- 手工补开 / 申报 / 作废 ----------

    @Test
    void manualDeclareAndVoidFlow() {
        String empId = seedEmp("a3", "DEMO市");
        // 手工补开：增员缺缴纳地 → 422
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("empId", empId);
        bad.put("chgType", HrSocialChange.TYPE_ADD);
        assertThrows(ServiceException.class, () -> changeService.manualOpen(bad));
        // 正常补开
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("empId", empId);
        ok.put("chgType", HrSocialChange.TYPE_ADD);
        ok.put("targetPlace", "DEMO市");
        ok.put("note", "漏办补录");
        HrSocialChange c = changeService.manualOpen(ok);
        assertEquals(HrSocialChange.SRC_MANUAL, c.getSrcType());
        // 申报登记 → DONE
        HrSocialChange done = changeService.declare(c.getId(),
                Map.of("receiptNo", "RCPT-001", "operator", "hr01"));
        assertEquals(HrSocialChange.ST_DONE, done.getStatus());
        assertEquals("RCPT-001", done.getReceiptNo());
        assertEquals("hr01", done.getOperator());
        // DONE 不可作废 / 重复申报
        assertThrows(ServiceException.class, () -> changeService.voidChange(c.getId(), "x"));
        assertThrows(ServiceException.class, () -> changeService.declare(c.getId(), Map.of()));
        // 减员手工开 → 作废 → 防重不再挡 → 可重开
        Map<String, Object> rm = new LinkedHashMap<>();
        rm.put("empId", empId);
        rm.put("chgType", HrSocialChange.TYPE_REMOVE);
        HrSocialChange rmC = changeService.manualOpen(rm);
        changeService.voidChange(rmC.getId(), "填错城市");
        HrSocialChange rmC2 = changeService.manualOpen(rm);
        assertNotNull(rmC2, "VOID 不挡重开");
        // hasDoneChange：DONE 减员且生效日 ≤ 月末才生效
        changeService.declare(rmC2.getId(), Map.of("receiptNo", "RCPT-RM"));
        jdbc.update("UPDATE hr_social_change SET CHANGE_DATE='2026-10-20' WHERE ID=?", rmC2.getId());
        assertTrue(changeService.hasDoneChange(empId, HrSocialChange.TYPE_REMOVE, LocalDate.of(2026, 10, 31)));
        assertTrue(!changeService.hasDoneChange(empId, HrSocialChange.TYPE_REMOVE, LocalDate.of(2026, 9, 30)),
                "生效日晚于核算月末不生效");
    }
}
