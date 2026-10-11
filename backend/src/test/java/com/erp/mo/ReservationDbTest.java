package com.erp.mo;

import com.erp.dao.mrp.MrpReservationDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpReservation;
import com.erp.service.SysParamService;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 物料预扣库测（task 2.1 / design D2 账目护栏）：
 * 部分预扣 min(可用,需求)、活动行幂等零重复扣、OFF 开关只记账、释放全量回补。
 */
@SpringBootTest
class ReservationDbTest {

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
    private SysParamService sysParam;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("resv-tester", "ROLE_PLANNER", "ROLE_ADMIN");
        sysParam.setValue("DISPATCH_RESERVATION", "ON", "ENUM", "MRP", "测试期默认开", "test");
    }

    @AfterEach
    void tearDown() {
        sysParam.setValue("DISPATCH_RESERVATION", "ON", "ENUM", "MRP", "恢复迁移 122 默认", "test");
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

    /** 物料当前可用量合计 */
    private BigDecimal avail(String itemCode) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(AVAILABLE_QTY),0) FROM erp_inv_stock WHERE ITEM_CODE=?",
                BigDecimal.class, itemCode);
        return v == null ? BigDecimal.ZERO : v;
    }

    private List<MrpReservation> activeRows(String moId) {
        return reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, moId)
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE));
    }

    @Test
    void partialReserveMinsAvailable() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "预扣产品一", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "预扣子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0"); // 用量 2
        MrpMo mo = createMo(product, "50"); // 需求 100
        MoSeed.mkStock(jdbc, child.getItemCode(), "30"); // 可用 30 < 需求 100

        MrpReservation row = reservationService.reserveForMo(mo.getId(), "tester");

        assertEquals(0, row.getReqQty().compareTo(new BigDecimal("100.0000")),
                "需求 = 50×2 = 100");
        assertEquals(0, row.getReservedQty().compareTo(new BigDecimal("30.0000")),
                "部分预扣 = min(可用 30, 需求 100)");
        assertEquals(0, row.getRemainQty().compareTo(new BigDecimal("30.0000")), "核销余量 = 实扣");
        assertEquals(0, avail(child.getItemCode()).compareTo(BigDecimal.ZERO),
                "可用量实扣到 0");
        assertEquals("1", row.getActiveFlag(), "活动行标记");
    }

    @Test
    void doubleConfirmIsIdempotent() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "预扣产品二", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "预扣子件二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10"); // 需求 10
        MoSeed.mkStock(jdbc, child.getItemCode(), "50");

        reservationService.reserveForMo(mo.getId(), "tester");
        BigDecimal after1 = avail(child.getItemCode());
        assertEquals(0, after1.compareTo(new BigDecimal("40.0000")), "首次预扣 10");

        reservationService.reserveForMo(mo.getId(), "tester"); // 重复确认（其他工序再确认）
        assertEquals(0, avail(child.getItemCode()).compareTo(after1), "重复确认零重复扣");
        assertEquals(1, activeRows(mo.getId()).size(), "活动行仍唯一（幂等锚点）");
    }

    @Test
    void offSwitchRecordsWithoutDeducting() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "预扣产品三", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "预扣子件三", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "50"); // 需求 100
        MoSeed.mkStock(jdbc, child.getItemCode(), "30");
        sysParam.setValue("DISPATCH_RESERVATION", "OFF", "ENUM", "MRP", "测试关闸", "test");

        MrpReservation row = reservationService.reserveForMo(mo.getId(), "tester");

        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("30.0000")),
                "OFF：AVAILABLE 不变（仅记账）");
        assertEquals(0, row.getReservedQty().compareTo(new BigDecimal("30.0000")),
                "OFF：账面仍记 min(可用, 需求)");
        assertTrue(row.getTakesJson() == null || row.getTakesJson().isEmpty()
                        || "[]".equals(row.getTakesJson()),
                "OFF：无实扣明细");
    }

    @Test
    void releaseRestoresFullRemain() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "预扣产品四", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "预扣子件四", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10"); // 需求 10
        MoSeed.mkStock(jdbc, child.getItemCode(), "50");
        reservationService.reserveForMo(mo.getId(), "tester");
        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("40.0000")), "预扣后 40");

        reservationService.releaseByMo(mo.getId(), "派工取消", "tester");

        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("50.0000")),
                "释放全量回补 50");
        assertTrue(activeRows(mo.getId()).isEmpty(), "活动行关闭");
        List<MrpReservation> all = reservationService.historyOf(mo.getId());
        assertEquals(1, all.size(), "历史留一行");
        assertEquals(MrpReservation.ST_RELEASED, all.get(0).getStatus(), "RELEASED");
        assertEquals("派工取消", all.get(0).getReleaseReason(), "原因留痕");
        assertEquals(0, all.get(0).getRemainQty().compareTo(BigDecimal.ZERO), "余量归零");
    }

    @Test
    void offRowReleaseDoesNotTouchStock() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "预扣产品五", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "预扣子件五", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10");
        MoSeed.mkStock(jdbc, child.getItemCode(), "30");
        sysParam.setValue("DISPATCH_RESERVATION", "OFF", "ENUM", "MRP", "测试关闸", "test");
        reservationService.reserveForMo(mo.getId(), "tester");

        reservationService.releaseByMo(mo.getId(), "驳回", "tester");

        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("30.0000")),
                "OFF 记账行释放不动库存（无 takes 零回补）");
        assertTrue(activeRows(mo.getId()).isEmpty(), "活动行关闭");
    }
}
