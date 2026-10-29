package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.SlaReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SLA 报告全链（add-sla-report-console spec sla-report-management：
 * BR-5.6-16 / C-5.6-05 / C-0-01）：指标推导（98/100=98%、空月不适用）/
 * DRAFT 覆盖与归档锁定 / sweep 补缺跳过 / 归档流转 / 未归档发布 422+违规留痕+双通知 /
 * CSV 下载三态校验。
 */
@SpringBootTest
class SlaReportDbTest {

    @Autowired
    private SlaReportService service;
    @Autowired
    private JdbcTemplate jdbc;

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 指标断言用的确定性月份（造数可控） */
    private String period;
    /** 空月（无造数） */
    private String emptyPeriod;

    @BeforeEach
    void setUp() {
        purge();
        period = LocalDate.now().minusMonths(3).format(PERIOD_FMT);
        emptyPeriod = LocalDate.now().minusMonths(4).format(PERIOD_FMT);
        // 造数：当月 100 次调用（98 个 2xx + 2 个 500），COST_MS 100/200/…
        LocalDateTime from = LocalDateTime.parse(period + "-05T10:00:00");
        for (int i = 0; i < 98; i++) {
            jdbc.update("INSERT INTO erp_intf_call_log (ID,CALLER,REQ_ID,API_PATH,RESP_CODE,COST_MS,CALL_AT,"
                            + "CREATE_BY,DEL_FLAG,VER_NO) VALUES (?,?,?,?,?,?,?,?, '0',0)",
                    "slal-" + i, "smk-sla", "req-" + i, "/api/test", 200, 100 + (i % 50),
                    from.plusMinutes(i), "slatest");
        }
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO erp_intf_call_log (ID,CALLER,REQ_ID,API_PATH,RESP_CODE,COST_MS,CALL_AT,"
                            + "CREATE_BY,DEL_FLAG,VER_NO) VALUES (?,?,?,?,?,?,?,?, '0',0)",
                    "slal-err-" + i, "smk-sla", "req-err-" + i, "/api/test", 500, 300,
                    from.plusMinutes(200 + i), "slatest");
        }
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        jdbc.execute("DELETE FROM erp_sla_report");
        jdbc.execute("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='SlaPublishViolation'");
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE FIELD_NAME LIKE 'SLA_REPORT%' "
                + "OR (FIELD_NAME='SECURITY_EVENT' AND NEW_VALUE='PUBLISH_BLOCKED'); SET @audit_bypass=0");
        jdbc.execute("DELETE FROM erp_intf_call_log WHERE CALLER='smk-sla'");
    }

    // ---------------- 2.1 生成与指标 ----------------

    @Test
    void generateMonthComputesMetrics() throws Exception {
        Map<String, Object> gen = service.generateMonth(period);
        assertEquals(period, gen.get("period"));
        assertEquals("DRAFT", gen.get("status"));
        assertNotNull(gen.get("dataAsOf"));

        Map<String, Object> rep = firstRow(period);
        List<Map<String, Object>> rows = metricsOf(rep);
        // 可用率 98/100 = 98%
        Map<String, Object> avail = row(rows, "AVAILABILITY");
        assertEquals("98", avail.get("value"));
        assertEquals(Boolean.FALSE, avail.get("pass"), "98% < 99.5% 未达标");
        // 调用量 100
        assertEquals("100", row(rows, "CALL_COUNT").get("value"));
        // 错误率 2/100 = 2% 未达标
        Map<String, Object> err = row(rows, "ERROR_RATE");
        assertEquals("2", err.get("value"));
        assertEquals(Boolean.FALSE, err.get("pass"));
        // P95：100 次排序后 offset=floor(95)-1=94 → 第 95 大值
        assertNotNull(row(rows, "P95_MS").get("value"));
        // 审计写入 ≥1（本测试造数无审计，但生成动作本身写审计——生成在收集之后，当月为 0 也可）
        assertNotNull(row(rows, "AUDIT_WRITES").get("value"));
        // 安全事件计数行存在
        assertNotNull(row(rows, "AUTHZ_INCIDENTS").get("value"));
        assertNotNull(row(rows, "GRANTS").get("value"));

        // DRAFT 覆盖重生成（spec 场景）
        Map<String, Object> again = service.generateMonth(period);
        assertEquals(gen.get("id"), again.get("id"), "同月同 ID 覆盖");
    }

    @Test
    void emptyPeriodShowsNotApplicable() throws Exception {
        Map<String, Object> gen = service.generateMonth(emptyPeriod);
        Map<String, Object> rep = firstRow(emptyPeriod);
        List<Map<String, Object>> rows = metricsOf(rep);
        Map<String, Object> avail = row(rows, "AVAILABILITY");
        assertEquals("不适用", avail.get("value"), "空月可用率不适用而非 0%");
        assertEquals(null, avail.get("pass"));
        assertEquals("0", row(rows, "CALL_COUNT").get("value"));
        assertNotNull(gen.get("id"));
    }

    @Test
    void invalidPeriodRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.generateMonth("2026-13"));
        assertEquals(422, e.getCode());
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.generateMonth("bad-format"));
        assertEquals(422, e2.getCode());
    }

    // ---------------- 2.2 sweep ----------------

    @Test
    void sweepGeneratesLastMonthAndSkipsExisting() {
        String lastMonth = LocalDate.now().minusMonths(1).format(PERIOD_FMT);
        // 上月不存在 → 生成
        Map<String, Object> sw1 = service.sweepGenerate();
        assertEquals(1, ((Number) sw1.get("generated")).intValue());
        assertEquals(0, ((Number) sw1.get("skipped")).intValue());
        // 再扫 → 已存在（DRAFT）跳过
        Map<String, Object> sw2 = service.sweepGenerate();
        assertEquals(0, ((Number) sw2.get("generated")).intValue());
        assertEquals(1, ((Number) sw2.get("skipped")).intValue());
        // 归档后仍跳过（版本锁定不被 sweep 覆盖）
        String id = String.valueOf(firstRow(lastMonth).get("ID"));
        service.archive(id);
        Map<String, Object> sw3 = service.sweepGenerate();
        assertEquals(1, ((Number) sw3.get("skipped")).intValue());
    }

    // ---------------- 3.1 归档/发布 ----------------

    @Test
    void archiveAndPublishFlow() {
        String id = String.valueOf(service.generateMonth(period).get("id"));

        // 未归档发布 → 422 + 违规审计 + 双通知（D4 REQUIRES_NEW 存活）
        ServiceException e = assertThrows(ServiceException.class, () -> service.publish(id));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("未归档"), "提示语：" + e.getMessage());
        Integer violation = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME='SECURITY_EVENT' "
                        + "AND EMP_ID=? AND NEW_VALUE='PUBLISH_BLOCKED'", Integer.class, id);
        assertTrue(violation >= 1, "违规审计独立事务存活（不被 422 回滚）");
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='SlaPublishViolation' AND BIZ_ID=?",
                Integer.class, id);
        assertTrue(notices >= 2, "通知生成人与内审（实际 " + notices + "）");

        // 归档
        service.archive(id);
        Map<String, Object> archived = firstRow(period);
        assertEquals("ARCHIVED", archived.get("STATUS"));
        assertNotNull(archived.get("ARCHIVE_AT"));
        // 重复归档 422
        ServiceException e2 = assertThrows(ServiceException.class, () -> service.archive(id));
        assertEquals(422, e2.getCode());
        // 归档后重生成 422（版本锁定）
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> service.generateMonth(period));
        assertEquals(422, e3.getCode());
        assertTrue(e3.getMessage().contains("锁定"));

        // 归档后发布成功
        service.publish(id);
        Map<String, Object> published = firstRow(period);
        assertEquals("PUBLISHED", published.get("STATUS"));
        assertNotNull(published.get("PUBLISH_AT"));
        assertEquals(1, ((Number) published.get("PUBLISH_COUNT")).intValue());
        // 重复发布 422
        ServiceException e4 = assertThrows(ServiceException.class, () -> service.publish(id));
        assertEquals(422, e4.getCode());
        assertTrue(e4.getMessage().contains("重复"));
        // 404
        ServiceException e5 = assertThrows(ServiceException.class,
                () -> service.archive("no-such-id"));
        assertEquals(404, e5.getCode());
    }

    // ---------------- 3.2 CSV 下载 ----------------

    @Test
    void downloadCsvStates() {
        String id = String.valueOf(service.generateMonth(period).get("id"));
        // DRAFT 下载 422
        ServiceException e = assertThrows(ServiceException.class, () -> service.download(id));
        assertEquals(422, e.getCode());

        service.archive(id);
        Map<String, Object> out = service.download(id);
        String csv = new String((byte[]) out.get("content"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("﻿"), "UTF-8 BOM 头");
        assertTrue(csv.contains("指标编码,指标名称,指标值,单位,目标值,是否达标,数据截止时间"), "固定列序");
        assertTrue(csv.contains("AVAILABILITY"), "含可用率行");
        assertTrue(csv.contains("98"), "含 98% 值");
        assertTrue(csv.contains("CALL_COUNT"), "含调用量行");
        // 下载留痕
        Integer dl = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME='SLA_REPORT_DOWNLOADED'",
                Integer.class);
        assertTrue(dl >= 1, "下载动作留痕");
        // 归档后可重复下载（spec 未禁）
        service.download(id);
    }

    // ---------------- 列表 ----------------

    @Test
    void listWithFilters() {
        service.generateMonth(period);
        Map<String, Object> all = service.list(null, null, 1, 50);
        assertTrue(((Number) all.get("total")).intValue() >= 1);
        Map<String, Object> filtered = service.list(period, "DRAFT", 1, 50);
        assertEquals(1, ((Number) filtered.get("total")).intValue());
        Map<String, Object> none = service.list(period, "PUBLISHED", 1, 50);
        assertEquals(0, ((Number) none.get("total")).intValue());
    }

    // ---------------- 内部 ----------------

    private Map<String, Object> firstRow(String p) {
        return jdbc.queryForList(
                "SELECT * FROM erp_sla_report WHERE PERIOD=? AND DEL_FLAG='0'", p).get(0);
    }

    private List<Map<String, Object>> metricsOf(Map<String, Object> rep) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                String.valueOf(rep.get("METRICS_JSON")),
                new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                });
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, String code) {
        return rows.stream()
                .filter(r -> code.equals(r.get("code"))).findFirst()
                .orElseThrow(() -> new AssertionError("指标不存在：" + code));
    }
}
