package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvWave;
import com.erp.entity.inv.InvWaveAdjust;
import com.erp.entity.inv.InvWaveDoc;
import com.erp.entity.sd.Shipment;
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
 * 波次生成 DB 集成（真实 MySQL，spec wave-management 聚类生成/状态机/作废，任务 3.1/3.2/3.3）：
 * 三键优先级、空线路降级分桶、超容拆分、空源不建波次、失效剔除、非法跃迁 422、作废全联动。
 */
@SpringBootTest
class WaveCreateDbTest {

    private static final String RT = "wv-route-1";

    @Autowired
    private WaveService service;
    @Autowired
    private com.erp.service.SysParamService paramService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));

        jdbc.update("INSERT INTO erp_inv_route (ID, ROUTE_CODE, ROUTE_NAME, STATUS, CREATE_BY) "
                + "VALUES (?, 'WV-R1', '波次测试线路', 'ACTIVE', 'junit')", RT);

        // 同线路 3 张 DRAFT（ROUTE 桶应聚一组）
        for (int i = 1; i <= 3; i++) {
            insertShipment("wv-sh-" + i, "SH-WV-" + i, RT, "顺丰", "C-WV", "同线客户", "DRAFT");
        }
        // 无线路、同承运商 2 张（CARRIER 桶）
        insertShipment("wv-sh-4", "SH-WV-4", null, "德邦", "C-WV2", "另一客户", "DRAFT");
        insertShipment("wv-sh-5", "SH-WV-5", null, "德邦", "C-WV3", "第三客户", "DRAFT");
        // 无线路无承运商（CUSTOMER 桶）1 张
        insertShipment("wv-sh-6", "SH-WV-6", null, null, "C-WV4", "无线路客户", "DRAFT");
        // 非 DRAFT 不入候选
        insertShipment("wv-sh-7", "SH-WV-7", RT, "顺丰", "C-WV", "同线客户", "POSTED");
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    /** 只取本夹具（SH-WV-）的组与单据——共享库存在冒烟残留 DRAFT 单，断言需隔离 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fixtureGroups() {
        Map<String, Object> out = service.preview();
        List<Map<String, Object>> groups = (List<Map<String, Object>>) out.get("groups");
        List<Map<String, Object>> hit = new java.util.ArrayList<>();
        for (Map<String, Object> g : groups) {
            List<Map<String, Object>> docs = ((List<Map<String, Object>>) g.get("docs"))
                    .stream()
                    .filter(d -> String.valueOf(d.get("shipNo")).startsWith("SH-WV-"))
                    .collect(java.util.stream.Collectors.toList());
            if (!docs.isEmpty()) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>(g);
                copy.put("docs", docs);
                hit.add(copy);
            }
        }
        return hit;
    }

    @Test
    void previewClustersByThreeKeysWithDegradation() {
        List<Map<String, Object>> groups = fixtureGroups();
        // ROUTE 3 张一组 + CARRIER 2 张一组 + CUSTOMER 1 张 = 3 组 6 张（POSTED 排除）
        assertEquals(3, groups.size(), "三键三个候选组：" + groups);
        long total = groups.stream().mapToInt(g -> ((List<?>) g.get("docs")).size()).sum();
        assertEquals(6, total, "DRAFT 6 张（POSTED 排除）");

        Map<String, Long> byType = new java.util.HashMap<>();
        for (Map<String, Object> g : groups) {
            byType.merge(String.valueOf(g.get("clusterType")),
                    (long) ((List<?>) g.get("docs")).size(), Long::sum);
        }
        assertEquals(3L, byType.get("ROUTE"), "线路桶 3 张");
        assertEquals(2L, byType.get("CARRIER"), "空线路降级承运商桶 2 张（不与有线索单混）");
        assertEquals(1L, byType.get("CUSTOMER"), "无线路无承运商按客户 1 张");
    }

    @Test
    void previewSplitsOverCapacity() {
        // 超容：WAVE_MAX_DOCS=50 默认，塞 3 张同线不够——直接改参数再验
        paramService.setValue("WAVE_MAX_DOCS", "2", "COUNT", "INV", "超容拆分测试", "junit");
        try {
            List<Map<String, Object>> groups = fixtureGroups();
            long routeGroups = groups.stream()
                    .filter(g -> "ROUTE".equals(g.get("clusterType")))
                    .count();
            assertEquals(2, routeGroups, "线路桶 3 张 > 2 拆为 2 组");
            boolean hasSplit = groups.stream().anyMatch(g ->
                    String.valueOf(g.get("splitReason")).contains("超容量拆分"));
            assertTrue(hasSplit, "拆分组标注原因");
        } finally {
            paramService.setValue("WAVE_MAX_DOCS", "50", "COUNT", "INV", "恢复默认", "junit");
        }
    }

    @Test
    void previewEmptySourceNoWave() {
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'wv-sh-%'");
        assertTrue(fixtureGroups().isEmpty(), "本夹具空源无候选组（残留单不影响本断言）");
        // 空 groups 建波次 → 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.createFromPreview(List.of()));
        assertEquals(422, ex.getCode());
    }

    @Test
    void createSkipsStaleDocsAndRejectsAllInvalid() {
        // 先把 1 号改成 POSTED（模拟并发失效）
        jdbc.update("UPDATE erp_sd_shipment SET STATUS = 'POSTED' WHERE ID = 'wv-sh-1'");
        Map<String, Object> payload = Map.of("groups", List.of(
                Map.of("clusterType", "ROUTE", "clusterKey", RT,
                        "docs", List.of(
                                Map.of("shipId", "wv-sh-1"),
                                Map.of("shipId", "wv-sh-2"),
                                Map.of("shipId", "wv-sh-3")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> out = service.createFromPreview(
                (List<Map<String, Object>>) payload.get("groups"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> waves = (List<Map<String, Object>>) out.get("waves");
        assertEquals(1, waves.size());
        assertEquals(2, waves.get(0).get("docCount"), "失效 1 号剔除，2 张落库");
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) out.get("skipped");
        assertEquals(1, skipped.size());
        assertTrue(skipped.get(0).contains("SH-WV-1"), "剔除说明含单号：" + skipped);

        // 全失效 → 422 不建
        jdbc.update("UPDATE erp_sd_shipment SET STATUS = 'POSTED' WHERE ID LIKE 'wv-sh-%'");
        ServiceException ex = assertThrows(ServiceException.class, () ->
                service.createFromPreview(List.of(
                        Map.of("clusterType", "ROUTE", "clusterKey", RT,
                                "docs", List.of(Map.of("shipId", "wv-sh-2"))))));
        assertEquals(422, ex.getCode(), "全部失效不建波次");
    }

    @Test
    void boundDocsExcludedFromNextPreview() {
        service.createFromPreview(List.of(
                Map.of("clusterType", "ROUTE", "clusterKey", RT,
                        "docs", List.of(Map.of("shipId", "wv-sh-2")))));
        List<Map<String, Object>> groups = fixtureGroups();
        boolean contains = groups.stream().flatMap(g -> ((List<?>) g.get("docs")).stream())
                .anyMatch(d -> "wv-sh-2".equals(((Map<?, ?>) d).get("shipId")));
        assertTrue(!contains, "已入波次单据不再进候选（防重复入波）");
        long rest = groups.stream().mapToInt(g -> ((List<?>) g.get("docs")).size()).sum();
        assertEquals(5, rest, "剩余 5 张（2 号已入波次）");
    }

    @Test
    void illegalTransitionAndCancelFullyUnbinds() {
        @SuppressWarnings("unchecked")
        Map<String, Object> out = service.createFromPreview(List.of(
                Map.of("clusterType", "ROUTE", "clusterKey", RT,
                        "docs", List.of(Map.of("shipId", "wv-sh-2"),
                                Map.of("shipId", "wv-sh-3")))));
        @SuppressWarnings("unchecked")
        Map<String, Object> w = (Map<String, Object>)
                ((List<?>) out.get("waves")).get(0);
        String waveId = (String) w.get("waveId");

        // CREATED → PICKING 非法（须先 ALLOCATED）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.transition(waveId, InvWave.ST_CREATED, InvWave.ST_PICKING));
        assertEquals(422, ex.getCode());
        // from 不匹配当前状态也 422
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> service.transition(waveId, InvWave.ST_ALLOCATED, InvWave.ST_PICKING));
        assertEquals(422, ex2.getCode());

        // 预置一条未决改批 + 分配行锁，验证作废联动
        jdbc.update("INSERT INTO erp_inv_wave_line (ID, WAVE_ID, SHIP_ID, LINE_NO, ITEM_CODE, "
                + "WAREHOUSE_CODE, QTY, LOCK_FLAG, CREATE_BY) VALUES "
                + "('wv-ln-1', ?, 'wv-sh-2', 1, 'IT-WV', 'WH-MAIN', 5, '1', 'junit')", waveId);
        jdbc.update("INSERT INTO erp_inv_wave_adjust (ID, WAVE_ID, LINE_ID, FIELD, NEW_VALUE, "
                + "REASON, STATUS, ADJUST_BY, CREATE_BY) VALUES ('wv-aj-1', ?, 'wv-ln-1', "
                + "'BATCH', 'B2', '测试', 'PENDING', 'tester', 'junit')", waveId);

        // 作废需原因
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> service.cancel(waveId, " "));
        assertEquals(422, ex3.getCode());

        service.cancel(waveId, "需求取消");

        // 全联动断言
        String waveStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_wave WHERE ID = ?", String.class, waveId);
        assertEquals(InvWave.ST_CANCELLED, waveStatus);
        int bound = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_wave_doc "
                + "WHERE WAVE_ID = ? AND BIND_STATUS = 'BOUND'", Integer.class, waveId);
        assertEquals(0, bound, "全部解绑（保留行）");
        int unbound = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_wave_doc "
                + "WHERE WAVE_ID = ? AND BIND_STATUS = 'UNBOUND'", Integer.class, waveId);
        assertEquals(2, unbound, "归属行保留（C-0-05）");
        String lock = jdbc.queryForObject("SELECT LOCK_FLAG FROM erp_inv_wave_line "
                + "WHERE ID = 'wv-ln-1'", String.class);
        assertEquals("0", lock, "锁全解");
        String adj = jdbc.queryForObject("SELECT STATUS FROM erp_inv_wave_adjust "
                + "WHERE ID = 'wv-aj-1'", String.class);
        assertEquals(InvWaveAdjust.ST_CANCELLED, adj, "未决调整关闭留痕");

        // 作废后单据回到候选（归属 UNBOUND）
        boolean back = fixtureGroups().stream()
                .flatMap(g -> ((List<?>) g.get("docs")).stream())
                .anyMatch(d -> "wv-sh-2".equals(((Map<?, ?>) d).get("shipId")));
        assertTrue(back, "作废解绑后单据可再次入波次");
    }

    // ---------- 夹具 ----------

    private void insertShipment(String id, String shipNo, String routeId, String carrier,
                                String custCode, String custName, String status) {
        jdbc.update("INSERT INTO erp_sd_shipment (ID, SHIP_NO, SHIP_TYPE, CUSTOMER_ID, "
                + "CUSTOMER_CODE, CUSTOMER_NAME, WAREHOUSE_CODE, STATUS, TOTAL_QTY, TOTAL_AMT, "
                + "ROUTE_ID, LOGISTICS_CO, CREATE_BY) VALUES (?, ?, 'PARTIAL', ?, ?, ?, "
                + "'WH-MAIN', ?, 10, 100, ?, ?, 'junit')",
                id, shipNo, "wv-cust-" + custCode, custCode, custName, status, routeId, carrier);
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_wave_adjust WHERE WAVE_ID IN "
                + "(SELECT ID FROM erp_inv_wave WHERE WAVE_NO LIKE 'WV%')");
        jdbc.update("DELETE FROM erp_inv_wave_line WHERE WAVE_ID IN "
                + "(SELECT ID FROM erp_inv_wave WHERE WAVE_NO LIKE 'WV%')");
        jdbc.update("DELETE FROM erp_inv_wave_doc WHERE WAVE_ID IN "
                + "(SELECT ID FROM erp_inv_wave WHERE WAVE_NO LIKE 'WV%')");
        jdbc.update("DELETE FROM erp_inv_wave WHERE WAVE_NO LIKE 'WV%'");
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'wv-sh-%'");
        jdbc.update("DELETE FROM erp_inv_route WHERE ID = ?", RT);
    }
}
