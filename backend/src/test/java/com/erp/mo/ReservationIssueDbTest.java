package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpReservation;
import com.erp.dao.mrp.MrpReservationDao;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.ReservationService;
import com.erp.service.vmi.MaterialIssueService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 预扣核销库测（task 2.2 + 2.3，spec material-issue ADDED「预扣核销联动」）：
 * 自然顺序（派工预扣在前、领料建单在后）创建可用、过账 release-first 核销、
 * 余量护栏（领 80 只核 60）、无关/未关联工单零变化、逐步对账。
 */
@SpringBootTest
class ReservationIssueDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private ReservationService reservationService;
    @Autowired
    private MrpReservationDao reservationDao;
    @Autowired
    private MaterialIssueService issueService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private int seq;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("rsv-issue-tester", "ROLE_PLANNER", "ROLE_WAREHOUSE", "ROLE_ADMIN");
        seq = 0;
    }

    @AfterEach
    void tearDown() {
        // 本测试创建的领料单（createBy=测试登录人）；须在 MoSeed.cleanup 删物料前执行
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID IN "
                + "(SELECT ID FROM erp_inv_material_issue WHERE CREATE_BY='rsv-issue-tester')");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE CREATE_BY='rsv-issue-tester'");
        MoTestAuth.logout();
        MoSeed.cleanup(jdbc);
    }

    private MrpMo createMo(MdmItem product, String qty) {
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(7));
        return (MrpMo) moService.create(h).get("mo");
    }

    /** 带批次库存（WH-MAIN + BATCH_NO：引擎配批/过账按此路径） */
    private void mkStock(String itemCode, String qty) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                        + "BATCH_NO, QTY, AVAILABLE_QTY, CREATE_BY) VALUES (?,?,?,?,?,?,?,?)",
                "st-rsv-" + (++seq), "WH-MAIN", itemCode, "核销测试物料",
                "RSK-B1", new BigDecimal(qty), new BigDecimal(qty), "junit");
    }

    private BigDecimal avail(String itemCode) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(AVAILABLE_QTY),0) FROM erp_inv_stock WHERE ITEM_CODE=?",
                BigDecimal.class, itemCode);
        return v == null ? BigDecimal.ZERO : v;
    }

    private MrpReservation activeRow(String moId) {
        List<MrpReservation> rows = reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, moId)
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> createIssue(MdmItem child, String workOrderNo, String qty) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("issueType", "OWN");
        payload.put("workOrderNo", workOrderNo); // BR-4.2-37 工单号必填
        payload.put("lines", List.of(Map.of("itemCode", child.getItemCode(),
                "qty", new BigDecimal(qty))));
        return issueService.create(payload);
    }

    /** 创建返回的 issue 是实体（非 Map），取 id 过账 */
    private String postId(Map<String, Object> created) {
        return ((com.erp.entity.vmi.MaterialIssue) created.get("issue")).getId();
    }

    @SuppressWarnings("unchecked")
    private BigDecimal postedLineSum(Map<String, Object> created) {
        List<?> lines = (List<?>) created.get("lines");
        BigDecimal sum = BigDecimal.ZERO;
        for (Object o : lines) {
            sum = sum.add(((com.erp.entity.vmi.MaterialIssueLine) o).getQty());
        }
        return sum;
    }

    @Test
    void naturalOrderCreateAndPostWithLock() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "核销产品一", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "核销子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "100"); // 需求 100
        mkStock(child.getItemCode(), "150");
        reservationService.reserveForMo(mo.getId(), "tester"); // 锁 100 → 可用 50
        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("50.0000")),
                "预扣后可用 50");

        // 自然顺序：预扣在前 → 建单 80（可用 50 < 80，无锁额度时此步 422）
        Map<String, Object> created = createIssue(child, mo.getMoNo(), "80");
        assertEquals(0, postedLineSum(created).compareTo(new BigDecimal("80")),
                "创建成功且配批合计 80");
        issueService.post(postId(created));

        // 过账：release-first 释放 80（take 100→20）再引擎扣 80 → QTY70、可用 50 = 70 − 锁 20
        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("50.0000")),
                "终态可用 = QTY 70 − 剩余锁 20");
        MrpReservation row = activeRow(mo.getId());
        assertEquals(0, row.getRemainQty().compareTo(new BigDecimal("20.0000")),
                "核销余量 100 − 80 = 20");
        assertEquals(MrpReservation.ST_ACTIVE, row.getStatus(), "余量未尽仍 ACTIVE");
    }

    @Test
    void reconciliationFullChainWithGuardRail() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "核销产品二", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "核销子件二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "100");
        mkStock(child.getItemCode(), "150");
        reservationService.reserveForMo(mo.getId(), "tester");
        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("50.0000")),
                "① 预扣 100 后可用 50（QTY150）");

        // ② 领 40
        Map<String, Object> c40 = createIssue(child, mo.getMoNo(), "40");
        issueService.post(postId(c40));
        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("50.0000")),
                "② 领 40：释放 40 再扣 40，可用 = QTY110 − 锁60 = 50");
        assertEquals(0, activeRow(mo.getId()).getRemainQty().compareTo(new BigDecimal("60.0000")),
                "② 余量 100 − 40 = 60");

        // ③ 领 80（护栏：只核 60 → CLEARED；拆分 = 锁内 60 + 锁外 20）
        Map<String, Object> c80 = createIssue(child, mo.getMoNo(), "80");
        assertEquals(0, postedLineSum(c80).compareTo(new BigDecimal("80")),
                "③ 拆分分配：锁内 60 占位 + 锁外 20 引擎 FIFO");
        issueService.post(postId(c80));
        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("30.0000")),
                "③ 领 80 后可用 = QTY30 − 锁0 = 30（护栏只核 60）");
        assertTrue(activeRow(mo.getId()) == null, "③ 余量归零 → CLEARED 无活动行");

        // ④ 取消释放：无活动行 → 零副作用
        BigDecimal before = avail(child.getItemCode());
        reservationService.releaseByMo(mo.getId(), "派工取消", "tester");
        assertEquals(0, avail(child.getItemCode()).compareTo(before),
                "④ 已核销完的工单释放零副作用");
    }

    @Test
    void otherMoIssueDoesNotConsume() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "核销产品三", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "核销子件三", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "100");
        // 另一张【不同产品】工单（同产品在途工单唯一规则），其名下无预扣
        MdmItem product2 = MoSeed.mkItem(itemService, categoryCode, "核销产品三之二", "MAKE");
        MdmItem child2 = MoSeed.mkItem(itemService, categoryCode, "核销子件三之二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product2, child2, "1", "0");
        MrpMo otherMo = createMo(product2, "100");
        mkStock(child.getItemCode(), "150");
        reservationService.reserveForMo(mo.getId(), "tester"); // 锁 100 → 可用 50

        // 关联【无预扣工单】的领料（BR-4.2-37 工单号必填，无法空）：
        // 无锁额度计入（available 50 足够建 10）、过账核销查无活动行 → 零消耗（既有行为零变化）
        Map<String, Object> created = createIssue(child, otherMo.getMoNo(), "10");
        issueService.post(postId(created));

        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("40.0000")),
                "他单领料：纯引擎扣 10（50 → 40），本单锁不动");
        assertEquals(0, activeRow(mo.getId()).getRemainQty().compareTo(new BigDecimal("100.0000")),
                "本单预扣余量零核销（核销仅限领料单关联的工单）");
    }
}
