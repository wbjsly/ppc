package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpFeed;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpReporting;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.ReportingService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投料即扣与退回库测（task 2.3）：投料扣 35 可用同步减（spec「投料即扣」）、
 * 负库存 422 余量护栏（spec「退回护栏」前置）、冲销回加恢复原值 + 幂等防双退、
 * CONFIRMED 后投料 422（requireOpen 统一守卫）。
 */
@SpringBootTest
class ReportingFeedDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private ReportingService reportingService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private MdmItem product;
    private MdmItem child;
    private MrpMo mo;
    private MrpDispatch dispatch;

    private static final LocalDate START = LocalDate.of(2028, 9, 1);
    private static final LocalDate END = LocalDate.of(2028, 9, 28);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("feed-tester", "ROLE_SHOP_FLOOR", "ROLE_PLANNER", "ROLE_ADMIN");

        product = MoSeed.mkItem(itemService, categoryCode, "投料测试产品", "MAKE");
        child = MoSeed.mkItem(itemService, categoryCode, "投料测试子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "100");
        mo = MoSeed.releaseMo(moService, product, "10", START, END);

        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> mo.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_mrp_feed");
        jdbc.update("DELETE FROM erp_mrp_reporting");
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        MoSeed.cleanup(jdbc);
    }

    private BigDecimal avail() {
        BigDecimal v = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=?",
                BigDecimal.class, child.getItemCode());
        return v == null ? BigDecimal.ZERO : v;
    }

    @Test
    void feedDeductsGuardAndReverseRestores() {
        // 基准 = seed 后实际可用（100 − 派工确认预扣 10 = 90，预扣实扣是 5.6 既有行为）
        BigDecimal base = avail();

        // 投料即扣：35
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        MrpFeed feed = reportingService.addFeed(rpt.getId(), child.getItemCode(), "B-FEED", new BigDecimal("35"));
        assertEquals(0, avail().compareTo(base.subtract(new BigDecimal("35"))), "投料 35 后可用同步减");
        assertEquals(0, feed.getQty().compareTo(new BigDecimal("35")), "投料行数量");
        assertEquals(MrpFeed.FLOW_HAND, feed.getFlowType(), "手工投料流");
        assertEquals("0", feed.getReturnedFlag(), "未回加标记");
        assertEquals(1, reportingService.feedsOf(rpt.getId()).size(), "投料行回显");

        // 负库存护栏：可用（base−35），投 base → 422，且可用不变（不产生负库存）
        ServiceException e = assertThrows(ServiceException.class,
                () -> reportingService.addFeed(rpt.getId(), child.getItemCode(), null, base));
        assertEquals(422, e.getCode(), "可用量不足 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("可用量不足"), "护栏提示：" + e.getMessage());
        assertEquals(0, avail().compareTo(base.subtract(new BigDecimal("35"))), "护栏拒绝后可用不变");

        // 确认后投料 422（只读守卫）
        reportingService.confirm(rpt.getId());
        ServiceException ro = assertThrows(ServiceException.class,
                () -> reportingService.addFeed(rpt.getId(), child.getItemCode(), null, BigDecimal.ONE));
        assertEquals(422, ro.getCode(), "CONFIRMED 后投料 → 422");

        // 冲销回加：恢复到基准值，RETURNED_FLAG 幂等
        int n = reportingService.reverseFeeds(rpt.getId(), "测试冲销");
        assertEquals(1, n, "回加 1 行");
        assertEquals(0, avail().compareTo(base), "回加恢复原值");
        int again = reportingService.reverseFeeds(rpt.getId(), "重复冲销");
        assertEquals(0, again, "幂等：二次回加 0 行");
        assertEquals(0, avail().compareTo(base), "幂等后可用仍为基准");
    }

    @Test
    void feedInvalidArgs422() {
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        ServiceException noItem = assertThrows(ServiceException.class,
                () -> reportingService.addFeed(rpt.getId(), " ", null, BigDecimal.ONE));
        assertEquals(422, noItem.getCode(), "物料必填 422");
        ServiceException badQty = assertThrows(ServiceException.class,
                () -> reportingService.addFeed(rpt.getId(), child.getItemCode(), null, BigDecimal.ZERO));
        assertEquals(422, badQty.getCode(), "数量须 > 0 → 422");
    }
}
