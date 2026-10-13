package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.entity.sd.SdReturn;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.ReworkOrderService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import com.erp.service.sd.ReturnService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 退货可返修判定出口（task 4.1，sales-return delta：judgeHandle 白名单加 REWORKABLE +
 * 场景「可返修判定开出返工工单」——判定→候选→建单→候选退出）。
 */
@SpringBootTest
class SalesReturnReworkDbTest {

    @Autowired
    private ReturnService returnService;
    @Autowired
    private ReworkOrderService reworkOrderService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("sr-rework", "ROLE_SALES_MGR", "ROLE_PLANNER",
                "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_inv_freeze WHERE ID LIKE 'fz-sr-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID LIKE 'st-sr-%'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'ncr-sr-%'");
        jdbc.update("DELETE FROM erp_sd_return_line WHERE RETURN_ID LIKE 'rt-sr-%'");
        jdbc.update("DELETE FROM erp_sd_return WHERE ID LIKE 'rt-sr-%'");
        MoSeed.cleanup(jdbc);
    }

    private String seedDraftReturn(String itemCode, String itemName, String qty) {
        String id = "rt-sr-" + (int) (System.nanoTime() % 1000000);
        jdbc.update("INSERT INTO erp_sd_return (ID, RETURN_NO, CUSTOMER_ID, TOTAL_QTY, STATUS)"
                        + " VALUES (?,?,?,?, 'DRAFT')",
                id, "RT-SR-" + id, "CUST-SR", new BigDecimal(qty));
        jdbc.update("INSERT INTO erp_sd_return_line (ID, RETURN_ID, LINE_NO, ITEM_CODE, ITEM_NAME,"
                        + " QTY, JUDGE_QTY, STOCK_BATCH_NO) VALUES (?,?,1,?,?,?,0,?)",
                id + "-L1", id, itemCode, itemName, new BigDecimal(qty), "B-SR");
        return id;
    }

    private void publishReworkRoute(MdmItem product) {
        int n = (int) (System.nanoTime() % 100000);
        String opCode = "SROP-" + n;
        String wcCode = "SRWC-" + n;
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("退货返修工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("退货返修中心");
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(BigDecimal.ONE);
        row.setStandardYield(new BigDecimal("98"));
        row.setKeyOpFlag("0");
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        head.setRouteType(MrpRouting.T_REWORK);
        MrpRouting draft = routingService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PENDING' WHERE ID=?", draft.getId());
        routingService.publishApproved(draft.getId());
    }

    @Test
    void judgeReworkableOpensRewOrder() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "退货返修产品", "MAKE");
        String code = product.getItemCode();
        publishReworkRoute(product);

        // ① 白名单：REWORKABLE 判定通过（原 REFUND/EXCHANGE 之外第三值）
        String retId = seedDraftReturn(code, "退货返修产品", "5");
        String retNo = jdbc.queryForObject("SELECT RETURN_NO FROM erp_sd_return WHERE ID=?",
                String.class, retId);
        Map<String, Object> payload = Map.of(
                "liability", "我方质量",
                "handle", "REWORKABLE",
                "basis", "复检确认可返修（测试）",
                "lines", List.of(Map.of("lineId", retId + "-L1", "judgeQty", 3)));
        SdReturn judged = returnService.judge(retId, payload);
        assertEquals("REWORKABLE", judged.getJudgeHandle(), "判定结论=可返修");
        assertEquals("JUDGED", judged.getStatus(), "判定完成");

        // ② 候选出现
        assertTrue(reworkOrderService.sourceCandidates("RETURN").stream()
                        .anyMatch(m -> retNo.equals(m.get("srcNo"))),
                "可返修退货单入建单候选：" + retNo);

        // ③ 开出返工工单 → 候选退出（场景「可返修判定开出返工工单」）
        String ncrId = "ncr-sr-" + (int) (System.nanoTime() % 1000000);
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY,"
                        + " DISPOSITION, STATUS) VALUES (?,?,?,?,?,?, 'REWORK', 'REWORKING')",
                ncrId, "NCR-SR-" + ncrId, code, "退货返修产品", "B-SR",
                new BigDecimal("3"));
        // 库存与冻结（转在制前置）
        jdbc.update("INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, QC_QTY,"
                        + " AVAILABLE_QTY, WIP_QTY) VALUES (?,?,?,?,3,3,0,0)",
                "st-sr-" + (int) (System.nanoTime() % 1000000), code, "SR种子", "B-SR");
        jdbc.update("INSERT INTO erp_inv_freeze (ID, FREEZE_NO, FREEZE_TYPE, WAREHOUSE_CODE,"
                        + " ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, REASON, SCOPE, STATUS, APPLY_BY)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?, 'ACTIVE', 'test')",
                "fz-sr-" + (int) (System.nanoTime() % 1000000), "FZ-SR", "QUALITY", "WH-MAIN",
                code, "SR种子", "B-SR", new BigDecimal("3"), "退货返修种子", "BATCH");

        MrpMo h = new MrpMo();
        h.setSrcType("RETURN");
        h.setSrcNo(retNo);
        h.setProductCode(code);
        h.setQty(new BigDecimal("3"));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(5));
        MrpMo rw = reworkOrderService.create(h);
        assertTrue(rw.getMoNo().startsWith("RW-"), "退货来源建单 RW- 前缀");
        assertTrue(reworkOrderService.sourceCandidates("RETURN").stream()
                        .noneMatch(m -> retNo.equals(m.get("srcNo"))),
                "已开单后候选退出");

        // ④ REFUND 判定仍可用（白名单回归）
        String retId2 = seedDraftReturn(code, "退货返修产品", "2");
        Map<String, Object> refund = Map.of(
                "liability", "客户原因",
                "handle", "REFUND",
                "basis", "退款判定（测试）",
                "lines", List.of(Map.of("lineId", retId2 + "-L1", "judgeQty", 1)));
        assertEquals("REFUND", returnService.judge(retId2, refund).getJudgeHandle(),
                "REFUND 判定回归可用");

        // ⑤ 非法值仍 422
        String retId3 = seedDraftReturn(code, "退货返修产品", "1");
        var bad = assertThrows(ServiceException.class, () -> returnService.judge(retId3,
                Map.of("liability", "我方质量", "handle", "PICK",
                        "basis", "非法值",
                        "lines", List.of(Map.of("lineId", retId3 + "-L1", "judgeQty", 1)))));
        assertEquals(422, bad.getCode(), "非法处理方式 422：" + bad.getMessage());
    }
}
