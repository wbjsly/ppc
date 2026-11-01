package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.bi.BiSlaReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 报表 SLA 服务报告库测（add-bi-sla-report，spec bi-sla-report）。
 * 造数：CREATE_BY 打标 bsl- 命名空间物理清理；period 用未来月隔离（源表按月聚合不受既有数据干扰）。
 */
@SpringBootTest
class BiSlaReportDbTest {

    @Autowired
    private BiSlaReportService service;
    @Autowired
    private JdbcTemplate jdbc;

    /** 隔离月份：未来月，源表无既有数据 → 部分数据/空值路径可控 */
    private static final String P = "2099-06";

    @BeforeEach
    void seed() {
        purge();
        SystemAuth.login("bsl-designer", "ROLE_BI_DESIGNER");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_bi_sla_metric WHERE PERIOD=?", P);
        jdbc.update("DELETE FROM erp_bi_sla_report WHERE PERIOD=?", P);
        // 16.9 表的隔离测试残留（PERIOD 唯一键防撞）
        jdbc.update("DELETE FROM erp_sla_report WHERE PERIOD=? AND CREATE_BY='bsl-test'", P);
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'BiSla%'");
        jdbc.update("DELETE FROM erp_bi_kpi_snapshot WHERE CREATE_BY='bsl-test'");
        jdbc.update("DELETE FROM erp_bi_report_push_log WHERE CREATE_BY='bsl-test'");
        jdbc.update("DELETE FROM erp_bi_extract_run WHERE CREATE_BY='bsl-test'");
        jdbc.update("DELETE FROM erp_bi_export_task WHERE CREATE_BY='bsl-test'");
        jdbc.update("DELETE FROM erp_bi_adhoc_task WHERE CREATE_BY='bsl-test'");
    }

    /** 造当月源数据：全部达标（可用率 100%、P95 5s、刷新 100%、导出 100%、触达 100%） */
    private void seedFullMonth() {
        LocalDate d1 = YearMonth.parse(P).atDay(1);
        LocalDate d2 = YearMonth.parse(P).atDay(2);
        // KPI 快照两日（可用率代理）
        jdbc.update("INSERT INTO erp_bi_kpi_snapshot (ID, KPI_KEY, DIM_VALUE, SNAP_DATE, ACTUAL, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'BSL-K', '', ?, 1, "
                        + "'bsl-test', NOW(), '0', 0)", uuid(), d1);
        jdbc.update("INSERT INTO erp_bi_kpi_snapshot (ID, KPI_KEY, DIM_VALUE, SNAP_DATE, ACTUAL, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'BSL-K', '', ?, 1, "
                        + "'bsl-test', NOW(), '0', 0)", uuid(), d2);
        // 抽取批次两日按时（CONFIG_ID 必填无默认）
        jdbc.update("INSERT INTO erp_bi_extract_run (ID, BATCH_DATE, BATCH_NO, CONFIG_ID, STATUS, FINISHED_AT, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'BSL-B1', 'bsl-cfg', 'SUCCESS', "
                        + "DATE_ADD(?, INTERVAL 3 HOUR), 'bsl-test', NOW(), '0', 0)", uuid(), d1, d1);
        jdbc.update("INSERT INTO erp_bi_extract_run (ID, BATCH_DATE, BATCH_NO, CONFIG_ID, STATUS, FINISHED_AT, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, 'BSL-B2', 'bsl-cfg', 'SUCCESS', "
                        + "DATE_ADD(?, INTERVAL 3 HOUR), 'bsl-test', NOW(), '0', 0)", uuid(), d2, d2);
        // 导出任务两笔成功
        jdbc.update("INSERT INTO erp_bi_export_task (ID, TASK_TYPE, TASK_NO, USER_NAME, STATUS, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'EXPORT', 'BSL-E1', "
                        + "'bsl-designer', 'SUCCESS', 'bsl-test', ?, '0', 0)", uuid(), d1.atStartOfDay());
        jdbc.update("INSERT INTO erp_bi_export_task (ID, TASK_TYPE, TASK_NO, USER_NAME, STATUS, "
                        + "CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'EXPORT', 'BSL-E2', "
                        + "'bsl-designer', 'SUCCESS', 'bsl-test', ?, '0', 0)", uuid(), d2.atStartOfDay());
        // 即席查询 DONE 两笔 5s（TEMPLATE_ID/QUERY_JSON 必填无默认）
        jdbc.update("INSERT INTO erp_bi_adhoc_task (ID, TASK_NO, USER_NAME, TEMPLATE_ID, QUERY_JSON, "
                        + "STATUS, EXEC_MS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'BSL-A1', "
                        + "'bsl-designer', 'bsl-tpl', '{}', 'DONE', 5000, 'bsl-test', ?, '0', 0)",
                uuid(), d1.atStartOfDay());
        jdbc.update("INSERT INTO erp_bi_adhoc_task (ID, TASK_NO, USER_NAME, TEMPLATE_ID, QUERY_JSON, "
                        + "STATUS, EXEC_MS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, 'BSL-A2', "
                        + "'bsl-designer', 'bsl-tpl', '{}', 'DONE', 5000, 'bsl-test', ?, '0', 0)",
                uuid(), d2.atStartOfDay());
        // 日报推送两笔送达
        jdbc.update("INSERT INTO erp_bi_report_push_log (ID, SUB_ID, REPORT_CODE, PUSH_DATE, RECEIVER, "
                        + "CHANNEL, SEND_STATUS, RETRY_CNT, DEGRADED_FLAG, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?, 'BSL-S1', 'BSL-R', ?, 'bsl-designer', 'NOTICE', "
                        + "'SENT', 1, 'N', 'bsl-test', NOW(), '0', 0)", uuid(), d1);
        jdbc.update("INSERT INTO erp_bi_report_push_log (ID, SUB_ID, REPORT_CODE, PUSH_DATE, RECEIVER, "
                        + "CHANNEL, SEND_STATUS, RETRY_CNT, DEGRADED_FLAG, CREATE_BY, CREATE_DATE, "
                        + "DEL_FLAG, VER_NO) VALUES (?, 'BSL-S2', 'BSL-R', ?, 'bsl-designer', 'NOTICE', "
                        + "'SENT', 1, 'N', 'bsl-test', NOW(), '0', 0)", uuid(), d2);
    }

    // ---- 指标采集 ----

    @Test
    void collectFiveMetrics() {
        seedFullMonth();
        Map<String, Object> r = service.collectMetrics(P);
        assertEquals(5, ((Number) r.get("success")).intValue(), "五指标全部采集成功");
        // 可用率：2 日有快照 / 当月 30 日 → 部分数据 + 值偏低
        Map<String, Object> av = jdbc.queryForMap(
                "SELECT METRIC_VALUE, TARGET, PARTIAL_FLAG FROM erp_bi_sla_metric "
                        + "WHERE PERIOD=? AND METRIC_KEY='AVAILABILITY'", P);
        assertNotNull(av.get("METRIC_VALUE"), "可用率有值");
        assertEquals("Y", av.get("PARTIAL_FLAG"), "缺失日 → 部分数据（BR-4.10-29）");
        // P95：两笔 5000ms → 5 秒
        BigDecimal p95 = jdbc.queryForObject(
                "SELECT METRIC_VALUE FROM erp_bi_sla_metric WHERE PERIOD=? AND METRIC_KEY='QUERY_P95'",
                BigDecimal.class, P);
        assertEquals(0, new BigDecimal("5.00").compareTo(p95), "P95=5 秒");
        // 幂等：重跑覆盖不叠加
        service.collectMetrics(P);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_sla_metric WHERE PERIOD=? AND METRIC_KEY='QUERY_P95'",
                Integer.class, P);
        assertEquals(1, cnt, "重跑幂等（UNIQUE PERIOD+METRIC_KEY）");
    }

    @Test
    void collectPartialDataListsMissingDates() {
        seedFullMonth();
        service.collectMetrics(P);
        Map<String, Object> rf = jdbc.queryForMap(
                "SELECT PARTIAL_FLAG, MISSING_DATES FROM erp_bi_sla_metric "
                        + "WHERE PERIOD=? AND METRIC_KEY='REFRESH_ONTIME'", P);
        assertEquals("Y", rf.get("PARTIAL_FLAG"), "抽取缺日 → 部分数据");
        String missing = String.valueOf(rf.get("MISSING_DATES"));
        assertTrue(missing.contains(YearMonth.parse(P).atDay(3).toString()),
                "缺失日期列出（禁插值）：" + missing);
    }

    // ---- 报告生成与三要素 ----

    @Test
    void generateReportAndThreeColor() {
        seedFullMonth();
        service.collectMetrics(P);
        Map<String, Object> r = service.generateReport(P);
        assertEquals("DRAFT", r.get("status"), "生成初始 DRAFT");
        // 可用率 2/30≈6.67% < 99.5×0.95 → BELOW（未达标）
        String st = jdbc.queryForObject(
                "SELECT METRICS_JSON FROM erp_bi_sla_report WHERE PERIOD=? AND VERSION=1", String.class, P);
        assertTrue(st.contains("BELOW"), "可用率未达标（造数仅 2 日）");
        assertTrue(st.contains("ACHIEVED"), "P95/导出/触达达标");
    }

    @Test
    void submitBlockedWhenThreeElementsMissing() {
        seedFullMonth();
        service.collectMetrics(P);
        service.generateReport(P);
        // 不填三要素直接提交 → 422（BR-4.10-27）
        ServiceException e = assertThrows(ServiceException.class, () -> service.submit(P));
        assertEquals(422, e.getCode(), "三要素缺失 422");
        assertTrue(e.getMessage().contains("AVAILABILITY"), "提示含未达标指标");
    }

    @Test
    void submitWithThreeElementsCreatesApproval() {
        seedFullMonth();
        service.collectMetrics(P);
        service.generateReport(P);
        service.saveRootCause(P, List.of(Map.of(
                "metricKey", "AVAILABILITY", "rootCause", "调度任务部分失败",
                "owner", "张三", "dueDate", "2099-07-15")));
        Map<String, Object> r = service.submit(P);
        assertEquals("PENDING_APPROVAL", r.get("status"), "提交后进入审核");
        assertNotNull(r.get("approvalInstanceId"), "审批实例已创建");
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_sla_report WHERE PERIOD=? AND VERSION=1", String.class, P);
        assertEquals("PENDING_APPROVAL", st, "状态落库");
    }

    // ---- 归档锁定与新版本 ----

    @Test
    void archivedReportIsLockedAndNewVersionWorks() {
        seedFullMonth();
        service.collectMetrics(P);
        service.generateReport(P);
        // 模拟审批通过归档（直接调回调入口）
        String id = jdbc.queryForObject(
                "SELECT ID FROM erp_bi_sla_report WHERE PERIOD=? AND VERSION=1", String.class, P);
        service.generateReport(P); // 确保 draft 存在
        // 直接置 ARCHIVED（模拟回调结果）
        jdbc.update("UPDATE erp_bi_sla_report SET STATUS='ARCHIVED', ARCHIVE_AT=NOW() WHERE ID=?", id);
        // 归档后编辑被拒（版本锁定）
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.saveImprove(P, Map.of("measures", "x")));
        assertEquals(422, e.getCode(), "归档锁定拒编辑");
        // 新版本生成
        Map<String, Object> v = service.newVersion(P);
        assertEquals(2, ((Number) v.get("version")).intValue(), "VERSION+1");
        assertEquals("DRAFT", v.get("status"), "新版本为草稿");
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_bi_sla_report WHERE PERIOD=? AND DEL_FLAG='0'", Integer.class, P);
        assertEquals(2, rows, "原归档版本保留（版本留痕）");
    }

    // ---- 发布门禁 ----

    @Test
    void publishBlockedWhenNotArchived() {
        seedFullMonth();
        service.collectMetrics(P);
        service.generateReport(P);
        // DRAFT 发布 → 422 + 违规留痕 + 通知数据治理（BR-4.10-26）
        ServiceException e = assertThrows(ServiceException.class, () -> service.publish(P));
        assertEquals(422, e.getCode(), "未归档发布硬阻断");
        assertTrue(e.getMessage().contains("未归档"), "提示未归档");
        Integer notice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='BiSlaPublishBlocked'",
                Integer.class);
        assertTrue(notice != null && notice > 0, "违规通知数据治理专员");
    }

    @Test
    void publishArchivedReportSucceeds() {
        seedFullMonth();
        service.collectMetrics(P);
        service.generateReport(P);
        String id = jdbc.queryForObject(
                "SELECT ID FROM erp_bi_sla_report WHERE PERIOD=? AND VERSION=1", String.class, P);
        jdbc.update("UPDATE erp_bi_sla_report SET STATUS='ARCHIVED', ARCHIVE_AT=NOW() WHERE ID=?", id);
        Map<String, Object> r = service.publish(P);
        assertEquals("PUBLISHED", r.get("status"), "归档后发布成功");
        assertNotNull(r.get("pdfRef"), "PDF 引用返回");
        // 发布记录留痕
        String pub = jdbc.queryForObject(
                "SELECT PUBLISH_JSON FROM erp_bi_sla_report WHERE ID=?", String.class, id);
        assertTrue(pub != null && pub.contains("NOTICE"), "发布记录含渠道");
    }

    @Test
    void pdfOnlyForArchived() {
        seedFullMonth();
        service.collectMetrics(P);
        service.generateReport(P);
        ServiceException e = assertThrows(ServiceException.class, () -> service.pdf(P));
        assertEquals(422, e.getCode(), "未归档不可下载发布版 PDF");
    }

    // ---- 与 16.9 隔离 ----

    @Test
    void isolatedFromInterfaceSlaReport() {
        // 16.9 erp_sla_report 造一行（ID 带运行期后缀防撞）
        String isoId = "bsl-iso-" + System.nanoTime();
        jdbc.update("INSERT INTO erp_sla_report (ID, PERIOD, METRICS_JSON, STATUS, CREATE_BY, "
                + "CREATE_DATE, DEL_FLAG, VER_NO) VALUES (?, ?, '[]', 'DRAFT', "
                + "'bsl-test', NOW(), '0', 0)", isoId, P);
        seedFullMonth();
        service.collectMetrics(P);
        service.generateReport(P);
        // 14.9 操作不影响 16.9 行
        String iso = jdbc.queryForObject(
                "SELECT STATUS FROM erp_sla_report WHERE ID=?", String.class, isoId);
        assertEquals("DRAFT", iso, "16.9 报告不受 14.9 操作影响");
        jdbc.update("DELETE FROM erp_sla_report WHERE ID=?", isoId);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
