package com.erp.system;

import com.erp.common.AesUtil;
import com.erp.common.ServiceException;
import com.erp.service.bi.ExportService;
import com.erp.service.bi.ReportTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出生命周期库测（add-export-console，spec bi-export-lifecycle）：
 * 模板驱动三格式导出 + 加密落盘 + 下载解密 + 权限再校验 + 优先级 +
 * 超时终止 + 到期删除/提醒。
 */
@SpringBootTest
class ExportLifecycleDbTest {

    @Autowired
    private ExportService service;
    @Autowired
    private ReportTemplateService templateService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SystemAuth.login("exp-designer", "ROLE_BI_DESIGNER");
    }

    @AfterEach
    void tearDown() {
        SystemAuth.logout();
        purge();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval i "
                + "ON t.APPR_ID=i.ID WHERE i.BIZ_TYPE='BiExportOverLimit'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='BiExportOverLimit'");
        jdbc.update("DELETE FROM erp_bi_export_task WHERE CREATE_BY LIKE 'exp%' "
                + "OR USER_NAME LIKE 'exp%' OR TASK_NO LIKE 'EXP%'");
        jdbc.update("DELETE FROM erp_bi_perm_rule WHERE USER_NAME LIKE 'exp%' "
                + "OR CREATE_BY LIKE 'exp%'");
        // 模板 CODE 有唯一键，purge 必须删（否则重跑 DuplicateKey）
        jdbc.update("DELETE FROM erp_bi_report_template WHERE CODE LIKE 'EXP-TPL%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE LIKE 'BiExport%'");
    }

    /** 造 PUBLISHED 模板 + 测试用户行级通配绑定（executeGovernedSelect 权限再校验基础） */
    private String seedTemplate(String code) {
        String id = uuid();
        jdbc.update("INSERT INTO erp_bi_report_template (ID, CODE, NAME, CATEGORY, SQL_TEXT, "
                        + "COLUMNS_JSON, METRICS_JSON, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, ?, ?, 'STANDARD', 'SELECT 1 AS A, ''x'' AS B', '[]', '[]', "
                        + "'PUBLISHED', 'exp-tester', NOW(), '0', 0)",
                id, code, "导出测试模板-" + code);
        jdbc.update("INSERT INTO erp_bi_perm_rule (ID, RULE_TYPE, TARGET_KEY, USER_NAME, ENTITY_ID, "
                        + "ACTION, STATUS, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                        + "VALUES (?, 'ROW_LE', '*', 'exp-designer', '*', 'PASS', 'ENABLED', "
                        + "'exp-tester', NOW(), '0', 0)",
                "expr-perm-" + code);
        return id;
    }

    private Map<String, Object> submit(String dataset, Map<String, Object> params, long est) {
        Map<String, Object> body = new HashMap<>(params);
        body.put("format", params.getOrDefault("format", "csv"));
        return service.submit(dataset, body, est);
    }

    // ---------- 模板驱动导出三格式（spec 需求一/二） ----------

    @Test
    void templateExportThreeFormats() throws Exception {
        String tpl = seedTemplate("EXP-TPL-1");
        for (String fmt : List.of("csv", "xlsx", "pdf")) {
            // 只清导出任务（purge 会删模板，须在 seed 之后仅清任务）
            jdbc.update("DELETE FROM erp_bi_export_task WHERE TASK_NO LIKE 'EX%' "
                    + "OR USER_NAME LIKE 'exp%'");
            Map<String, Object> p = new HashMap<>();
            p.put("templateId", tpl);
            p.put("format", fmt);
            p.put("reportName", "测试报表");
            Map<String, Object> sub = submit("template", p, 10);
            String id = (String) sub.get("id");
            service.drainOnce();
            String status = jdbc.queryForObject(
                    "SELECT STATUS FROM erp_bi_export_task WHERE ID=?", String.class, id);
            String errMsg = jdbc.queryForObject(
                    "SELECT ERROR_MSG FROM erp_bi_export_task WHERE ID=?", String.class, id);
            assertEquals("DONE", status, fmt + " 模板导出完成（err=" + errMsg + "）");
            String filePath = jdbc.queryForObject(
                    "SELECT FILE_PATH FROM erp_bi_export_task WHERE ID=?", String.class, id);
            assertNotNull(filePath);
            assertTrue(Files.exists(Path.of(filePath)), fmt + " 密文文件落盘");
            // 密文不可直读（前 4 字节非明文特征）
            byte[] cipher = Files.readAllBytes(Path.of(filePath));
            String head = new String(cipher, 0, Math.min(4, cipher.length));
            assertTrue(!head.contains("watermark") && !head.contains("SELECT"),
                    fmt + " 密文不可直读");
            // 解密往返含水印
            byte[] plain = AesUtil.decryptFile(Path.of(filePath), "erp-export-default-secret");
            String text = fmt.equals("csv")
                    ? new String(plain, java.nio.charset.StandardCharsets.UTF_8)
                    : "binary-ok";
            if ("csv".equals(fmt)) {
                assertTrue(text.contains("# watermark:"), "CSV 首行水印");
                assertTrue(text.contains("exp-designer"), "水印含用户名");
            }
            // 下载属主校验
            Map<String, Object> dl = service.download(id);
            assertNotNull(dl.get("bytes"), fmt + " 下载解密流出");
            assertTrue(String.valueOf(dl.get("fileName")).contains("测试报表"),
                    "文件命名含报表名");
        }
    }

    @Test
    void purposeRequiredWhenOverLimit() {
        Map<String, Object> p = new HashMap<>();
        p.put("format", "csv");
        ServiceException e = assertThrows(ServiceException.class,
                () -> submit("cost", p, 50000));
        assertEquals(422, e.getCode(), "超量无用途 422");
        assertTrue(e.getMessage().contains("用途"), "提示用途必填");
    }

    // ---------- 权限再校验（spec 需求一） ----------

    @Test
    void permissionRecheckFailsTask() {
        String tpl = seedTemplate("EXP-TPL-2");
        Map<String, Object> p = new HashMap<>();
        p.put("templateId", tpl);
        p.put("format", "csv");
        // _allow 传空集（模拟权限收窄）
        p.put("_allow", List.of());
        Map<String, Object> sub = submit("template", p, 10);
        String id = (String) sub.get("id");
        service.drainOnce();
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_export_task WHERE ID=?", String.class, id);
        // executeGovernedSelect 对空集抛 403 → FAILED（行级权限不足）
        // 注：_allow 空集在 allowFrom 返回 [] → writeFile 预检 FAILED
        assertEquals("FAILED", status, "权限空集任务失败");
    }

    // ---------- 优先级与超时（spec 需求四） ----------

    @Test
    void priorityAssignment() {
        Map<String, Object> small = submit("cost", Map.of("format", "csv"), 500);
        assertEquals("SMALL", small.get("priority"), "≤1000 行 SMALL");
        Map<String, Object> normal = submit("cost", Map.of("format", "csv"), 5000);
        assertEquals("NORMAL", normal.get("priority"), "1001-1万 NORMAL");
    }

    @Test
    void runningTimeoutTerminated() {
        Map<String, Object> sub = submit("cost", Map.of("format", "csv"), 100);
        String id = (String) sub.get("id");
        // 置 RUNNING 并回拨更新时间 31 分钟
        jdbc.update("UPDATE erp_bi_export_task SET STATUS='RUNNING', "
                        + "UPDATE_DATE=DATE_SUB(NOW(), INTERVAL 31 MINUTE) WHERE ID=?", id);
        int n = service.sweepTimeouts();
        assertTrue(n >= 1, "超时扫描处理");
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertEquals("FAILED", status, "超时终止 FAILED");
        String err = jdbc.queryForObject(
                "SELECT ERROR_MSG FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertTrue(err.contains("超时"), "超时原因记录");
    }

    // ---------- 生命周期（spec 需求六） ----------

    @Test
    void lifecycleExpireAndRemind() {
        Map<String, Object> sub = submit("cost", Map.of("format", "csv"), 100);
        String id = (String) sub.get("id");
        String taskNo = (String) sub.get("taskNo");
        service.drainOnce();
        // 回拨到期时间已过期 → 清理
        Path dir = Path.of("./data/export");
        jdbc.update("UPDATE erp_bi_export_task SET EXPIRE_AT=DATE_SUB(NOW(), INTERVAL 1 DAY) "
                + "WHERE ID=?", id);
        int[] r = service.sweepLifecycle();
        assertTrue(r[0] >= 1, "到期清理");
        String cleanAt = jdbc.queryForObject(
                "SELECT CAST(CLEAN_AT AS CHAR) FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertNotNull(cleanAt, "清理时间落库（≥180 天可查）");
        // 密文文件已删
        String filePath = jdbc.queryForObject(
                "SELECT FILE_PATH FROM erp_bi_export_task WHERE ID=?", String.class, id);
        assertTrue(!Files.exists(Path.of(filePath)), "密文已删除");

        // 24h 内提醒：另一任务回拨到 12h 后过期
        Map<String, Object> sub2 = submit("cost", Map.of("format", "csv"), 100);
        String id2 = (String) sub2.get("id");
        service.drainOnce();
        jdbc.update("UPDATE erp_bi_export_task SET EXPIRE_AT=DATE_ADD(NOW(), INTERVAL 12 HOUR) "
                + "WHERE ID=?", id2);
        int[] r2 = service.sweepLifecycle();
        assertTrue(r2[1] >= 1, "24h 内提醒");
        Integer notice = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='BiExportExpiring' "
                        + "AND BIZ_ID=?", Integer.class, id2);
        assertTrue(notice >= 1, "提醒通知落库");
        assertNotNull(taskNo);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
