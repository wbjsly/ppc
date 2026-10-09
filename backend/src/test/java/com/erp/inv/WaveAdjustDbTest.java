package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvWaveAdjust;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.WaveService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 波次改批审批 DB 集成（真实 MySQL，spec wave-management C-4.4-08，任务 4.3）：
 * 行级 LOCKED 冻结、审批通过后值生效+回写、驳回保原值、
 * 同人防闭环两道（提交无他人 422 / 发起人自签 422，C-0-03）。
 */
@SpringBootTest
class WaveAdjustDbTest {

    private static final String ITEM = "IT-WAJ-01";

    @Autowired
    private WaveService waveService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    private String waveId;
    private String lineId;

    @BeforeEach
    void setUp() {
        purge();
        // 发起人 tester（不在用户表 → 排除后剩 wh-smoke，第一道通过）
        as("tester", "ROLE_WAREHOUSE");

        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, "
                + "BASE_UNIT, MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('waj-it', ?, '改批测试物料', '0001', 'PC', "
                + "'STRUCT', 'BUY', 'NORMAL', '1', '1', 'junit')", ITEM);
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, STATUS, CREATE_BY) VALUES "
                + "('waj-lg1','WAJ-B1',?, '改批测试物料','2026-08-01','2028-08-01','0','1',"
                + "'junit'), ('waj-lg2','WAJ-B2',?, '改批测试物料','2026-09-01','2028-09-01',"
                + "'0','1','junit'), ('waj-lg3','WAJ-BLOCKED',?, '改批测试物料','2026-01-01',"
                + "'2026-06-01','1','1','junit')", ITEM, ITEM, ITEM);

        jdbc.update("INSERT INTO erp_inv_wave (ID, WAVE_NO, CLUSTER_TYPE, CLUSTER_KEY, STATUS, "
                + "DOC_COUNT, CREATE_BY) VALUES ('waj-wave','WVAJ0001','CARRIER','顺丰',"
                + "'CREATED',1,'junit')");
        waveId = "waj-wave";
        jdbc.update("INSERT INTO erp_inv_wave_line (ID, WAVE_ID, SHIP_ID, SHIP_LINE_ID, "
                + "LINE_NO, ITEM_CODE, ITEM_NAME, WAREHOUSE_CODE, BATCH_NO, BIN_CODE, QTY, "
                + "LOCK_FLAG, CREATE_BY) VALUES ('waj-ln1','waj-wave','waj-sh1','waj-sol1',1,"
                + "?, '改批测试物料','WH-MAIN','WAJ-B1','BIN-A01',30,'0','junit')", ITEM);
        lineId = "waj-ln1";
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void approveAppliesNewValueAndUnlocks() {
        Map<String, Object> res = waveService.adjust(waveId, lineId, "BATCH", "WAJ-B2",
                "先进批次优先策略调整");
        // 行 LOCKED + 调整记录 PENDING
        String lock = jdbc.queryForObject("SELECT LOCK_FLAG FROM erp_inv_wave_line WHERE ID = ?",
                String.class, lineId);
        assertEquals("1", lock, "提交后行冻结（C-4.4-08）");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave_adjust WHERE ID = ?",
                String.class, res.get("adjustId"));
        assertEquals(InvWaveAdjust.ST_PENDING, st);

        // 重复提交 422
        ServiceException dup = assertThrows(ServiceException.class,
                () -> waveService.adjust(waveId, lineId, "BATCH", "WAJ-B2", "重复"));
        assertEquals(422, dup.getCode());

        // 由另一身份（wh-smoke，ROLE_WAREHOUSE）签署通过
        String taskId = activeTaskId(res);
        as("wh-smoke", "ROLE_WAREHOUSE");
        approvalEngine.pass(taskId, "同意改批");

        // 后值生效 + 解锁 + 记录 APPROVED
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT BATCH_NO, LOCK_FLAG FROM erp_inv_wave_line WHERE ID = ?", lineId);
        assertEquals("WAJ-B2", line.get("BATCH_NO"), "审批通过 → 后值生效");
        assertEquals("0", line.get("LOCK_FLAG"), "解锁");
        String adjSt = jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave_adjust WHERE ID = ?",
                String.class, res.get("adjustId"));
        assertEquals(InvWaveAdjust.ST_APPROVED, adjSt, "调整记录留痕 APPROVED");
        // 前值/原因留痕
        Map<String, Object> adj = jdbc.queryForMap(
                "SELECT OLD_VALUE, REASON FROM erp_inv_wave_adjust WHERE ID = ?",
                res.get("adjustId"));
        assertEquals("WAJ-B1", adj.get("OLD_VALUE"), "前值留痕");
        assertTrue(String.valueOf(adj.get("REASON")).contains("策略"), "原因留痕");
    }

    @Test
    void rejectKeepsOriginalValue() {
        Map<String, Object> res = waveService.adjust(waveId, lineId, "BATCH", "WAJ-B2",
                "改回旧批次测试");
        String taskId = activeTaskId(res);
        as("wh-smoke", "ROLE_WAREHOUSE");
        approvalEngine.reject(taskId, "不同意该调整");

        Map<String, Object> line = jdbc.queryForMap(
                "SELECT BATCH_NO, LOCK_FLAG FROM erp_inv_wave_line WHERE ID = ?", lineId);
        assertEquals("WAJ-B1", line.get("BATCH_NO"), "驳回保持原推荐值");
        assertEquals("0", line.get("LOCK_FLAG"), "驳回解锁");
        String adjSt = jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave_adjust WHERE ID = ?",
                String.class, res.get("adjustId"));
        assertEquals(InvWaveAdjust.ST_REJECTED, adjSt);
    }

    @Test
    void selfSignBlockedTwoGuards() {
        // 第一道：wh-smoke（角色唯一真实成员）发起 → 排除自己后无他人 → 422
        as("wh-smoke", "ROLE_WAREHOUSE");
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> waveService.adjust(waveId, lineId, "BATCH", "WAJ-B2", "同人第一道"));
        assertEquals(422, ex1.getCode());
        assertTrue(String.valueOf(ex1.getMessage()).contains("同人"), "第一道提示禁同人闭环");

        // 第二道：tester 发起（第一道过：角色有 wh-smoke 他人），tester 自签 → 422
        as("tester", "ROLE_WAREHOUSE");
        Map<String, Object> res = waveService.adjust(waveId, lineId, "BATCH", "WAJ-B2",
                "同人第二道");
        String taskId = activeTaskId(res);
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> approvalEngine.pass(taskId, "自己签自己"));
        assertEquals(422, ex2.getCode());
        assertTrue(String.valueOf(ex2.getMessage()).contains("同人闭环"), "第二道拦截自签");
        // 他人可签（回到通过路径验证拦截不误伤）
        as("wh-smoke", "ROLE_WAREHOUSE");
        approvalEngine.pass(taskId, "另一名主管同意");
        assertEquals("WAJ-B2", jdbc.queryForObject(
                "SELECT BATCH_NO FROM erp_inv_wave_line WHERE ID = ?", String.class, lineId));
    }

    @Test
    void blockedBatchRejectedByValidation() {
        // 效期锁定批次不可改入
        ServiceException ex = assertThrows(ServiceException.class,
                () -> waveService.adjust(waveId, lineId, "BATCH", "WAJ-BLOCKED", "改入锁定批"));
        assertEquals(422, ex.getCode());
        // 不存在批次
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> waveService.adjust(waveId, lineId, "BATCH", "WAJ-NOPE", "改入不存在"));
        assertEquals(422, ex2.getCode());
        // 空原因
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> waveService.adjust(waveId, lineId, "BATCH", "WAJ-B2", " "));
        assertEquals(422, ex3.getCode());
        // 未锁行
        String lock = jdbc.queryForObject("SELECT LOCK_FLAG FROM erp_inv_wave_line WHERE ID = ?",
                String.class, lineId);
        assertEquals("0", lock, "校验失败不锁行");
    }

    // ---------- 工具 ----------

    private String apprIdOf(Map<String, Object> res) {
        return jdbc.queryForObject("SELECT APPR_ID FROM erp_inv_wave_adjust WHERE ID = ?",
                String.class, res.get("adjustId"));
    }

    private String activeTaskId(Map<String, Object> res) {
        return jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID = ? AND STATUS = 'ACTIVE'",
                String.class, apprIdOf(res));
    }

    private void as(String user, String role) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, role));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE = 'WaveAdjust')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'WaveAdjust'");
        jdbc.update("DELETE FROM erp_inv_wave_adjust WHERE WAVE_ID LIKE 'waj-%'");
        jdbc.update("DELETE FROM erp_inv_wave_line WHERE WAVE_ID LIKE 'waj-%'");
        jdbc.update("DELETE FROM erp_inv_wave WHERE ID LIKE 'waj-%'");
        jdbc.update("DELETE FROM erp_inv_batch WHERE ID LIKE 'waj-%'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ID = 'waj-it'");
    }
}
