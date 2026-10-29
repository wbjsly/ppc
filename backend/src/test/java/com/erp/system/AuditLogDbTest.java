package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.security.AuditContext;
import com.erp.security.AuditLogWriter;
import com.erp.service.AuditLogService;
import com.erp.service.ForensicService;
import com.erp.service.MaskRuleService;
import com.erp.service.MaskService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审计日志全链（add-audit-log-console spec：BR-5.6-13/14、S-5.6-07）：
 * 脱敏规则三态/组合检索掩码/触发器拒绝留痕/豁免放行留痕/门面写入带 IP/
 * 取证双签顺序与自签 403/生成包封条/下载校验三重与封条比对/过期 sweep 幂等。
 */
@SpringBootTest
class AuditLogDbTest {

    @Autowired
    private MaskService maskService;
    @Autowired
    private MaskRuleService maskRuleService;
    @Autowired
    private AuditLogService auditLogService;
    @Autowired
    private ForensicService forensicService;
    @Autowired
    private AuditLogWriter auditLogWriter;
    @Autowired
    private JdbcTemplate jdbc;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String NOW = LocalDateTime.now().format(TS);
    private static final String PAST = LocalDateTime.now().minusDays(1).format(TS);
    private static final String FUTURE = LocalDateTime.now().plusDays(1).format(TS);

    private String applicant;
    private String auditor;
    private String dpo;

    @BeforeEach
    void setUp() {
        purge();
        applicant = seedUser("ald-app");
        auditor = seedUser("ald-aud");
        dpo = seedUser("ald-dpo");
        grantRole(auditor, "ROLE_UAR_AUDITOR");
        grantRole(dpo, "ROLE_DPO");
        // 造审计行：手机号 + 身份证（供脱敏断言）
        insertLog("ald-tgt", "EMP_STATUS", "在职", "13812345678", "身份证 310101199001011234",
                "smoke", PAST);
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
        AuditContext.clear();
    }

    private void purge() {
        jdbc.execute("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE EMP_ID LIKE 'ald-%' "
                + "OR FIELD_NAME LIKE 'FORENSIC%'; SET @audit_bypass=0");
        jdbc.execute("DELETE FROM erp_ops_forensic_package");
        jdbc.execute("DELETE FROM erp_ops_forensic_request WHERE ID LIKE 'ald-%' OR APPLY_BY IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'ald-%') t)");
        jdbc.execute("DELETE FROM erp_ops_audit_tamper");
        jdbc.execute("DELETE FROM erp_sys_notice WHERE BIZ_TYPE='AuditTamper'");
        jdbc.execute("DELETE ur FROM erp_admin_user_role ur JOIN erp_admin_user u ON u.ID=ur.USER_ID "
                + "WHERE u.USERNAME LIKE 'ald-%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'ald-%'");
    }

    private String seedUser(String name) {
        String id = uuid();
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','ald-test','0',0)",
                id, name, "{noop}", "审计测试");
        return id;
    }

    private void grantRole(String userId, String roleCode) {
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) "
                + "SELECT ?,?,ID FROM erp_admin_role WHERE ROLE_CODE=?",
                uuid(), userId, roleCode);
    }

    private void insertLog(String empId, String field, String oldV, String newV, String reason,
                           String by, String at) {
        jdbc.update("INSERT INTO hr_change_log (ID,EMP_ID,FIELD_NAME,OLD_VALUE,NEW_VALUE,REASON,"
                        + "CHANGE_BY,CHANGE_AT) VALUES (?,?,?,?,?,?,?,?)",
                uuid(), empId, field, oldV, newV, reason, by, LocalDateTime.parse(at, TS));
    }

    private void login(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    // ---------------- 2.1 脱敏规则 ----------------

    @Test
    void seedRulesMaskCorrectlyAndToggle() {
        // 种子三规则掩码正确（掩码列：OLD/NEW/REASON）
        assertEquals("138****5678", maskService.applyAll("13812345678"));
        assertEquals("310101********1234", maskService.applyAll("310101199001011234"));
        // 停用不掩码：临时停用 MOBILE
        String mobileId = jdbc.queryForObject(
                "SELECT ID FROM erp_ops_mask_rule WHERE CODE='MOBILE'", String.class);
        maskRuleService.save(mobileId, "MOBILE", "手机号", "(1[3-9]\\d)\\d{4}(\\d{4})",
                "$1****$2", "0", null);
        assertEquals("13812345678", maskService.applyAll("13812345678"));
        // 重新启用恢复
        maskRuleService.save(mobileId, "MOBILE", "手机号", "(1[3-9]\\d)\\d{4}(\\d{4})",
                "$1****$2", "1", null);
        assertEquals("138****5678", maskService.applyAll("13812345678"));
        // 新规则即刻生效（不重启）
        maskRuleService.save(null, "ALD_TEST", "测试规则", "(ALD\\d{4})", "T**T", "1", null);
        assertEquals("T**T", maskService.applyAll("ALD1234"));
        // 非法正则 422
        assertThrows(ServiceException.class, () -> maskRuleService.save(
                null, "ALD_BAD", "坏规则", "([", "x", "1", null));
        // 捕获组越界 422
        assertThrows(ServiceException.class, () -> maskRuleService.save(
                null, "ALD_BAD2", "坏模板", "(\\d{4})", "$5", "1", null));
        // 清理测试规则
        jdbc.update("DELETE FROM erp_ops_mask_rule WHERE CODE='ALD_TEST'");
        maskService.evictCache();
    }

    // ---------------- 2.2 组合检索 + 脱敏 ----------------

    @Test
    void searchCombinedFiltersAndMasks() {
        Map<String, Object> r = auditLogService.search(null, null, "ald-tgt", null, null, null, 1, 50);
        assertEquals(1, ((Number) r.get("total")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("rows");
        // NEW_VALUE 手机号已掩码；REASON 含身份证已掩码
        assertEquals("138****5678", rows.get(0).get("newValue"));
        assertTrue(String.valueOf(rows.get(0).get("reason")).contains("310101********1234"));
        // 时间范围过滤：过去范围命中，未来范围不命中
        Map<String, Object> hit = auditLogService.search(null, null, "ald-tgt", null,
                PAST, FUTURE, 1, 50);
        assertEquals(1, ((Number) hit.get("total")).intValue());
        Map<String, Object> miss = auditLogService.search(null, null, "ald-tgt", null,
                FUTURE, null, 1, 50);
        assertEquals(0, ((Number) miss.get("total")).intValue());
        // 关键词组合
        Map<String, Object> kw = auditLogService.search("smoke", "EMP_STATUS", "ald-tgt",
                "138", null, null, 1, 50);
        assertEquals(1, ((Number) kw.get("total")).intValue());
    }

    // ---------------- 3.1 触发器拒绝/豁免 + 告警联动 ----------------

    @Test
    void triggerRejectsWithTamperRecordAndBypassStaysLogged() {
        String id = jdbc.queryForObject(
                "SELECT ID FROM hr_change_log WHERE EMP_ID='ald-tgt' LIMIT 1", String.class);
        // 直连 UPDATE 被拒 + 尝试表留痕
        assertThrows(Exception.class, () ->
                jdbc.update("UPDATE hr_change_log SET NEW_VALUE='x' WHERE ID=?", id));
        Integer upd = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_audit_tamper WHERE ACTION_TYPE='UPDATE' AND BYPASS='0'",
                Integer.class);
        assertTrue(upd >= 1, "UPDATE 尝试应留痕");
        // 直连 DELETE 被拒 + 留痕
        assertThrows(Exception.class, () ->
                jdbc.update("DELETE FROM hr_change_log WHERE ID=?", id));
        Integer del = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_audit_tamper WHERE ACTION_TYPE='DELETE' AND BYPASS='0'",
                Integer.class);
        assertTrue(del >= 1, "DELETE 尝试应留痕");
        // 豁免放行仍留痕（BYPASS=1）
        jdbc.update("SET @audit_bypass=1; DELETE FROM hr_change_log WHERE ID=?; SET @audit_bypass=0", id);
        Integer gone = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE ID=?", Integer.class, id);
        assertEquals(0, gone, "豁免放行应删除成功");
        Integer bypass = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_ops_audit_tamper WHERE ACTION_TYPE='DELETE' AND BYPASS='1'",
                Integer.class);
        assertTrue(bypass >= 1, "豁免放行仍留痕");
        // 告警联动：sweep → 内审角色收到站内通知（幂等）
        login(auditor);
        Map<String, Object> sw1 = auditLogService.sweepTamperNotices();
        assertTrue(((Number) sw1.get("notified")).intValue() >= 1, "首次 sweep 应通知");
        Map<String, Object> sw2 = auditLogService.sweepTamperNotices();
        assertEquals(0, ((Number) sw2.get("fresh")).intValue(), "幂等：无新增尝试行");
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='AuditTamper'", Integer.class);
        assertTrue(notices >= 1, "通知落库");
        // 尝试记录列表可读
        List<Map<String, Object>> tamper = auditLogService.tamperList(100);
        assertTrue(tamper.size() >= 3);
    }

    // ---------------- 3.2 门面写入带 IP ----------------

    @Test
    void writerFacadeCarriesSourceIp() {
        AuditContext.set("10.9.8.7", "JUnit-Agent/1.0");
        login(applicant);
        auditLogWriter.write("ald-tgt", "ALD_FACADE", "a", "b", "门面写入探针");
        String ip = jdbc.queryForObject(
                "SELECT SOURCE_IP FROM hr_change_log WHERE FIELD_NAME='ALD_FACADE'", String.class);
        String terminal = jdbc.queryForObject(
                "SELECT TERMINAL FROM hr_change_log WHERE FIELD_NAME='ALD_FACADE'", String.class);
        assertEquals("10.9.8.7", ip);
        assertEquals("JUnit-Agent/1.0", terminal);
        // 上下文外写入为空
        AuditContext.clear();
        auditLogWriter.write("ald-tgt", "ALD_FACADE2", "a", "b", "无上下文");
        String ip2 = jdbc.queryForObject(
                "SELECT SOURCE_IP FROM hr_change_log WHERE FIELD_NAME='ALD_FACADE2'", String.class);
        assertEquals(null, ip2);
    }

    // ---------------- 4.1 双签顺序 / 自签 / 重复签 ----------------

    @Test
    void dualSignOrderSelfSignAndRepeat() {
        login(applicant);
        String reqId = String.valueOf(forensicService.apply(
                "安全事件取证调查需要调阅相关操作日志", PAST, FUTURE, null, null).get("id"));
        // DPO 先签 → 422（内审未签）
        login(dpo);
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> forensicService.signDpo(reqId, "先签测试"));
        assertEquals(422, e1.getCode());
        // 内审自签（申请人= auditor？不，申请人为 applicant，auditor 非申请人）→ 正常签
        login(auditor);
        forensicService.signAudit(reqId, "内审核验通过");
        // 内审重复签 422
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> forensicService.signAudit(reqId, "重复"));
        assertEquals(422, e2.getCode());
        // DPO 后签成功
        login(dpo);
        forensicService.signDpo(reqId, "DPO 核验通过");
        // 非签署角色 403
        login(applicant);
        String req2 = String.valueOf(forensicService.apply(
                "第二笔调阅申请用于验证角色校验", PAST, FUTURE, null, null).get("id"));
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> forensicService.signAudit(req2, "无角色"));
        assertEquals(403, e3.getCode());
        // 自签 403：申请人持内审角色签署自己的申请
        grantRole(applicant, "ROLE_UAR_AUDITOR");
        ServiceException e4 = assertThrows(ServiceException.class,
                () -> forensicService.signAudit(req2, "自签"));
        assertEquals(403, e4.getCode());
    }

    // ---------------- 4.2 生成包 / 下载三重校验 / 封条 / sweep ----------------

    @Test
    void packageSealDownloadAndSweep() {
        // 双签齐备
        login(applicant);
        String reqId = String.valueOf(forensicService.apply(
                "封条校验场景的取证调阅申请", PAST, FUTURE, null, "ald-").get("id"));
        login(auditor);
        forensicService.signAudit(reqId, "同意");
        login(dpo);
        forensicService.signDpo(reqId, "同意");
        // 未双签生成 422：新申请直接生成
        login(applicant);
        String req2 = String.valueOf(forensicService.apply(
                "未双签直接生成的场景申请", PAST, FUTURE, null, null).get("id"));
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> forensicService.generatePackage(req2));
        assertEquals(422, e1.getCode());
        // 生成包（封条预计算）
        login(auditor);
        Map<String, Object> pkg = forensicService.generatePackage(reqId);
        String pkgId = String.valueOf(pkg.get("packageId"));
        assertTrue(((Number) pkg.get("totalRows")).intValue() >= 1, "总条数 ≥1");
        assertEquals(1, ((Number) pkg.get("shardCount")).intValue(), "小数据集 1 片");
        // 下载成功（封条比对一致）
        Map<String, Object> dl = forensicService.download(pkgId, 0);
        assertNotNull(dl.get("sha256"));
        assertTrue(((Number) dl.get("rows")).intValue() >= 1);
        String csv = new String((byte[]) dl.get("content"), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("138****5678"), "下载 CSV 含脱敏掩码");
        // 下载自身留痕
        Integer dlAudit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM hr_change_log WHERE FIELD_NAME='FORENSIC_DOWNLOAD'",
                Integer.class);
        assertTrue(dlAudit >= 1, "下载动作留痕");
        // 不存在分片 404
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> forensicService.download(pkgId, 9));
        assertEquals(404, e2.getCode());
        // 封条失效：篡改源数据后下载被拒 500
        String logId = jdbc.queryForObject(
                "SELECT ID FROM hr_change_log WHERE EMP_ID='ald-tgt' LIMIT 1", String.class);
        jdbc.update("SET @audit_bypass=1; UPDATE hr_change_log SET NEW_VALUE='tampered' "
                + "WHERE ID=?; SET @audit_bypass=0", logId);
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> forensicService.download(pkgId, 0));
        assertEquals(500, e3.getCode(), "数据变更=封条失效，拒绝下载");
        // 使用完毕确认（提前销毁，幂等）
        forensicService.confirmUsed(pkgId);
        forensicService.confirmUsed(pkgId);
        String st = jdbc.queryForObject(
                "SELECT STATUS FROM erp_ops_forensic_package WHERE ID=?", String.class, pkgId);
        assertEquals("DESTROYED", st);
        // 销毁后下载拒 422
        ServiceException e4 = assertThrows(ServiceException.class,
                () -> forensicService.download(pkgId, 0));
        assertEquals(422, e4.getCode());
        // 过期 sweep 幂等：再造一个包并回拨过期（申请人为非签署人，防自签 403）
        login(applicant);
        String req3 = String.valueOf(forensicService.apply(
                "过期销毁场景的取证调阅申请", PAST, FUTURE, null, null).get("id"));
        login(auditor);
        forensicService.signAudit(req3, "同意");
        login(dpo);
        forensicService.signDpo(req3, "同意");
        String pkg3 = String.valueOf(forensicService.generatePackage(req3).get("packageId"));
        jdbc.update("UPDATE erp_ops_forensic_package SET EXPIRE_AT=DATE_SUB(NOW(), INTERVAL 1 HOUR) "
                + "WHERE ID=?", pkg3);
        Map<String, Object> sw1 = forensicService.sweepExpired();
        assertTrue(((Number) sw1.get("destroyed")).intValue() >= 1, "过期销毁");
        Map<String, Object> sw2 = forensicService.sweepExpired();
        assertEquals(0, ((Number) sw2.get("destroyed")).intValue(), "sweep 幂等");
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
