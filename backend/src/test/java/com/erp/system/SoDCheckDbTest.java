package com.erp.system;

import com.erp.common.ServiceException;
import com.erp.service.SoDCheckService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SoD 预检（add-permission-approval-flow spec：合规性预检与 SoD 互斥）：
 * 冲突判定（目标×目标 / 目标×现有）、规则 CRUD 与规范化、超基线判定复用岗位基线。
 */
@SpringBootTest
class SoDCheckDbTest {

    @Autowired
    private SoDCheckService service;
    @Autowired
    private JdbcTemplate jdbc;

    private String u1;
    private String u2;

    @BeforeEach
    void setUp() {
        purge();
        u1 = seedUser("sod-u1");
        u2 = seedUser("sod-u2");
        // u1 绑定 ROLE_PURCHASER（现有权限侧冲突源）
        String purId = jdbc.queryForObject(
                "SELECT ID FROM erp_admin_role WHERE ROLE_CODE='ROLE_PURCHASER'", String.class);
        jdbc.update("INSERT INTO erp_admin_user_role (ID,USER_ID,ROLE_ID) VALUES (?,?,?)",
                "sod-ur-" + UUID.randomUUID().toString().substring(0, 8), u1, purId);
        // u2 有岗位基线 [ROLE_PLANNER]
        String posId = "sod-pos-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO hr_employee (ID,EMP_NO,NAME,ID_CARD_NO,EMP_STATUS,HIRE_DATE,"
                        + "USER_ID,POS_ID,ATTEND_SCHEME,CREATE_BY,DEL_FLAG,VER_NO) "
                        + "VALUES (?,?,?,'SODID-','在职',CURDATE(),?,?,'STD','sod-test','0',0)",
                "sod-emp-" + UUID.randomUUID().toString().substring(0, 8),
                "SOD" + UUID.randomUUID().toString().substring(0, 6),
                "SoD测试员工", u2, posId);
        jdbc.update("INSERT INTO erp_admin_position_baseline (ID,POS_ID,ROLE_CODES,CREATE_BY,"
                        + "CREATE_DATE,DEL_FLAG,VER_NO) VALUES (?,?,?,'sod-test',NOW(),'0',0)",
                "sod-bl-" + UUID.randomUUID().toString().substring(0, 8), posId, "[\"ROLE_PLANNER\"]");
    }

    @AfterEach
    void tearDown() {
        purge();
    }

    private void purge() {
        // 测试只允许种子规则存在（防失败残留污染后续断言）
        jdbc.execute("DELETE FROM erp_admin_sod_rule WHERE ID NOT LIKE 'sod-seed-%'");
        jdbc.execute("DELETE FROM erp_admin_user_role WHERE USER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_admin_user WHERE USERNAME LIKE 'sod-u%') t)");
        jdbc.execute("DELETE FROM erp_admin_position_baseline WHERE POS_ID LIKE 'sod-pos-%'");
        jdbc.execute("DELETE FROM hr_employee WHERE EMP_NO LIKE 'SOD%' OR ID LIKE 'sod-emp-%'");
        jdbc.execute("DELETE FROM erp_admin_user WHERE USERNAME LIKE 'sod-u%'");
    }

    private String seedUser(String name) {
        String id = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO erp_admin_user (ID,USERNAME,PASSWORD,NICK_NAME,STATUS,CREATE_BY,"
                        + "DEL_FLAG,VER_NO) VALUES (?,?,?,?,'1','sod-test','0',0)",
                id, name, "{noop}", "SoD测试用户");
        return id;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conflicts(Map<String, Object> result) {
        return (List<Map<String, Object>>) result.get("conflicts");
    }

    @Test
    void targetPairConflictDetected() {
        // 种子对：采购创建 ↔ 采购审批（目标×目标）
        Map<String, Object> r = service.check(null, List.of("ROLE_PURCHASER", "ROLE_PM"));
        List<Map<String, Object>> cs = conflicts(r);
        assertEquals(1, cs.size(), "目标内互斥对应命中：" + cs);
        assertTrue(String.valueOf(cs.get(0).get("remark")).contains("采购"));
    }

    @Test
    void targetVsExistingConflictDetected() {
        // u1 现有 PURCHASER，申请 PM → 目标×现有命中
        Map<String, Object> r = service.check(u1, List.of("ROLE_PM"));
        assertEquals(1, conflicts(r).size(), "目标×现有互斥应命中");
        List<String> existing = (List<String>) r.get("existing");
        assertTrue(existing.contains("ROLE_PURCHASER"));
    }

    @Test
    void ruleCrudWithNormalization() {
        // 规范化重复（顺序颠倒）→ 422
        ServiceException dup = assertThrows(ServiceException.class,
                () -> service.addRule("ROLE_PM", "ROLE_PURCHASER", "重复种子对"));
        assertEquals(422, dup.getCode());
        // 自互斥 → 422
        ServiceException self = assertThrows(ServiceException.class,
                () -> service.addRule("ROLE_PM", "ROLE_pm", "自互斥"));
        assertEquals(422, self.getCode());

        // 新增（逆序输入，规范化落库）
        service.addRule("ROLE_CS", "ROLE_SALES", "客服与销售客户数据互斥");
        Map<String, Object> r = service.check(null, List.of("ROLE_SALES", "ROLE_CS"));
        assertEquals(1, conflicts(r).size(), "新增规则应立即参与判定");

        // 删除新规则 → 该对不再命中（SALES/CS 不在种子三对中）
        Map<String, Object> rule = jdbc.queryForMap(
                "SELECT ID FROM erp_admin_sod_rule WHERE ROLE_A='ROLE_CS' AND ROLE_B='ROLE_SALES'");
        service.deleteRule(String.valueOf(rule.get("ID")));
        Map<String, Object> r2 = service.check(null, List.of("ROLE_SALES", "ROLE_CS"));
        assertEquals(0, conflicts(r2).size(), "删除规则后该对不再命中");

        // 删除不存在 → 404
        ServiceException nf = assertThrows(ServiceException.class,
                () -> service.deleteRule("no-such-rule"));
        assertEquals(404, nf.getCode());
    }

    @Test
    void beyondBaselineFromPositionBaseline() {
        // u2 基线 [PLANNER]，申请 [PLANNER, PM] → beyond=[PM]，baseline 含 PLANNER
        Map<String, Object> r = service.check(u2, List.of("ROLE_PLANNER", "ROLE_PM"));
        List<String> baseline = (List<String>) r.get("baseline");
        List<String> beyond = (List<String>) r.get("beyondBaseline");
        assertTrue(baseline.contains("ROLE_PLANNER"));
        assertEquals(List.of("ROLE_PM"), beyond, "超基线 = 目标 − 岗位基线");
    }
}
