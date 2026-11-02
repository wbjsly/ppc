package com.erp.iot;

import com.erp.common.ServiceException;
import com.erp.service.iot.IotOfflineSyncService;
import com.erp.service.iot.IotTerminalService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13.5 离线同步库测（add-iot-mobile，FR-4.11-4-x / C-4.11-04/12 / BL-4.11-01）：
 * 分批逐条反馈 / 幂等去重 / 失败保留 / 单批 100 上限 / 数量合并冲突 /
 * 财务 L2 阻断 / 字典序裁决 / 72h 锁定 / 缓存清理。
 */
@SpringBootTest
class IotOfflineDbTest {

    @Autowired
    private IotOfflineSyncService service;
    @Autowired
    private IotTerminalService terminalService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String P = "iot-off-";
    private String deviceCode;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("user-admin", null, List.of(
                        new SimpleGrantedAuthority("ROLE_SHOP_FLOOR"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));
        purge();
        deviceCode = P + "PDA-" + UUID.randomUUID().toString().substring(0, 8);
        terminalService.register(Map.of("deviceCode", deviceCode, "deviceType", "PDA"));
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_iot_sync_conflict WHERE QUEUE_ID IN " +
                "(SELECT ID FROM erp_iot_offline_queue WHERE DEVICE_CODE LIKE ?)", P + "%");
        jdbc.update("DELETE FROM erp_iot_offline_queue WHERE DEVICE_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_terminal WHERE DEVICE_CODE LIKE ?", P + "%");
        jdbc.update("DELETE FROM erp_iot_exception WHERE DEVICE_NO LIKE ?", P + "%");
    }

    private Map<String, Object> rec(String key, String bizType, String bizKey,
                                    LocalDateTime clientTs, int ver, String payload) {
        Map<String, Object> m = new HashMap<>();
        m.put("idempotentKey", key);
        m.put("bizType", bizType);
        m.put("bizKey", bizKey);
        m.put("deviceCode", deviceCode);
        m.put("clientTs", clientTs.toString());
        m.put("clientVerNo", ver);
        m.put("payload", payload);
        return m;
    }

    // ---------- 5.1 批量上传 ----------

    @Test
    void batchFeedbackPerRecord() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> batch = List.of(
                rec(P + "K1", "SCAN", null, now, 0, "{\"n\":1}"),
                rec(P + "K2", "SCAN", null, now, 0, "{\"n\":2}"),
                rec(P + "K3", "SCAN", null, now, 0, "{\"n\":3}"));
        Map<String, Object> out = service.uploadBatch(batch);
        assertEquals(3, out.get("total"));
        assertEquals(3, out.get("synced"), "三条全部过账成功");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(r -> "SYNCED".equals(r.get("result"))),
                "逐条反馈状态");
        // 中断保留：SYNCED 记录在队列
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_offline_queue WHERE DEVICE_CODE=? AND STATUS='SYNCED'",
                Integer.class, deviceCode);
        assertEquals(3, cnt);
    }

    @Test
    void idempotentDuplicateDedups() {
        LocalDateTime now = LocalDateTime.now();
        service.uploadBatch(List.of(rec(P + "K1", "SCAN", null, now, 0, "{}")));
        Map<String, Object> again = service.uploadBatch(
                List.of(rec(P + "K1", "SCAN", null, now, 0, "{}")));
        assertEquals(1, again.get("duplicated"), "同幂等键第二次 DUPLICATE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) again.get("results");
        assertEquals("DUPLICATE", results.get(0).get("result"));
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_offline_queue WHERE IDEMPOTENT_KEY=?",
                Integer.class, P + "K1");
        assertEquals(1, cnt, "队列仅一条");
    }

    @Test
    void failedRecordKeptInQueue() {
        // EXCEPTION 载荷非法类型 → 业务 422 → FAILED 保留（同步中断语义）
        Map<String, Object> out = service.uploadBatch(List.of(
                rec(P + "K-FAIL", "EXCEPTION", null, LocalDateTime.now(), 0,
                        "{\"excType\":\"BOGUS\"}")));
        assertEquals(1, out.get("failed"));
        String status = jdbc.queryForObject(
                "SELECT STATUS FROM erp_iot_offline_queue WHERE IDEMPOTENT_KEY=?",
                String.class, P + "K-FAIL");
        assertEquals("FAILED", status, "失败记录保留在队列");
        String errMsg = jdbc.queryForObject(
                "SELECT ERR_MSG FROM erp_iot_offline_queue WHERE IDEMPOTENT_KEY=?",
                String.class, P + "K-FAIL");
        assertNotNull(errMsg, "失败原因留痕");
    }

    @Test
    void batchOverHundredRejected() {
        List<Map<String, Object>> big = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            big.add(rec(P + "BIG" + i, "SCAN", null, LocalDateTime.now(), 0, "{}"));
        }
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.uploadBatch(big));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("100"));
    }

    // ---------- 5.2 三重冲突与字典序裁决 ----------

    @Test
    void quantityConflictMergesWithSnapshot() {
        // 第一条（clientTs 更新）SYNCED；第二条（clientTs 更旧）字典序败 → MERGE 冲突
        service.uploadBatch(List.of(rec(P + "K-A", "SCAN", "BIZ-QTY-1",
                LocalDateTime.of(2026, 11, 1, 10, 0), 1,
                "{\"bizKey\":\"BIZ-QTY-1\",\"qty\":10}")));
        Map<String, Object> out = service.uploadBatch(List.of(rec(P + "K-B", "SCAN", "BIZ-QTY-1",
                LocalDateTime.of(2026, 11, 1, 9, 0), 1,
                "{\"bizKey\":\"BIZ-QTY-1\",\"qty\":20}")));
        assertEquals(1, out.get("conflicted"), "更旧客户端记录判为冲突");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        assertEquals("CONFLICT", results.get(0).get("result"));
        String conflictId = String.valueOf(results.get(0).get("conflictId"));
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT CONFLICT_TYPE, NEED_FIN_APPROVAL, DICT_SEQ_KEY FROM erp_iot_sync_conflict WHERE ID=?",
                conflictId);
        assertEquals("MERGE", c.get("CONFLICT_TYPE"), "数量类自动合并");
        assertEquals("0", c.get("NEED_FIN_APPROVAL"));
        String dict = String.valueOf(c.get("DICT_SEQ_KEY"));
        assertTrue(dict.contains("V001"), "字典序键含版本号");
        assertTrue(dict.indexOf("10:00") < dict.indexOf("09:00") || dict.contains("2026-11-01 10"),
                "字典序键含时间戳（VER_NO > CLIENT_TS > IDEMPOTENT_KEY）");
    }

    @Test
    void financialConflictBlockedUntilL2Approve() {
        service.uploadBatch(List.of(rec(P + "K-F1", "SCAN", "BIZ-FIN-1",
                LocalDateTime.of(2026, 11, 1, 10, 0), 2,
                "{\"bizKey\":\"BIZ-FIN-1\",\"qty\":5,\"amount\":100}")));
        Map<String, Object> out = service.uploadBatch(List.of(rec(P + "K-F2", "SCAN", "BIZ-FIN-1",
                LocalDateTime.of(2026, 11, 1, 9, 0), 2,
                "{\"bizKey\":\"BIZ-FIN-1\",\"qty\":5,\"amount\":100,\"overTolerance\":true}")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) out.get("results");
        String conflictId = String.valueOf(results.get(0).get("conflictId"));
        Map<String, Object> c = jdbc.queryForMap(
                "SELECT NEED_FIN_APPROVAL, STATUS FROM erp_iot_sync_conflict WHERE ID=?", conflictId);
        assertEquals("1", c.get("NEED_FIN_APPROVAL"), "财务数据标记 L2");
        assertEquals("NEED_REVIEW", c.get("STATUS"));
        // 未 APPROVE 前裁决 → 422 阻断
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.resolveConflict(conflictId, "REJECT", "先不批"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("L2"));
        // 财务主管 APPROVE → 裁决生效 + 队列放行
        Map<String, Object> r = service.resolveConflict(conflictId, "APPROVE", "财务主管同意");
        assertEquals("RESOLVED", r.get("status"));
        String qStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_iot_offline_queue WHERE ID=?",
                String.class, jdbc.queryForObject(
                        "SELECT QUEUE_ID FROM erp_iot_sync_conflict WHERE ID=?",
                        String.class, conflictId));
        assertEquals("SYNCED", qStatus, "裁决后队列放行");
    }

    // ---------- 5.3 72h 锁定链路 ----------

    @Test
    void lockScanWarnsAndLocksAtThreshold() {
        // 55 小时前的 PENDING → 预警（54h 起）不锁定
        jdbc.update("INSERT INTO erp_iot_offline_queue (ID, IDEMPOTENT_KEY, DEVICE_CODE, BIZ_TYPE, " +
                        "PAYLOAD_JSON, CLIENT_VER_NO, CLIENT_TS, STATUS, RETRY_CNT, CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,'SCAN','{}',0, DATE_SUB(NOW(), INTERVAL 55 HOUR), 'PENDING',0,NOW(),'0',0)",
                P + "q-warn", P + "K-WARN", deviceCode);
        Map<String, Object> warned = service.scanLocks();
        @SuppressWarnings("unchecked")
        List<String> warnedList = (List<String>) warned.get("warned");
        assertTrue(warnedList.stream().anyMatch(s -> s.contains(deviceCode)), "55h 进入预警清单");
        assertEquals("0", terminalService.detail(deviceCode).get("lockedFlag"), "预警不锁定");
        // 拨到 73 小时 → 锁定
        jdbc.update("UPDATE erp_iot_offline_queue SET CLIENT_TS=DATE_SUB(NOW(), INTERVAL 73 HOUR) " +
                "WHERE IDEMPOTENT_KEY=?", P + "K-WARN");
        Map<String, Object> lockedOut = service.scanLocks();
        @SuppressWarnings("unchecked")
        List<String> lockedList = (List<String>) lockedOut.get("locked");
        assertTrue(lockedList.stream().anyMatch(s -> s.contains(deviceCode)), "73h 触发锁定");
        Map<String, Object> detail = terminalService.detail(deviceCode);
        assertEquals("1", detail.get("lockedFlag"), "终端锁定标记置位");
        assertTrue(String.valueOf(detail.get("lockReason")).contains("72"), "锁定原因留痕");
        assertNotNull(detail.get("lockAt"), "锁定时间留痕");
        // 锁定状态视图
        Map<String, Object> lock = service.lockStatus(deviceCode);
        assertEquals(Boolean.TRUE, lock.get("locked"));
        assertEquals(Boolean.TRUE, lock.get("warn"), "超预警比例黄闪标记");
    }

    @Test
    void cleanupClearsSyncedRows() {
        // 一条 SYNCED 超 1 小时 → 清理
        jdbc.update("INSERT INTO erp_iot_offline_queue (ID, IDEMPOTENT_KEY, DEVICE_CODE, BIZ_TYPE, " +
                        "PAYLOAD_JSON, CLIENT_VER_NO, CLIENT_TS, STATUS, RETRY_CNT, SYNCED_AT, " +
                        "CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,'SCAN','{}',0, NOW(),'SYNCED',0, DATE_SUB(NOW(), INTERVAL 2 HOUR), " +
                        "NOW(),'0',0)",
                P + "q-old", P + "K-OLD", deviceCode);
        jdbc.update("INSERT INTO erp_iot_offline_queue (ID, IDEMPOTENT_KEY, DEVICE_CODE, BIZ_TYPE, " +
                        "PAYLOAD_JSON, CLIENT_VER_NO, CLIENT_TS, STATUS, RETRY_CNT, SYNCED_AT, " +
                        "CREATE_DATE, DEL_FLAG, VER_NO) " +
                        "VALUES (?,?,?,'SCAN','{}',0, NOW(),'SYNCED',0, NOW(), NOW(),'0',0)",
                P + "q-new", P + "K-NEW", deviceCode);
        Map<String, Object> out = service.cleanupSynced(deviceCode);
        assertEquals(1, out.get("cleared"), "超 1 小时 SYNCED 清除（本地缓存清理确认）");
        Integer remain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_iot_offline_queue WHERE DEVICE_CODE=?",
                Integer.class, deviceCode);
        assertEquals(1, remain, "新鲜 SYNCED 保留");
    }
}
