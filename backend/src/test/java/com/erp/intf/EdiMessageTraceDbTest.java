package com.erp.intf;

import com.erp.service.intf.EdiMessageService;
import com.erp.util.IntfCrypto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重试轨迹与回执留存（add-edi-message-console，spec edi-message-processing 留痕要求）。
 * - 轨迹：每次语法失败逐条留痕不覆盖（BR-4.9-24 工单附全量清单）；
 * - 回执：finish() 与幂等跳过路径各留一条（SKIPPED 含）；
 * - detail 返回体扩展 retryTrace（升序）/ receipts（降序）。
 */
@SpringBootTest
class EdiMessageTraceDbTest {

    private static final String PARTNER = "smk-edi-t";

    @Autowired
    private EdiMessageService service;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void purge() {
        jdbc.update("DELETE FROM erp_intf_msg_retry_trace WHERE MSG_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_intf_message WHERE PARTNER_CODE=?) t)", PARTNER);
        jdbc.update("DELETE FROM erp_intf_msg_receipt WHERE MSG_ID IN " +
                "(SELECT ID FROM (SELECT ID FROM erp_intf_message WHERE PARTNER_CODE=?) t)", PARTNER);
        jdbc.update("DELETE FROM erp_intf_ticket WHERE PARTNER_CODE=?", PARTNER);
        jdbc.update("DELETE FROM erp_intf_message WHERE PARTNER_CODE=?", PARTNER);
    }

    private String badRaw(String idem) {
        // 语法层必败：明细行缺 itemCode（行 1 / 段 PO1 / 字段 01）
        return "{\"idempotencyKey\":\"" + idem + "\",\"version\":\"v1\"," +
                "\"header\":{\"supplierId\":\"S1\",\"orderDate\":\"20261028\"}," +
                "\"lines\":[{\"qty\":1,\"unit\":\"PC\",\"unitPrice\":1}]}";
    }

    @Test
    void traceAccumulatesPerAttemptAndDetailExtends() {
        String idem = PARTNER + ":ORDERS:TR1:v1";
        String raw = badRaw(idem);

        // 第 1 次：语法失败 → REJECTED + 轨迹 1 条 + finish 回执 1 条
        service.receive("UPLOAD", PARTNER, "ORDERS", raw);
        assertEquals(1, traceCount(idem));
        assertEquals(1, receiptCount(idem));
        assertEquals("REJECTED", statusOf(idem));

        // 第 2 次（对方重发同载荷）：轨迹第 2 条，主行 retryCount=2（syntaxResult 被覆盖但轨迹不覆盖）
        service.receive("UPLOAD", PARTNER, "ORDERS", raw);
        assertEquals(2, traceCount(idem));
        assertEquals(Integer.valueOf(2), retryCountOf(idem));

        // 第 3 次：达 API_RETRY_MAX=3 → MANUAL + 工单；轨迹 3 条全量
        service.receive("UPLOAD", PARTNER, "ORDERS", raw);
        assertEquals(3, traceCount(idem));
        assertEquals("MANUAL", statusOf(idem));
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_intf_ticket WHERE PARTNER_CODE=? AND SOURCE LIKE '%MANUAL%'",
                Integer.class, PARTNER));

        // detail 扩展：retryTrace 升序全量、receipts 存在
        Map<String, Object> d = service.detail(msgId(idem));
        @SuppressWarnings("unchecked")
        List<com.erp.entity.intf.IntfMsgRetryTrace> trace =
                (List<com.erp.entity.intf.IntfMsgRetryTrace>) d.get("retryTrace");
        assertNotNull(trace);
        assertEquals(3, trace.size());
        assertEquals(1, trace.get(0).getAttemptNo().intValue());
        assertEquals(3, trace.get(2).getAttemptNo().intValue());
        assertNotNull(trace.get(0).getErrorsJson());
        @SuppressWarnings("unchecked")
        List<com.erp.entity.intf.IntfMsgReceipt> receipts =
                (List<com.erp.entity.intf.IntfMsgReceipt>) d.get("receipts");
        assertNotNull(receipts);
        assertTrue(receipts.size() >= 3, "每次处理结论均留回执");
    }

    @Test
    void replayKeepsHistoryAndSkippedReceiptLands() {
        String idem = PARTNER + ":ORDERS:TR2:v1";
        String raw = badRaw(idem);
        service.receive("UPLOAD", PARTNER, "ORDERS", raw);
        service.receive("UPLOAD", PARTNER, "ORDERS", raw);
        assertEquals(2, traceCount(idem));

        // 人工重放（保留原幂等键）：再次语法失败 → 轨迹第 3 条，历史不被清除
        service.replay(msgId(idem));
        assertEquals(3, traceCount(idem));

        // 幂等跳过回执：直插已处理报文（同载荷哈希），再次推送 → SKIPPED 回执行
        String idem2 = PARTNER + ":ORDERS:TR3:v1";
        String raw2 = badRaw(idem2);
        String hash = IntfCrypto.sha256Hex(raw2.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("INSERT INTO erp_intf_message (ID, MSG_NO, PARTNER_CODE, MSG_TYPE, MSG_VERSION," +
                        " IDEMPOTENCY_KEY, RAW_PAYLOAD, PAYLOAD_HASH, SOURCE, STATUS, LINE_TOTAL, LINE_OK, LINE_FAIL," +
                        " RETRY_COUNT, RECEIVED_AT, RECEIPT_SENT, DEL_FLAG, VER_NO)" +
                        " VALUES (?, ?, ?, 'ORDERS', 'v1', ?, ?, ?, 'UPLOAD', 'PROCESSED', 1, 1, 0, 0, NOW(), 1, '0', 0)",
                idem2 + "-id", "MSG-SMK-EDI-T3", PARTNER, idem2, raw2, hash);
        Map<String, Object> r = service.receive("UPLOAD", PARTNER, "ORDERS", raw2);
        assertTrue(String.valueOf(r.get("message")).contains("重复跳过"));
        assertEquals(1, receiptCount(idem2));
        assertEquals("SKIPPED", jdbc.queryForObject(
                "SELECT CONCLUSION FROM erp_intf_msg_receipt WHERE MSG_ID=?", String.class, idem2 + "-id"));
        // 主行状态不变（跳过不改台账）
        assertEquals("PROCESSED", statusOf(idem2));
    }

    private int traceCount(String idem) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_intf_msg_retry_trace t " +
                "JOIN erp_intf_message m ON m.ID=t.MSG_ID WHERE m.IDEMPOTENCY_KEY=?", Integer.class, idem);
    }

    private int receiptCount(String idem) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM erp_intf_msg_receipt r " +
                "JOIN erp_intf_message m ON m.ID=r.MSG_ID WHERE m.IDEMPOTENCY_KEY=?", Integer.class, idem);
    }

    private String statusOf(String idem) {
        return jdbc.queryForObject("SELECT STATUS FROM erp_intf_message WHERE IDEMPOTENCY_KEY=?", String.class, idem);
    }

    private Integer retryCountOf(String idem) {
        return jdbc.queryForObject("SELECT RETRY_COUNT FROM erp_intf_message WHERE IDEMPOTENCY_KEY=?", Integer.class, idem);
    }

    private String msgId(String idem) {
        return jdbc.queryForObject("SELECT ID FROM erp_intf_message WHERE IDEMPOTENCY_KEY=?", String.class, idem);
    }
}
