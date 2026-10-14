package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.SamplingRecord;
import com.erp.service.qms.InspectionLotService;
import com.erp.service.qms.SamplingRecordService;
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
 * 抽样执行记录（change rework-sampling-inspection，spec inspection-lot
 * 「抽样执行记录」/「随机选样辅助」）：
 * 首次留痕 + FLAG 置位、加大抽样不覆盖、枚举校验 422、权限 401/403、
 * 零侵入回归——无抽样记录时 input/judge/release 全链正常。
 */
@SpringBootTest
class SamplingRecordDbTest {

    @Autowired
    private SamplingRecordService service;
    @Autowired
    private InspectionLotService lotService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asRole("ROLE_INSPECTOR");
        seedLot("lot-smp-1", "IL-SMP-001", 100);
        seedLot("lot-smp-2", "IL-SMP-002", 50);
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_qms_sampling_record WHERE LOT_ID LIKE 'lot-smp-%'");
        jdbc.update("DELETE FROM erp_qms_lot_item WHERE LOT_ID LIKE 'lot-smp-%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE ID LIKE 'lot-smp-%'");
    }

    private void seedLot(String id, String lotNo, int qty) {
        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, LOT_TYPE, SOURCE_TYPE, ITEM_CODE, ITEM_NAME, "
                + "LOT_QTY, RISK_GRADE, STATUS, RESULT, PRIORITY, STRICTNESS, SAMPLE_QTY, SAMPLED_FLAG, "
                + "CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, 'IQC', 'MANUAL', 'SMP-ITEM', '抽样测试物料', ?, 'C', "
                + "'PENDING', 'PENDING', 'NORMAL', 'NORMAL', 20, NULL, 'junit', '0', 0)",
                id, lotNo, qty);
    }

    private void asRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, role));
    }

    private Map<String, Object> body(String method, String reason, String qty) {
        return Map.of("sampleMethod", method, "triggerReason", reason,
                "actualSampleQty", qty, "remark", "junit 测试");
    }

    // ---------- 首次留痕 + FLAG 一致性 ----------

    @Test
    void initialRecordPersistsAndSetsFlag() {
        Map<String, Object> rec = service.create("lot-smp-1", body("RANDOM", "INITIAL", "20"));
        assertEquals(1, rec.get("seq"));
        assertEquals("RANDOM", rec.get("sampleMethod"));
        assertNotNull(rec.get("samplerName"), "抽样人姓名应解析");

        List<Map<String, Object>> list = service.listByLot("lot-smp-1");
        assertEquals(1, list.size());

        String flag = jdbc.queryForObject(
                "SELECT SAMPLED_FLAG FROM erp_qms_inspection_lot WHERE ID='lot-smp-1'", String.class);
        assertEquals("1", flag, "SAMPLED_FLAG 应同事务置位");
    }

    // ---------- 加大抽样：第二条独立记录不覆盖 ----------

    @Test
    void escalateKeepsIndependentRecords() {
        service.create("lot-smp-1", body("RANDOM", "INITIAL", "20"));
        service.create("lot-smp-1", body("NINE_POINT", "ESCALATE", "40"));

        List<Map<String, Object>> list = service.listByLot("lot-smp-1");
        assertEquals(2, list.size(), "两条记录并存");
        assertEquals(1, list.get(0).get("seq"));
        assertEquals(2, list.get(1).get("seq"));
        assertEquals(0, ((BigDecimal) list.get(0).get("actualSampleQty")).compareTo(new BigDecimal("20")),
                "首次记录不被覆盖");
        assertEquals(0, ((BigDecimal) list.get(1).get("actualSampleQty")).compareTo(new BigDecimal("40")));
        assertEquals("ESCALATE", list.get(1).get("triggerReason"));
    }

    // ---------- 枚举与数值校验 422 ----------

    @Test
    void invalidInputRejected() {
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.create("lot-smp-1", body("BAD_METHOD", "INITIAL", "20")));
        assertEquals(422, e1.getCode());

        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create("lot-smp-1", body("RANDOM", "BAD_REASON", "20")));
        assertEquals(422, e2.getCode());

        ServiceException e3 = assertThrows(ServiceException.class,
                () -> service.create("lot-smp-1", body("RANDOM", "INITIAL", "0")));
        assertEquals(422, e3.getCode());

        ServiceException e4 = assertThrows(ServiceException.class,
                () -> service.create("lot-smp-1", body("RANDOM", "INITIAL", "-5")));
        assertEquals(422, e4.getCode());

        ServiceException e5 = assertThrows(ServiceException.class,
                () -> service.create("lot-smp-none", body("RANDOM", "INITIAL", "20")));
        assertEquals(404, e5.getCode());
    }

    // ---------- 权限：未认证 401 / 越权 403 ----------

    @Test
    void authBoundaries() {
        SecurityContextHolder.clearContext();
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> service.create("lot-smp-1", body("RANDOM", "INITIAL", "20")));
        assertEquals(401, e1.getCode());

        asRole("ROLE_PLANNER");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.create("lot-smp-1", body("RANDOM", "INITIAL", "20")));
        assertEquals(403, e2.getCode());
        assertEquals(0, service.listByLot("lot-smp-1").size(), "403 未写入任何记录");
    }

    // ---------- 零侵入回归：无抽样记录不阻断判定/放行 ----------

    @Test
    void missingRecordDoesNotBlockJudgeRelease() {
        // lot-smp-2 无任何抽样记录（SAMPLED_FLAG 仍 NULL）；造「判定合格待确认」状态直接放行
        jdbc.update("UPDATE erp_qms_inspection_lot SET STANDARD_ID='std-smp-x', STANDARD_CODE='SMP-STD', "
                + "STANDARD_VERSION=1, RESULT='PASS' WHERE ID='lot-smp-2'");
        // 非 CTQ 检验项已判合格（记录完整）
        jdbc.update("INSERT INTO erp_qms_lot_item (ID, LOT_ID, SEQUENCE_NO, CHARACTERISTIC_NAME, CTQ_FLAG, "
                + "SPEC_TYPE, JUDGE, INSPECT_BY, INSPECT_TIME, CREATE_BY, VER_NO) VALUES "
                + "('li-smp-2-1','lot-smp-2',1,'外观','0','LOOK','PASS','tester',NOW(),'junit',0)");

        Map<String, Object> out = lotService.confirmRelease("lot-smp-2");
        assertNotNull(out);

        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_inspection_lot WHERE ID='lot-smp-2'", String.class);
        assertEquals("RELEASED", status, "无抽样记录不阻断放行（可选留痕 D2）");

        String flag = jdbc.queryForObject(
                "SELECT SAMPLED_FLAG FROM erp_qms_inspection_lot WHERE ID='lot-smp-2'", String.class);
        assertTrue(flag == null || flag.isEmpty(), "放行不回写抽样标记");
        assertTrue(service.listByLot("lot-smp-2").isEmpty());
    }

    // ---------- 报告段：详情附 samplingRecords（Controller 已验证，此处验证服务层数据形状） ----------

    @Test
    void recordShapeForReport() {
        service.create("lot-smp-1", body("OTHER", "RESAMPLE", "12"));
        List<Map<String, Object>> list = service.listByLot("lot-smp-1");
        Map<String, Object> r = list.get(0);
        for (String key : new String[]{"id", "seq", "actualSampleQty", "sampleMethod",
                "samplerId", "samplerName", "sampledTime", "triggerReason", "remark"}) {
            assertTrue(r.containsKey(key), "报告段缺字段: " + key);
        }
        assertEquals("RESAMPLE", r.get("triggerReason"));
    }
}
