package com.erp.sd;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.sd.ReservationDao;
import com.erp.dao.sd.ShipmentDao;
import com.erp.dao.sd.ShipmentLineDao;
import com.erp.dao.sd.SoLineDao;
import com.erp.entity.inv.InvStock;
import com.erp.entity.sd.Reservation;
import com.erp.entity.sd.Shipment;
import com.erp.entity.sd.ShipmentLine;
import com.erp.entity.sd.SoLine;
import com.erp.service.sd.ShipmentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 发货出库 DB 级恒等 E2E（spec sales-shipment MODIFIED，任务 8.3 后半）：
 * 真实过账后 erp_inv_stock 双列同减且行级恒等 QTY = AVAILABLE + QC + FIN 成立
 * （修复前只扣 AVAILABLE → QTY 虚高，恒等式破坏）。
 */
@SpringBootTest
class ShipmentIdentityDbTest {

    private static final String WH = "WH-SHIPDB";
    private static final String ITEM = "IT-SHIP-DBE";

    @Autowired
    private ShipmentService shipmentService;
    @Autowired
    private ShipmentDao shipDao;
    @Autowired
    private ShipmentLineDao shipLineDao;
    @Autowired
    private SoLineDao soLineDao;
    @Autowired
    private ReservationDao reservationDao;
    @Autowired
    private InvStockDao stockDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"),
                        new SimpleGrantedAuthority("ROLE_ADMIN"))));

        // 库存：100 = 100（无冻结）
        InvStock s = new InvStock();
        s.setId("db-ship-stock");
        s.setWarehouseCode(WH);
        s.setItemCode(ITEM);
        s.setItemName("发货恒等测试物料");
        s.setBatchNo("B-SHIP-1");
        s.setQty(new BigDecimal("100"));
        s.setAvailableQty(new BigDecimal("100"));
        s.setQcQty(BigDecimal.ZERO);
        s.setFinQty(BigDecimal.ZERO);
        s.setInboundDate(java.time.LocalDate.of(2026, 10, 1));
        stockDao.insert(s);

        // SO 行（预留 50）
        SoLine sl = new SoLine();
        sl.setId("db-ship-sol");
        sl.setSoId("db-ship-so");
        sl.setLineNo(1);
        sl.setItemCode(ITEM);
        sl.setQty(new BigDecimal("50"));
        sl.setWarehouseCode(WH);
        sl.setPriceLocked("0");
        sl.setDeliveryPending("0");
        sl.setLineStatus(SoLine.LS_OPEN);
        sl.setShippedQty(BigDecimal.ZERO);
        sl.setInvoicedQty(BigDecimal.ZERO);
        sl.setReservedQty(new BigDecimal("50"));
        soLineDao.insert(sl);

        // ACTIVE 预留 50（过账须消耗）
        Reservation r = new Reservation();
        r.setId("db-ship-res");
        r.setSoId("db-ship-so");
        r.setSoNo("SO-DBE2-1");
        r.setLineId("db-ship-sol");
        r.setLineNo(1);
        r.setItemCode(ITEM);
        r.setWarehouseCode(WH);
        r.setBatchNo("B-SHIP-1");
        r.setQty(new BigDecimal("50"));
        r.setStatus(Reservation.ST_ACTIVE);
        r.setLockAt(LocalDateTime.now());
        reservationDao.insert(r);

        // 草稿发货单 + 行（50）
        Shipment sh = new Shipment();
        sh.setId("db-ship-1");
        sh.setShipNo("SH-DBE2-1");
        sh.setShipType("NORMAL");
        sh.setCustomerId("C-DBE2");
        sh.setWarehouseCode(WH);
        sh.setStatus(Shipment.ST_DRAFT);
        sh.setTotalQty(new BigDecimal("50"));
        sh.setTotalAmt(BigDecimal.ZERO);
        sh.setSignWarned("0");
        shipDao.insert(sh);

        ShipmentLine line = new ShipmentLine();
        line.setId("db-ship-line1");
        line.setShipId("db-ship-1");
        line.setLineNo(1);
        line.setSoId("db-ship-so");
        line.setSoLineId("db-ship-sol");
        line.setItemCode(ITEM);
        line.setQty(new BigDecimal("50"));
        line.setWarehouseCode(WH);
        line.setLineStatus(ShipmentLine.LS_PENDING);
        shipLineDao.insert(line);
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_shipment WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_so_line WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE WAREHOUSE_CODE = ?", WH);
        // AR 事件幂等键（SH-DBE2-1:v1）会拒重发布（C-0-06），历史行须清
        jdbc.update("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE '%SH-DBE2-1%' "
                + "OR EVENT_ID LIKE '%SH-DBE2-1%'");
        jdbc.update("DELETE FROM erp_inv_transaction WHERE BIZ_DOC_NO = 'SH-DBE2-1'");
    }

    @Test
    void postingDeductsBothColumnsAndHoldsIdentity() {
        Shipment done = shipmentService.post("db-ship-1");
        assertEquals(Shipment.ST_POSTED, done.getStatus());

        InvStock s = stockDao.selectById("db-ship-stock");
        // 双列同减：100 → 各 50（修复前 QTY 仍 100）
        assertEquals(0, new BigDecimal("50").compareTo(s.getQty()), "QTY 须同步扣减，实际 " + s.getQty());
        assertEquals(0, new BigDecimal("50").compareTo(s.getAvailableQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.getQcQty()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.getFinQty()));
        // 行级恒等
        assertEquals(0, s.getQty().compareTo(
                s.getAvailableQty().add(s.getQcQty()).add(s.getFinQty())),
                "行级恒等 QTY = AVAILABLE + QC + FIN");

        // 预留消耗 + SO 行回写
        Reservation r = reservationDao.selectById("db-ship-res");
        assertEquals(Reservation.ST_CONSUMED, r.getStatus());
        SoLine line = soLineDao.selectById("db-ship-sol");
        assertEquals(0, new BigDecimal("0").compareTo(line.getReservedQty()));

        // 发货单与行状态、批次分配
        ShipmentLine posted = shipLineDao.selectById("db-ship-line1");
        assertEquals(ShipmentLine.LS_POSTED, posted.getLineStatus());
        assertNotNull(posted.getBatchAlloc());
        assertTrue(posted.getBatchAlloc().contains("B-SHIP-1"), posted.getBatchAlloc());

        // SALES_OUT 流水对账（六链路流水完整性之一）
        var txns = jdbc.queryForList("SELECT DIRECTION, TYPE_CODE, QTY, BEFORE_QTY, AFTER_QTY "
                + "FROM erp_inv_transaction WHERE BIZ_DOC_NO = 'SH-DBE2-1'");
        assertEquals(1, txns.size());
        assertEquals("OUT", txns.get(0).get("DIRECTION"));
        assertEquals("SALES_OUT", txns.get(0).get("TYPE_CODE"));
        assertEquals(0, new BigDecimal("100").compareTo(
                new java.math.BigDecimal(String.valueOf(txns.get(0).get("BEFORE_QTY")))));
        assertEquals(0, new BigDecimal("50").compareTo(
                new java.math.BigDecimal(String.valueOf(txns.get(0).get("AFTER_QTY")))));

        // 库存不足路径恒等（把可用扣穿不可能：ge 守卫）——余量为 50 的行仍满足恒等
        List<InvStock> rows = stockDao.selectList(new LambdaQueryWrapper<InvStock>()
                .eq(InvStock::getWarehouseCode, WH));
        assertEquals(1, rows.size());
    }
}
