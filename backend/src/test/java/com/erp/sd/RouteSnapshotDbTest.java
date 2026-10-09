package com.erp.sd;

import com.erp.dao.sd.ShipmentDao;
import com.erp.entity.sd.Shipment;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 配送线路快照 DB 集成（真实 MySQL，spec wave-management 线路挂载，任务 2.2）：
 * 建单时从客户默认线带出快照；客户改线不影响已开发货单（快照语义）。
 */
@SpringBootTest
class RouteSnapshotDbTest {

    private static final String ITEM = "IT-RS-01";
    private static final String RT1 = "rs-route-1";
    private static final String RT2 = "rs-route-2";
    private static final String CUST = "rs-cust-1";

    @Autowired
    private ShipmentService shipmentService;
    @Autowired
    private ShipmentDao shipDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_WAREHOUSE"))));

        // 线路两条 + 客户挂 RT1
        jdbc.update("INSERT INTO erp_inv_route (ID, ROUTE_CODE, ROUTE_NAME, STATUS, CREATE_BY) VALUES "
                + "('rs-route-1','RS-R1','快照测试线路一','ACTIVE','junit'), "
                + "('rs-route-2','RS-R2','快照测试线路二','ACTIVE','junit')");
        jdbc.update("INSERT INTO erp_mdm_customer_group (ID, CUSTOMER_CODE, CUSTOMER_NAME, ROUTE_ID, "
                + "STATUS, CREATE_BY) VALUES (?, 'CUST-RS', '快照测试客户', ?, '1', 'junit')", CUST, RT1);

        // SO CONFIRMED + 行 + ACTIVE 预留（shipable = min(remain, reserved)）
        jdbc.update("INSERT INTO erp_sd_so (ID, SO_NO, CUSTOMER_ID, CUSTOMER_CODE, CUSTOMER_NAME, "
                + "STATUS, CREATE_BY) VALUES ('rs-so-1','SO-RS-1',?,'CUST-RS','快照测试客户',"
                + "'CONFIRMED','junit')", CUST);
        jdbc.update("INSERT INTO erp_sd_so_line (ID, SO_ID, LINE_NO, ITEM_CODE, ITEM_NAME, QTY, "
                + "SHIPPED_QTY, WAREHOUSE_CODE, CREATE_BY) VALUES ('rs-sol-1','rs-so-1',1,?,'快照物料',"
                + "10,0,'WH-MAIN','junit')", ITEM);
        jdbc.update("INSERT INTO erp_sd_reservation (ID, SO_ID, LINE_ID, ITEM_CODE, QTY, STATUS, "
                + "BATCH_NO, LOCK_AT, CREATE_BY) VALUES ('rs-rsv-1','rs-so-1','rs-sol-1',?,10,"
                + "'ACTIVE','',?,'junit')", ITEM, LocalDateTime.now());
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    @Test
    void shipmentSnapshotsCustomerRouteAndSurvivesCustomerChange() {
        // 第一张：带出 RT1
        Map<String, Object> res = shipmentService.generatePartial("rs-so-1", null);
        com.erp.entity.sd.Shipment ship = (com.erp.entity.sd.Shipment) res.get("shipment");
        String shipId = ship.getId();
        String snap1 = jdbc.queryForObject("SELECT ROUTE_ID FROM erp_sd_shipment WHERE ID = ?",
                String.class, shipId);
        assertEquals(RT1, snap1, "建单时快照客户默认线路");

        // 客户改挂 RT2 → 已开发货单不受影响（spec 场景）
        jdbc.update("UPDATE erp_mdm_customer_group SET ROUTE_ID = ? WHERE ID = ?", RT2, CUST);
        String snapAfter = jdbc.queryForObject("SELECT ROUTE_ID FROM erp_sd_shipment WHERE ID = ?",
                String.class, shipId);
        assertEquals(RT1, snapAfter, "客户改线不影响已开发货单快照");
    }

    @Test
    void unroutedCustomerSnapshotsNull() {
        jdbc.update("UPDATE erp_mdm_customer_group SET ROUTE_ID = NULL WHERE ID = ?", CUST);
        Map<String, Object> res = shipmentService.generatePartial("rs-so-1", null);
        com.erp.entity.sd.Shipment ship = (com.erp.entity.sd.Shipment) res.get("shipment");
        String snap = jdbc.queryForObject("SELECT ROUTE_ID FROM erp_sd_shipment WHERE ID = ?",
                String.class, ship.getId());
        assertNull(snap, "未挂线客户快照为空（聚类按承运商>客户降级，不报错）");
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sd_shipment_line WHERE SHIP_ID IN "
                + "(SELECT ID FROM erp_sd_shipment WHERE SHIP_NO LIKE 'SH%') "
                + "AND ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_sd_shipment WHERE ID LIKE 'rs-%' OR "
                + "CUSTOMER_ID = ?", CUST);
        jdbc.update("DELETE FROM erp_sd_reservation WHERE ID = 'rs-rsv-1'");
        jdbc.update("DELETE FROM erp_sd_so_line WHERE ID = 'rs-sol-1'");
        jdbc.update("DELETE FROM erp_sd_so WHERE ID = 'rs-so-1'");
        jdbc.update("DELETE FROM erp_mdm_customer_group WHERE ID = ?", CUST);
        jdbc.update("DELETE FROM erp_inv_route WHERE ID IN ('rs-route-1','rs-route-2')");
    }
}
