package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.mrp.MrpFeed;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.entity.mrp.MrpReporting;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.KittingService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.ReworkOrderService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 返工工单库测（change add-rework-management，rework-order + rework-execution 选路族）。
 * 2.1 载体编码/在途分池；2.2 判定双接入；2.3 卡控三查；2.4 转在制三池守恒；
 * 2.5 禁拆分+挂起取消；2.6 阶段推导+列表分池；3.2 选路阻断与工序快照。
 */
@SpringBootTest
class ReworkOrderDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private ReworkOrderService reworkOrderService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private KittingService kittingService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private ReportingService reportingService;
    @Autowired
    private com.erp.service.SysParamService sysParam;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private com.erp.service.mrp.MoFreezeService freezeService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private String opCode;
    private String wcCode;
    private final List<String> touchedProducts = new ArrayList<>();

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("rw-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");

        int n = (int) (System.nanoTime() % 100000);
        opCode = "RWOP-" + n;
        wcCode = "RWWC-" + n;
        MrpOperation op = new MrpOperation();
        op.setOpCode(opCode);
        op.setOpName("返工测试工序");
        operationService.create(op);
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("返工测试中心");
        workCenterService.create(wc);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(opCode);
        std.setWcCode(wcCode);
        std.setSetupHours(new BigDecimal("0.5"));
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_inv_transaction WHERE BIZ_DOC_TYPE LIKE 'REWORK_%'");
        jdbc.update("DELETE FROM erp_qms_copq_entry WHERE REMARK LIKE 'REWORK:%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('REWORK_ECON','REWORK_SCRAP','REWORK_SECOND_RESULT')");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE ID LIKE 'fz-rw-%'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID LIKE 'st-rw-%'");
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID IN "
                + "(SELECT ID FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER')");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'ncr-rw-%'");
        jdbc.update("DELETE FROM erp_sd_return_line WHERE RETURN_ID LIKE 'rt-rw-%'");
        jdbc.update("DELETE FROM erp_sd_return WHERE ID LIKE 'rt-rw-%'");
        jdbc.update("DELETE FROM erp_qms_inspection_lot WHERE REF_TYPE='WORKORDER'");
        jdbc.update("DELETE FROM erp_mrp_feed");
        jdbc.update("DELETE FROM erp_mrp_reporting");
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE FROM erp_mrp_reservation");
        jdbc.update("DELETE FROM erp_mrp_mo_shortage");
        for (String code : touchedProducts) {
            jdbc.update("DELETE FROM erp_mrp_mo_op WHERE MO_ID IN "
                    + "(SELECT ID FROM erp_mrp_mo WHERE PRODUCT_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_mo WHERE PRODUCT_CODE=?", code);
            jdbc.update("DELETE FROM erp_mrp_routing_op WHERE ROUTING_ID IN "
                    + "(SELECT ID FROM erp_mrp_routing WHERE ITEM_CODE=?)", code);
            jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE=?", code);
        }
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE=?", opCode);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE=?", wcCode);
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE=?", opCode);
        MoSeed.cleanup(jdbc);
    }

    // ---------- 种子助手 ----------

    private int seq() {
        return (int) (System.nanoTime() % 1000000);
    }

    private MdmItem mkMake(String name) {
        MdmItem p = MoSeed.mkItem(itemService, categoryCode, name, "MAKE");
        touchedProducts.add(p.getItemCode());
        return p;
    }

    /** 带已发布 BOM 的产品（create 入口前置） */
    private MdmItem productWithBom(String name) {
        MdmItem p = mkMake(name);
        MdmItem child = mkMake(name + "子件");
        MoSeed.publishBom(bomService, jdbc, p, child, "1", "0");
        return p;
    }

    /** 发布单行 REWORK 路线（FR-4.5-7-3 选路前置） */
    private void publishReworkRoute(MdmItem product) {
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

    /** 造 NCR（处置=返工） */
    private String mkNcr(String itemCode, String itemName, String batch, String qty,
                         String disposition, String status) {
        String id = "ncr-rw-" + seq();
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY,"
                        + " DISPOSITION, STATUS) VALUES (?,?,?,?,?,?,?,?)",
                id, "NCR-RW-" + id, itemCode, itemName, batch, new BigDecimal(qty), disposition, status);
        return "NCR-RW-" + id;
    }

    /** 造库存行（QC/可用分池，QTY = QC + AVAIL） */
    private void mkQcStock(String itemCode, String batch, String qc, String avail) {
        jdbc.update("INSERT INTO erp_inv_stock (ID, ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, QC_QTY,"
                        + " AVAILABLE_QTY, WIP_QTY) VALUES (?,?,?,?,?,?,?,0)",
                "st-rw-" + seq(), itemCode, "RW种子", batch,
                new BigDecimal(qc).add(new BigDecimal(avail)), new BigDecimal(qc), new BigDecimal(avail));
    }

    /** 造冻结行（ACTIVE） */
    private void mkFreeze(String itemCode, String batch, String type, String qty) {
        jdbc.update("INSERT INTO erp_inv_freeze (ID, FREEZE_NO, FREEZE_TYPE, WAREHOUSE_CODE,"
                        + " ITEM_CODE, ITEM_NAME, BATCH_NO, QTY, REASON, SCOPE, STATUS, APPLY_BY)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?, 'ACTIVE', 'test')",
                "fz-rw-" + seq(), "FZ-RW-" + seq(), type, "WH-MAIN",
                itemCode, "RW种子", batch, new BigDecimal(qty), "返工种子冻结", "BATCH");
    }

    /** 造可返修退货单 + 行 */
    private String mkReturn(String itemCode, String itemName, String batch, String judgeQty) {
        String id = "rt-rw-" + seq();
        String no = "RT-RW-" + id;
        jdbc.update("INSERT INTO erp_sd_return (ID, RETURN_NO, CUSTOMER_ID, TOTAL_QTY, STATUS)"
                        + " VALUES (?,?,?,?, 'JUDGED')",
                id, no, "CUST-RW", new BigDecimal(judgeQty));
        jdbc.update("UPDATE erp_sd_return SET JUDGE_HANDLE='REWORKABLE' WHERE ID=?", id);
        jdbc.update("INSERT INTO erp_sd_return_line (ID, RETURN_ID, LINE_NO, ITEM_CODE, ITEM_NAME,"
                        + " QTY, JUDGE_QTY, STOCK_BATCH_NO) VALUES (?,?,1,?,?,?,?,?)",
                id + "-L1", id, itemCode, itemName, new BigDecimal(judgeQty),
                new BigDecimal(judgeQty), batch);
        return no;
    }

    private MrpMo createNormal(MdmItem product, String qty) {
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(7));
        return (MrpMo) moService.create(h).get("mo");
    }

    private MrpMo reworkCreate(String srcType, String srcNo, String productCode, String qty) {
        MrpMo h = new MrpMo();
        h.setSrcType(srcType);
        h.setSrcNo(srcNo);
        h.setProductCode(productCode);
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(5));
        return reworkOrderService.create(h);
    }

    /** 直插返工在途行（2.1 编号口） */
    private MrpMo insertRw(MdmItem product, String qty) {
        MrpMo rw = new MrpMo();
        rw.setProductCode(product.getItemCode());
        rw.setProductName(product.getItemName());
        rw.setQty(new BigDecimal(qty));
        rw.setMoType(MrpMo.MO_TYPE_RW);
        rw.setStatus(MrpMo.ST_RELEASED);
        rw.setPriority(5);
        rw.setShortageFlag("0");
        rw.setPlanStartDate(LocalDate.now());
        rw.setPlanEndDate(LocalDate.now().plusDays(7));
        return moService.insertWithMoNo(rw);
    }

    // ================= 2.1 载体与编码分池 =================

    @Test
    void rwNumberingPoolAndTransitSplit() {
        MdmItem product = productWithBom("返工载体产品");

        MrpMo normal = createNormal(product, "10");
        assertTrue(normal.getMoNo().startsWith("MO-"), "正常工单 MO- 前缀：" + normal.getMoNo());
        assertEquals("NORMAL", jdbc.queryForObject(
                "SELECT MO_TYPE FROM erp_mrp_mo WHERE ID=?", String.class, normal.getId()),
                "MO_TYPE 缺省 NORMAL");

        MrpMo rw1 = insertRw(product, "3");
        assertTrue(rw1.getMoNo().startsWith("RW-"), "返工工单 RW- 前缀：" + rw1.getMoNo());
        MrpMo rw2 = insertRw(product, "2");
        assertTrue(rw2.getMoNo().startsWith("RW-"));
        String n1 = rw1.getMoNo().substring(rw1.getMoNo().length() - 3);
        String n2 = rw2.getMoNo().substring(rw2.getMoNo().length() - 3);
        assertTrue(Integer.parseInt(n2) == Integer.parseInt(n1) + 1,
                "RW 序列独立连续：" + rw1.getMoNo() + " → " + rw2.getMoNo());

        // RW 在途不阻断同产品 NORMAL 创建（分池）
        MdmItem productRwOnly = productWithBom("仅返工在途产品");
        insertRw(productRwOnly, "3");
        MrpMo another = createNormal(productRwOnly, "5");
        assertTrue(another.getMoNo().startsWith("MO-"), "RW 在途时正常建单放行");

        // 反向：NORMAL 在途时 RW 插入照常
        MdmItem product2 = productWithBom("反向分池产品");
        createNormal(product2, "7");
        MrpMo rw3 = insertRw(product2, "1");
        assertTrue(rw3.getMoNo().startsWith("RW-"), "NORMAL 在途时 RW 插入放行");

        // NORMAL 池在途阻断仍生效
        MdmItem product3 = productWithBom("在途阻断产品");
        createNormal(product3, "4");
        var blocked = assertThrows(ServiceException.class,
                () -> createNormal(product3, "2"));
        assertEquals(422, blocked.getCode(), "NORMAL 池唯一在途阻断仍生效：" + blocked.getMessage());
    }

    // ================= 2.2 判定来源双接入 =================

    @Test
    void sourceCandidatesBindAndDuplicateBlocked() {
        MdmItem product = productWithBom("来源绑定产品");
        String code = product.getItemCode();
        publishReworkRoute(product);

        String ncrNo = mkNcr(code, "来源绑定产品", "B-RW1", "5", "REWORK", "REWORKING");
        List<Map<String, Object>> ncrs = reworkOrderService.sourceCandidates("NCR");
        assertTrue(ncrs.stream().anyMatch(m -> ncrNo.equals(m.get("srcNo"))),
                "处置=返工的 NCR 出现在候选：" + ncrNo);
        String otherNo = mkNcr(code, "来源绑定产品", "B-RW1", "2", "SCRAP", "CREATED");
        assertTrue(ncrs.stream().noneMatch(m -> otherNo.equals(m.get("srcNo"))),
                "非返工处置 NCR 不入候选");
        String rtNo = mkReturn(code, "来源绑定产品", "B-RW1", "3");
        List<Map<String, Object>> rets = reworkOrderService.sourceCandidates("RETURN");
        assertTrue(rets.stream().anyMatch(m -> rtNo.equals(m.get("srcNo"))),
                "可返修退货单出现在候选：" + rtNo);

        mkQcStock(code, "B-RW1", "10", "0");
        mkFreeze(code, "B-RW1", "QUALITY", "10");
        MrpMo rw = reworkCreate("NCR", ncrNo, code, "3");
        assertTrue(rw.getMoNo().startsWith("RW-"));
        assertEquals("NCR", rw.getSrcType());
        assertEquals(0, rw.getSrcQty().compareTo(new BigDecimal("5")), "带出判定批准数量");

        var dup = assertThrows(ServiceException.class, () -> reworkCreate("NCR", ncrNo, code, "1"));
        assertEquals(422, dup.getCode(), "重复判定单 422：" + dup.getMessage());
        assertTrue(reworkOrderService.sourceCandidates("NCR").stream()
                        .noneMatch(m -> ncrNo.equals(m.get("srcNo"))),
                "已关联判定单退出候选");

        var missing = assertThrows(ServiceException.class,
                () -> reworkCreate("NCR", "NCR-NO-SUCH", code, "1"));
        assertEquals(422, missing.getCode(), "不存在判定单 422");
        var wrongKind = assertThrows(ServiceException.class,
                () -> reworkCreate("RETURN", otherNo, code, "1"));
        assertEquals(422, wrongKind.getCode(), "非可返修退货单 422：" + wrongKind.getMessage());
        MrpMo fromReturn = reworkCreate("RETURN", rtNo, code, "2");
        assertTrue(fromReturn.getMoNo().startsWith("RW-"), "退货来源建单成功");
    }

    // ================= 2.3 创建卡控三查（卡控先于选路，无需路线种子） =================

    @Test
    void controlTriplesBlockCreate() {
        String srcNo = mkNcr("RW-ITM-B", "返工投入B", "B-RW2", "4", "REWORK", "REWORKING");

        var noState = assertThrows(ServiceException.class,
                () -> reworkCreate("NCR", srcNo, "RW-ITM-B", "2"));
        assertEquals(422, noState.getCode(), "非冻结/待处理 422：" + noState.getMessage());
        assertTrue(noState.getMessage().contains("冻结"), "提示核对判定单：" + noState.getMessage());

        mkQcStock("RW-ITM-B", "B-RW2", "4", "0");
        mkFreeze("RW-ITM-B", "B-RW2", "FINANCE", "4");
        var finance = assertThrows(ServiceException.class,
                () -> reworkCreate("NCR", srcNo, "RW-ITM-B", "2"));
        assertEquals(422, finance.getCode(), "财务冻结 422：" + finance.getMessage());
        assertTrue(finance.getMessage().contains("财务冻结"), "提示先解冻：" + finance.getMessage());

        jdbc.update("UPDATE erp_inv_freeze SET FREEZE_TYPE='QUALITY' WHERE ID LIKE 'fz-rw-%'");
        var over = assertThrows(ServiceException.class,
                () -> reworkCreate("NCR", srcNo, "RW-ITM-B", "5"));
        assertEquals(422, over.getCode(), "超判定数量 422：" + over.getMessage());
        assertTrue(over.getMessage().contains("超过判定单批准数量"), "提示批准量：" + over.getMessage());

        jdbc.update("UPDATE erp_inv_stock SET QC_QTY=1, QTY=1 WHERE ID LIKE 'st-rw-%'");
        var insufficient = assertThrows(ServiceException.class,
                () -> reworkCreate("NCR", srcNo, "RW-ITM-B", "3"));
        assertEquals(422, insufficient.getCode(), "QC 不足 422：" + insufficient.getMessage());

        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo WHERE SRC_NO=?", Long.class, srcNo),
                "卡控失败不产生工单");
    }

    // ================= 2.4 转在制过账 =================

    @Test
    void wipTransferConservationAndPin() {
        MdmItem product = productWithBom("转在制产品");
        String code = product.getItemCode();
        publishReworkRoute(product);
        String srcNo = mkNcr(code, "转在制产品", "B-RW3", "5", "REWORK", "REWORKING");
        mkQcStock(code, "B-RW3", "5", "3");   // QTY=8, QC=5, AVAIL=3
        mkFreeze(code, "B-RW3", "QUALITY", "5");

        BigDecimal qtyBefore = jdbc.queryForObject(
                "SELECT QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RW3'",
                BigDecimal.class, code);
        MrpMo rw = reworkCreate("NCR", srcNo, code, "3");

        BigDecimal qc = jdbc.queryForObject(
                "SELECT QC_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RW3'",
                BigDecimal.class, code);
        BigDecimal wip = jdbc.queryForObject(
                "SELECT WIP_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RW3'",
                BigDecimal.class, code);
        BigDecimal qtyAfter = jdbc.queryForObject(
                "SELECT QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RW3'",
                BigDecimal.class, code);
        assertEquals(0, qc.compareTo(new BigDecimal("2")), "QC 5−3=2：" + qc);
        assertEquals(0, wip.compareTo(new BigDecimal("3")), "WIP 0+3=3：" + wip);
        assertEquals(0, qtyBefore.compareTo(qtyAfter), "QTY 总量不变（三池守恒）");
        assertEquals(0, qc.add(wip).add(new BigDecimal("3")).compareTo(qtyAfter),
                "QC+AVAIL+WIP = QTY：" + qc + "+" + wip + "+3=" + qtyAfter);

        Map<String, Object> fz = jdbc.queryForMap(
                "SELECT STATUS, REF_MO_ID FROM erp_inv_freeze WHERE ITEM_CODE=? AND BATCH_NO='B-RW3'",
                code);
        assertEquals("ACTIVE", fz.get("STATUS"), "冻结行仍 ACTIVE");
        assertEquals(rw.getId(), fz.get("REF_MO_ID"), "REF_MO_ID 钉住本工单");

        Map<String, Object> txn = jdbc.queryForMap(
                "SELECT BIZ_DOC_NO, QTY FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='REWORK_WIP'");
        assertEquals(rw.getMoNo(), txn.get("BIZ_DOC_NO"), "流水关联返工工单号");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT MO_TYPE, SRC_TYPE, SRC_QTY FROM erp_mrp_mo WHERE ID=?", rw.getId());
        assertEquals("RW", row.get("MO_TYPE"));
        assertEquals("NCR", row.get("SRC_TYPE"));
        assertEquals(0, new BigDecimal(String.valueOf(row.get("SRC_QTY"))).compareTo(new BigDecimal("5")),
                "SRC_QTY=判定批准 5");
    }

    // ================= 2.5 变更能力边界 =================

    @Test
    void rwSplitBlockedHoldCancelApply() {
        MdmItem product = productWithBom("拆分边界产品");
        String code = product.getItemCode();
        publishReworkRoute(product);
        String srcNo = mkNcr(code, "拆分边界产品", "B-RW4", "4", "REWORK", "REWORKING");
        mkQcStock(code, "B-RW4", "4", "0");
        mkFreeze(code, "B-RW4", "QUALITY", "4");
        MrpMo rw = reworkCreate("NCR", srcNo, code, "3");

        var split = assertThrows(ServiceException.class,
                () -> moService.split(rw.getId(), List.of(new BigDecimal("1")), "测试拆分"));
        assertEquals(422, split.getCode(), "RW 拆分 422：" + split.getMessage());
        assertTrue(split.getMessage().contains("返工工单不支持拆分"), "提示锚定判定单：" + split.getMessage());

        moService.submit(rw.getId());
        moService.onApproved(rw.getId());
        moService.release(rw.getId());
        moService.hold(rw.getId(), "返工暂停待料");
        assertEquals("HOLD", jdbc.queryForObject("SELECT STATUS FROM erp_mrp_mo WHERE ID=?",
                String.class, rw.getId()), "RW 挂起成功");
        moService.resume(rw.getId());
        assertEquals("RELEASED", jdbc.queryForObject("SELECT STATUS FROM erp_mrp_mo WHERE ID=?",
                String.class, rw.getId()), "RW 恢复成功");
        moService.cancel(rw.getId(), "判定作废");
        assertEquals("CANCELLED", jdbc.queryForObject("SELECT STATUS FROM erp_mrp_mo WHERE ID=?",
                String.class, rw.getId()), "RW 取消成功");
    }

    // ================= 2.6 阶段推导与列表分池 =================

    @Test
    void phaseMatrixAndPoolVisibility() {
        MdmItem product = productWithBom("阶段推导产品");
        String code = product.getItemCode();
        publishReworkRoute(product);
        String srcNo = mkNcr(code, "阶段推导产品", "B-RW5", "4", "REWORK", "REWORKING");
        mkQcStock(code, "B-RW5", "4", "0");
        mkFreeze(code, "B-RW5", "QUALITY", "4");
        MrpMo rw = reworkCreate("NCR", srcNo, code, "3");

        assertEquals("PLANNED", reworkOrderService.phaseOf(rw.getId()), "非 COMPLETED 返回状态");

        jdbc.update("UPDATE erp_mrp_mo SET STATUS='COMPLETED' WHERE ID=?", rw.getId());
        assertEquals("PENDING_INSP", reworkOrderService.phaseOf(rw.getId()), "待检验推导");

        jdbc.update("INSERT INTO erp_qms_inspection_lot (ID, LOT_NO, ITEM_CODE, LOT_TYPE, REF_TYPE, REF_ID, STATUS)"
                        + " VALUES (?, 'LOT-RW-T', ?, 'IPQC', 'WORKORDER', ?, 'INPUTTING')",
                "lot-rw-" + seq(), code, rw.getId() + "#REWORK");
        assertEquals("PENDING_INSP", reworkOrderService.phaseOf(rw.getId()), "批未出结论待检验");

        jdbc.update("UPDATE erp_qms_inspection_lot SET RESULT='PASS', STATUS='RELEASED'"
                        + " WHERE REF_ID=?", rw.getId() + "#REWORK");
        assertEquals("PENDING_STOCK_IN", reworkOrderService.phaseOf(rw.getId()), "待入库推导");

        jdbc.update("UPDATE erp_inv_freeze SET STATUS='RELEASED', REF_MO_ID=NULL"
                        + " WHERE REF_MO_ID=?", rw.getId());
        assertEquals("CLOSED", reworkOrderService.phaseOf(rw.getId()), "闭环后推导 CLOSED");

        // 正常工单 COMPLETED 不走推导
        MdmItem p = productWithBom("推导对照产品");
        MrpMo normal = createNormal(p, "4");
        jdbc.update("UPDATE erp_mrp_mo SET STATUS='COMPLETED' WHERE ID=?", normal.getId());
        assertEquals("COMPLETED", reworkOrderService.phaseOf(normal.getId()),
                "NORMAL 工单不做阶段推导");

        // 列表分池
        List<MrpMo> normalPool = moService.list(null, code, null);
        assertTrue(normalPool.isEmpty(), "NORMAL 池不出现 RW 行：" + normalPool.size());
        List<MrpMo> rwPool = moService.listByType(null, null, "RW");
        assertTrue(rwPool.stream().allMatch(m -> "RW".equals(m.getMoType())), "RW 池仅返工行");
        assertTrue(rwPool.stream().anyMatch(m -> m.getId().equals(rw.getId())), "本单在 RW 池");
    }

    // ================= 3.2 选路阻断与工序快照 =================

    @Test
    void reworkRouteRequiredAndSnapshot() {
        // ① 无已发布 REWORK 路线 → L1 阻断（spec「无适用返工路线先编制」）
        MdmItem noRoute = productWithBom("无返工路线产品");
        String nc = noRoute.getItemCode();
        String srcNo = mkNcr(nc, "无返工路线产品", "B-RW6", "3", "REWORK", "REWORKING");
        mkQcStock(nc, "B-RW6", "3", "0");
        mkFreeze(nc, "B-RW6", "QUALITY", "3");
        var blocked = assertThrows(ServiceException.class, () -> reworkCreate("NCR", srcNo, nc, "2"));
        assertEquals(422, blocked.getCode(), "无路线 422：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("无已发布返工路线"), "提示先编制：" + blocked.getMessage());
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo WHERE SRC_NO=?", Long.class, srcNo), "阻断不产生工单");

        // ② 有路线 → 建单成功且工序快照带入（工时/标准良率/关键标记/定额）
        MdmItem withRoute = productWithBom("选路快照产品");
        String wc = withRoute.getItemCode();
        publishReworkRoute(withRoute);
        String src2 = mkNcr(wc, "选路快照产品", "B-RW7", "3", "REWORK", "REWORKING");
        mkQcStock(wc, "B-RW7", "3", "0");
        mkFreeze(wc, "B-RW7", "QUALITY", "3");
        MrpMo rw = reworkCreate("NCR", src2, wc, "2");

        assertNotEquals(null, rw.getSourceRoutingId(), "sourceRoutingId 关联 REWORK 路线");
        List<MrpMoOp> ops = jdbc.query(
                "SELECT * FROM erp_mrp_mo_op WHERE MO_ID=?", (rs, i) -> {
                    MrpMoOp o = new MrpMoOp();
                    o.setOpSeq(rs.getInt("OP_SEQ"));
                    o.setStandardYield(rs.getBigDecimal("STANDARD_YIELD"));
                    o.setKeyOpFlag(rs.getString("KEY_OP_FLAG"));
                    o.setRunHours(rs.getBigDecimal("RUN_HOURS"));
                    o.setSetupHours(rs.getBigDecimal("SETUP_HOURS"));
                    o.setOpStatus(rs.getString("OP_STATUS"));
                    return o;
                }, rw.getId());
        assertEquals(1, ops.size(), "快照 1 行（路线单行）");
        MrpMoOp snap = ops.get(0);
        assertEquals(0, snap.getStandardYield().compareTo(new BigDecimal("98")), "快照标准良率 98");
        assertEquals("0", snap.getKeyOpFlag(), "快照关键标记");
        assertEquals(0, snap.getRunHours().compareTo(BigDecimal.ONE), "快照定额加工工时 1h");
        assertEquals(0, snap.getSetupHours().compareTo(new BigDecimal("0.5")), "快照定额准备工时 0.5h");
        assertEquals("PENDING", snap.getOpStatus(), "快照工序状态 PENDING");
    }

    // ================= 3.3 返工 BOM 与齐套/投料分支 =================

    @Test
    void kittingInputAddlSplitAndFeedRange() {
        MdmItem product = productWithBom("齐套分支产品");
        String code = product.getItemCode();
        String addlItem = mkMake("追加料件").getItemCode();
        publishReworkRoute(product);

        String srcNo = mkNcr(code, "齐套分支产品", "B-RW8", "3", "REWORK", "REWORKING");
        mkQcStock(code, "B-RW8", "3", "0");
        mkFreeze(code, "B-RW8", "QUALITY", "3");

        // 建单（带追加料清单：1 件/件）
        MrpMoBom addl = new MrpMoBom();
        addl.setItemCode(addlItem);
        addl.setItemName("追加料件");
        addl.setUnitQty(BigDecimal.ONE);
        addl.setLossRate(BigDecimal.ZERO);
        MrpMo h = new MrpMo();
        h.setSrcType("NCR");
        h.setSrcNo(srcNo);
        h.setProductCode(code);
        h.setQty(new BigDecimal("3"));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(5));
        MrpMo rw = reworkOrderService.create(h, List.of(addl));

        // ① BOM 快照两行：INPUT=不合格品自身 / ADDL=追加料
        List<Map<String, Object>> bom = jdbc.queryForList(
                "SELECT ITEM_CODE, LINE_TYPE FROM erp_mrp_mo_bom WHERE MO_ID=? ORDER BY LINE_NO",
                rw.getId());
        assertEquals(2, bom.size(), "BOM 快照 2 行");
        assertEquals("INPUT", bom.get(0).get("LINE_TYPE"), "行1 INPUT 投入");
        assertEquals(code, bom.get(0).get("ITEM_CODE"), "INPUT=不合格品自身");
        assertEquals("ADDL", bom.get(1).get("LINE_TYPE"), "行2 ADDL 追加料");
        assertEquals(addlItem, bom.get(1).get("ITEM_CODE"), "ADDL=追加料件");

        // ② 齐套分支：INPUT 按在制池判满足（钉住 3 ≥ 需求 3，AVAILABLE=0 不误报）；ADDL 缺料
        BigDecimal rate = kittingService.check(rw.getId(), false);
        assertEquals(0, rate.compareTo(new BigDecimal("50.0")),
                "齐套率 50%（INPUT 满足 / ADDL 缺料）：" + rate);
        List<Map<String, Object>> shortages = jdbc.queryForList(
                "SELECT ITEM_CODE FROM erp_mrp_mo_shortage WHERE MO_ID=?", rw.getId());
        assertEquals(1, shortages.size(), "仅 ADDL 缺料 1 行");
        assertEquals(addlItem, shortages.get(0).get("ITEM_CODE"), "缺料=追加料件");

        // ③ 追加料到货 → 复检 100%
        mkQcStock(addlItem, "B-ADDL", "0", "20");
        BigDecimal rate2 = kittingService.check(rw.getId(), false);
        assertEquals(0, rate2.compareTo(BigDecimal.valueOf(100)), "补料后齐套 100%：" + rate2);

        // ④ 派工 RW（释放 → pending → generate → confirm）
        moService.submit(rw.getId());
        moService.onApproved(rw.getId());
        moService.release(rw.getId());
        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> rw.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        assertEquals(1, rows.size(), "RW 待派工序 1 行");
        MrpDispatch dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());

        // 预扣 INPUT 行跳过：无投入品预扣行（WIP 不预扣），ADDL 有
        long inputResv = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_reservation WHERE MO_ID=? AND ITEM_CODE=?",
                Long.class, rw.getId(), code);
        assertEquals(0L, inputResv, "INPUT 行不建预扣（WIP 池口径）");
        long addlResv = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_reservation WHERE MO_ID=? AND ITEM_CODE=?",
                Long.class, rw.getId(), addlItem);
        assertEquals(1L, addlResv, "ADDL 行正常预扣");

        // ⑤ 投料范围：需求 = 3×1×1 = 3
        BigDecimal childAvail = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-ADDL'",
                BigDecimal.class, addlItem);
        assertEquals(0, childAvail.compareTo(new BigDecimal("17")),
                "预扣 3 后可用 17：" + childAvail);
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        // 范围内（0+2 ≤ 3）不需确认
        MrpFeed f1 = reportingService.addFeed(rpt.getId(), addlItem, "B-ADDL", new BigDecimal("2"));
        assertEquals(0, f1.getQty().compareTo(new BigDecimal("2")), "范围内投料 2 成功");
        // 超范围（2+2=4 > 3）→ 422 提示确认
        var over = assertThrows(ServiceException.class,
                () -> reportingService.addFeed(rpt.getId(), addlItem, "B-ADDL", new BigDecimal("2")));
        assertEquals(422, over.getCode(), "超范围 422：" + over.getMessage());
        assertTrue(over.getMessage().contains("超返工 BOM 范围"), "提示确认：" + over.getMessage());
        // overAck=true 放行
        MrpFeed f2 = reportingService.addFeed(rpt.getId(), addlItem, "B-ADDL",
                new BigDecimal("2"), true);
        assertEquals(0, f2.getQty().compareTo(new BigDecimal("2")), "确认后放行");
        BigDecimal after = jdbc.queryForObject(
                "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-ADDL'",
                BigDecimal.class, addlItem);
        assertEquals(0, after.compareTo(new BigDecimal("13")), "投 2+2 后可用 13：" + after);
    }

    // ================= 3.4 返工完工倒冲豁免（BR-4.5-32 / completion-backflush delta） =================

    @Test
    void rwCompletionSkipsBackflush() {
        // 关首件卡控（本测聚焦倒冲豁免）
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        sysParam.setValue("REWORK_LABOR_RATE", "10", "NUMBER", "MRP", "测试费率", "test");
        sysParam.setValue("REWORK_MACHINE_RATE", "20", "NUMBER", "MRP", "测试费率", "test");
        try {
            // 显式产品+子件（3.7 成本归集需要标准成本锚点）
            MdmItem product = mkMake("倒冲豁免产品");
            MdmItem feedChild = mkMake("倒冲豁免子件");
            MoSeed.publishBom(bomService, jdbc, product, feedChild, "1", "0");
            String code = product.getItemCode();
            String childCode = feedChild.getItemCode();
            jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST=100 WHERE ITEM_CODE=?", childCode);
            jdbc.update("UPDATE erp_mdm_item SET STANDARD_COST=50 WHERE ITEM_CODE=?", code);
            publishReworkRoute(product);
            String srcNo = mkNcr(code, "倒冲豁免产品", "B-RW9", "3", "REWORK", "REWORKING");
            mkQcStock(code, "B-RW9", "3", "0");
            mkFreeze(code, "B-RW9", "QUALITY", "3");
            // 无追加料清单 → BOM 仅 INPUT 行；投入可用 0 → 正常倒冲会算出差额 3 并 422 缺料
            MrpMo rw = reworkCreate("NCR", srcNo, code, "3");

            moService.submit(rw.getId());
            moService.onApproved(rw.getId());
            moService.release(rw.getId());
            List<GenReq> rows = dispatchService.pending(null).stream()
                    .filter(r -> rw.getMoNo().equals(r.get("moNo")))
                    .map(r -> new GenReq((String) r.get("moId"),
                            ((Number) r.get("opSeq")).intValue(), null))
                    .toList();
            MrpDispatch dispatch = dispatchService.generate(rows).get(0);
            dispatch = dispatchService.confirm(dispatch.getId());

            // 追加料库存（投料即扣用）+ 子件可用
            mkQcStock(childCode, "B-FEED", "0", "10");
            MrpReporting rpt = reportingService.open(dispatch.getId(), null);
            // 追加料 2 件（BOM 无 ADDL 行 → 超范围，overAck 确认放行；材料成本 2×100=200）
            reportingService.addFeed(rpt.getId(), childCode, "B-FEED", new BigDecimal("2"), true);
            // 工时：准备 0.5 + 加工 1（=标准，偏差 0 直接入账）→ 人工费 1.5×10=15
            reportingService.saveHours(rpt.getId(), new BigDecimal("0.5"),
                    new BigDecimal("1"), BigDecimal.ZERO);
            reportingService.saveOutput(rpt.getId(), new BigDecimal("3"), BigDecimal.ZERO,
                    null, false);
            reportingService.confirm(rpt.getId());

            // 完工：RW 豁免倒冲（不 422 缺料、不产 BFLUSH 行），其余校验照常
            Map<String, Object> out = reportingService.completeMo(rw.getId());
            assertEquals(0, ((Number) out.get("backflushRows")).intValue(),
                    "RW 完工零倒冲行：" + out.get("backflushRows"));
            long bflush = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_mrp_feed WHERE MO_ID=? AND FLOW_TYPE='BFLUSH'",
                    Long.class, rw.getId());
            assertEquals(0L, bflush, "无 BFLUSH 补投行");
            assertEquals("COMPLETED", jdbc.queryForObject(
                    "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, rw.getId()),
                    "完工推进 COMPLETED");
            assertEquals(0, new BigDecimal(String.valueOf(out.get("qualifiedQty")))
                    .compareTo(new BigDecimal("3")), "合格=末道累计 3");

            // ---- 3.5 完工触发复检加严（FR-4.5-7-6）----
            List<Map<String, Object>> lots = jdbc.queryForList(
                    "SELECT LOT_TYPE, SOURCE_TYPE, STRICTNESS, LOT_QTY, STATUS FROM erp_qms_inspection_lot"
                            + " WHERE REF_TYPE='WORKORDER' AND REF_ID=?", rw.getId() + "#REWORK");
            assertEquals(1, lots.size(), "完工自动建复检批 1 个");
            Map<String, Object> lot = lots.get(0);
            assertEquals("RECHECK", lot.get("SOURCE_TYPE"), "复检批 sourceType=RECHECK");
            assertEquals("TIGHTENED", lot.get("STRICTNESS"), "加严一档 TIGHTENED：" + lot.get("STRICTNESS"));
            assertEquals(0, new BigDecimal(String.valueOf(lot.get("LOT_QTY")))
                    .compareTo(new BigDecimal("3")), "批数量=合格产出 3");
            assertNotEquals(null, lot.get("STATUS"), "批状态在位（待检/阻断）");

            // ---- 3.7 料工费归集（FR-4.5-7-7）：200 料 + 15 工 + 0 费 = 215，挂原 NCR COPQ ----
            @SuppressWarnings("unchecked")
            Map<String, Object> cost = (Map<String, Object>) out.get("reworkCost");
            assertNotNull(cost, "完工返回归集明细");
            assertEquals(0, ((BigDecimal) cost.get("totalCost")).compareTo(new BigDecimal("215")),
                    "归集额 215：" + cost.get("totalCost"));
            assertEquals(0, ((BigDecimal) cost.get("materialCost")).compareTo(new BigDecimal("200")),
                    "材料 200");
            assertEquals(0, ((BigDecimal) cost.get("laborCost")).compareTo(new BigDecimal("15")),
                    "人工 15");
            // 单位成本 = 50 + 215/3 = 121.6667
            assertEquals(0, ((BigDecimal) cost.get("unitCostAfter"))
                    .compareTo(new BigDecimal("121.6667")), "单位成本留痕：" + cost.get("unitCostAfter"));
            String ncrId = jdbc.queryForObject("SELECT ID FROM erp_qms_ncr WHERE NCR_NO=?",
                    String.class, srcNo);
            Map<String, Object> copq = jdbc.queryForMap(
                    "SELECT AMOUNT, SOURCE_TYPE, SOURCE_ID, REMARK FROM erp_qms_copq_entry"
                            + " WHERE REMARK LIKE 'REWORK:%' AND DEL_FLAG='0'");
            assertEquals(0, new BigDecimal(String.valueOf(copq.get("AMOUNT")))
                    .compareTo(new BigDecimal("215")), "COPQ 金额 215");
            assertEquals("NCR", copq.get("SOURCE_TYPE"), "挂原判定 NCR 来源");
            assertEquals(ncrId, copq.get("SOURCE_ID"), "sourceId=NCR 主键");
            // 经济性：215 > 重置成本 50×3=150 → 质量主管通知（幂等 1 条）
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='REWORK_ECON' AND BIZ_ID=?",
                    Long.class, rw.getId()), "经济性提示 1 条");

            // 重建式回拨：投料回加后重算 → 仅剩工时 15（无重复行）
            jdbc.update("UPDATE erp_mrp_feed SET RETURNED_FLAG='1' WHERE MO_ID=?", rw.getId());
            reworkOrderService.aggregateCost(rw, new BigDecimal("3"));
            // CopqEntry @TableLogic 逻辑删除——裸 SQL 须过滤 DEL_FLAG（旧 215 行已逻辑删）
            List<Map<String, Object>> copqRows = jdbc.queryForList(
                    "SELECT AMOUNT FROM erp_qms_copq_entry WHERE REMARK LIKE 'REWORK:%' AND DEL_FLAG='0'");
            assertEquals(1, copqRows.size(), "重建后仍 1 行（无重复）");
            assertEquals(0, new BigDecimal(String.valueOf(copqRows.get(0).get("AMOUNT")))
                    .compareTo(new BigDecimal("15")), "回拨后金额 15：" + copqRows.get(0).get("AMOUNT"));

            // 幂等：onCompleted 再次调用不重复建批
            reworkOrderService.onCompleted(rw, new BigDecimal("3"));
            long lotCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_qms_inspection_lot WHERE REF_ID=?",
                    Long.class, rw.getId() + "#REWORK");
            assertEquals(1L, lotCount, "复检批幂等不重复建");

            // 阶段推导：COMPLETED + 未放行批 → PENDING_INSP（spec 待检验）
            assertEquals("PENDING_INSP", reworkOrderService.phaseOf(rw.getId()),
                    "完工后待检验阶段");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
            sysParam.setValue("REWORK_LABOR_RATE", "60", "NUMBER", "MRP", "恢复默认", "test");
            sysParam.setValue("REWORK_MACHINE_RATE", "100", "NUMBER", "MRP", "恢复默认", "test");
        }
    }

    // ================= 3.6 回次与二次审批 + 强制报废（FR-4.5-7-6 / BR-4.5-02） =================

    @Test
    void secondApprovalAndForcedScrap() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        try {
            MdmItem product = productWithBom("回次审批产品");
            String code = product.getItemCode();
            publishReworkRoute(product);
            String srcNo = mkNcr(code, "回次审批产品", "B-RWA", "3", "REWORK", "REWORKING");
            mkQcStock(code, "B-RWA", "3", "0");
            mkFreeze(code, "B-RWA", "QUALITY", "3");

            // 第一张
            MrpMo rw1 = reworkCreate("NCR", srcNo, code, "3");
            assertTrue(rw1.getMoNo().startsWith("RW-"));

            // ① 未审批 → 第二张阻断（spec「二次返工须审批」）
            var noApproval = assertThrows(ServiceException.class,
                    () -> reworkCreate("NCR", srcNo, code, "3"));
            assertEquals(422, noApproval.getCode(), "未审批建第二张 422：" + noApproval.getMessage());
            assertTrue(noApproval.getMessage().contains("二次返工须质量主管审批"),
                    "提示审批：" + noApproval.getMessage());

            // ② 无质量权限发起 → 403
            MoTestAuth.login("rw-noq", "ROLE_PLANNER");
            var forbidden = assertThrows(ServiceException.class,
                    () -> reworkOrderService.applySecond(srcNo));
            assertEquals(403, forbidden.getCode(), "非质量侧发起 403：" + forbidden.getMessage());

            // ③ 质量侧发起 → 幂等 → 主管签核通过
            MoTestAuth.login("rw-q", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
            Map<String, Object> appr = reworkOrderService.applySecond(srcNo);
            assertNotNull(appr.get("apprNo"), "审批实例号");
            Map<String, Object> again = reworkOrderService.applySecond(srcNo);
            assertEquals(appr.get("apprNo"), again.get("apprNo"), "重复发起幂等同实例");
            String taskId = approvalEngine.todo().stream()
                    .filter(t -> "ReworkSecond".equals(t.get("bizType"))
                            && srcNo.equals(t.get("bizId")))
                    .map(t -> String.valueOf(t.get("taskId")))
                    .findFirst().orElse(null);
            assertNotNull(taskId, "二次返工待办存在");
            approvalEngine.pass(taskId, "同意二次返工（测试）");

            // ④ 审批通过 → 第二张可建；轮次转移（账不动、钉住转到 rw2）
            MoTestAuth.login("rw-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
            BigDecimal qcBefore = jdbc.queryForObject(
                    "SELECT QC_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RWA'",
                    BigDecimal.class, code);
            BigDecimal wipBefore = jdbc.queryForObject(
                    "SELECT WIP_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RWA'",
                    BigDecimal.class, code);
            assertEquals(0, qcBefore.compareTo(BigDecimal.ZERO), "rw1 后 QC=0");
            assertEquals(0, wipBefore.compareTo(new BigDecimal("3")), "rw1 后 WIP=3");
            MrpMo rw2 = reworkCreate("NCR", srcNo, code, "3");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT QC_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RWA'",
                    BigDecimal.class, code).compareTo(BigDecimal.ZERO), "rw2 不重复转在制 QC 仍 0");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT WIP_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RWA'",
                    BigDecimal.class, code).compareTo(new BigDecimal("3")), "WIP 仍 3（账不动）");
            assertEquals(rw2.getId(), jdbc.queryForObject(
                    "SELECT REF_MO_ID FROM erp_inv_freeze WHERE ITEM_CODE=? AND BATCH_NO='B-RWA'",
                    String.class, code), "钉住转移到 rw2");

            // ⑤ 第三张阻断（BR-4.5-02 不得第三次）
            var third = assertThrows(ServiceException.class,
                    () -> reworkCreate("NCR", srcNo, code, "1"));
            assertEquals(422, third.getCode(), "第三张 422：" + third.getMessage());
            assertTrue(third.getMessage().contains("不得创建第三次"), "提示不得第三次：" + third.getMessage());

            // ⑥ rw2 完工 → 复检批判 FAIL → 强制报废
            moService.submit(rw2.getId());
            moService.onApproved(rw2.getId());
            moService.release(rw2.getId());
            List<GenReq> rows = dispatchService.pending(null).stream()
                    .filter(r -> rw2.getMoNo().equals(r.get("moNo")))
                    .map(r -> new GenReq((String) r.get("moId"),
                            ((Number) r.get("opSeq")).intValue(), null))
                    .toList();
            MrpDispatch dispatch = dispatchService.generate(rows).get(0);
            dispatch = dispatchService.confirm(dispatch.getId());
            MrpReporting rpt = reportingService.open(dispatch.getId(), null);
            reportingService.saveOutput(rpt.getId(), new BigDecimal("3"), BigDecimal.ZERO,
                    null, false);
            reportingService.confirm(rpt.getId());
            reportingService.completeMo(rw2.getId());
            jdbc.update("UPDATE erp_qms_inspection_lot SET RESULT='FAIL', STATUS='COMPLETED'"
                    + " WHERE REF_ID=?", rw2.getId() + "#REWORK");

            // 未判不合格时不可报废（先试 rw1——第一张非末轮 → 422）
            MoTestAuth.login("rw-q", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
            var notLast = assertThrows(ServiceException.class,
                    () -> reworkOrderService.enforceScrap(rw1.getId()));
            assertEquals(422, notLast.getCode(), "rw1 非末轮不可报废：" + notLast.getMessage());

            reworkOrderService.enforceScrap(rw2.getId());
            Map<String, Object> stock = jdbc.queryForMap(
                    "SELECT QTY, WIP_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RWA'", code);
            assertEquals(0, new BigDecimal(String.valueOf(stock.get("QTY"))).compareTo(BigDecimal.ZERO),
                    "报废后总量 0：" + stock.get("QTY"));
            assertEquals(0, new BigDecimal(String.valueOf(stock.get("WIP_QTY"))).compareTo(BigDecimal.ZERO),
                    "报废后在制 0");
            Map<String, Object> fz = jdbc.queryForMap(
                    "SELECT STATUS, REF_MO_ID FROM erp_inv_freeze WHERE ITEM_CODE=? AND BATCH_NO='B-RWA'", code);
            assertEquals("RELEASED", fz.get("STATUS"), "冻结行释放（报废终局）");
            assertNull(fz.get("REF_MO_ID"), "钉住解除");
            assertEquals("CLOSED", jdbc.queryForObject(
                    "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, rw2.getId()),
                    "工单关闭终局");
            Long scrapTxn = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='REWORK_SCRAP'"
                            + " AND BIZ_DOC_NO=?", Long.class, rw2.getMoNo());
            assertEquals(1L, scrapTxn, "报废凭证流水 1 行");
            Long notices = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='REWORK_SCRAP'", Long.class);
            assertEquals(2L, notices, "质量主管+计划员双通知");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }

    // ================= 3.8 复检合格入库解冻关单三件套（FR-4.5-7-8） =================

    @Test
    void recheckPassStockInUnfreezeClose() {
        sysParam.setValue("FAI_ENFORCE", "OFF", "ENUM", "MRP", "测试关闭", "test");
        try {
            MdmItem product = productWithBom("入库闭环产品");
            String code = product.getItemCode();
            publishReworkRoute(product);
            String srcNo = mkNcr(code, "入库闭环产品", "B-RWB", "3", "REWORK", "REWORKING");
            mkQcStock(code, "B-RWB", "3", "0");
            mkFreeze(code, "B-RWB", "QUALITY", "3");
            MrpMo rw = reworkCreate("NCR", srcNo, code, "3");

            moService.submit(rw.getId());
            moService.onApproved(rw.getId());
            moService.release(rw.getId());
            List<GenReq> rows = dispatchService.pending(null).stream()
                    .filter(r -> rw.getMoNo().equals(r.get("moNo")))
                    .map(r -> new GenReq((String) r.get("moId"),
                            ((Number) r.get("opSeq")).intValue(), null))
                    .toList();
            MrpDispatch dispatch = dispatchService.generate(rows).get(0);
            dispatch = dispatchService.confirm(dispatch.getId());
            MrpReporting rpt = reportingService.open(dispatch.getId(), null);
            reportingService.saveOutput(rpt.getId(), new BigDecimal("3"), BigDecimal.ZERO,
                    null, false);
            reportingService.confirm(rpt.getId());
            reportingService.completeMo(rw.getId());
            assertEquals("PENDING_INSP", reworkOrderService.phaseOf(rw.getId()), "待检验");

            // ① 冻结异常阻断：钉住被清 → 422（spec「冻结未解除阻断入库」状态异常面）
            jdbc.update("UPDATE erp_inv_freeze SET REF_MO_ID=NULL WHERE REF_MO_ID=?", rw.getId());
            var anomaly = assertThrows(ServiceException.class,
                    () -> reworkOrderService.closeFromInspection(rw.getId()));
            assertEquals(422, anomaly.getCode(), "冻结状态异常 422：" + anomaly.getMessage());
            assertTrue(anomaly.getMessage().contains("冻结状态异常"), "提示核对冻结：" + anomaly.getMessage());
            // 恢复钉住
            jdbc.update("UPDATE erp_inv_freeze SET REF_MO_ID=? WHERE ITEM_CODE=? AND BATCH_NO='B-RWB'",
                    rw.getId(), code);

            // ② 复检合格放行监听（同 confirmRelease 后的回调路径——直接驱动监听器同一入口）
            InspectionLot lot = new InspectionLot();
            lot.setRefType("WORKORDER");
            lot.setRefId(rw.getId() + "#REWORK");
            lot.setResult("PASS");
            lot.setStatus("RELEASED");
            lot.setLotQty(new BigDecimal("3"));
            ((com.erp.service.qms.LotReleaseListener) reworkOrderService).onReleased(lot);

            Map<String, Object> stock = jdbc.queryForMap(
                    "SELECT QTY, WIP_QTY, AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RWB'",
                    code);
            assertEquals(0, new BigDecimal(String.valueOf(stock.get("WIP_QTY"))).compareTo(BigDecimal.ZERO),
                    "在制扣减 0：" + stock.get("WIP_QTY"));
            assertEquals(0, new BigDecimal(String.valueOf(stock.get("AVAILABLE_QTY")))
                    .compareTo(new BigDecimal("3")), "合格入可用 3：" + stock.get("AVAILABLE_QTY"));
            assertEquals(0, new BigDecimal(String.valueOf(stock.get("QTY"))).compareTo(new BigDecimal("3")),
                    "QTY 总量不变（池间平移）");
            Map<String, Object> fz = jdbc.queryForMap(
                    "SELECT STATUS, REF_MO_ID, RELEASE_RESULT FROM erp_inv_freeze WHERE ITEM_CODE=? AND BATCH_NO='B-RWB'",
                    code);
            assertEquals("RELEASED", fz.get("STATUS"), "原质量冻结自动解除");
            assertNull(fz.get("REF_MO_ID"), "钉住清除");
            assertTrue(String.valueOf(fz.get("RELEASE_RESULT")).contains("返工复检合格入库"),
                    "解冻执行记录：" + fz.get("RELEASE_RESULT"));
            assertEquals("CLOSED", jdbc.queryForObject(
                    "SELECT STATUS FROM erp_mrp_mo WHERE ID=?", String.class, rw.getId()),
                    "返工工单关闭终局");
            assertEquals(1L, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM erp_inv_transaction WHERE BIZ_DOC_TYPE='REWORK_STOCK_IN'",
                    Long.class), "入库流水 1 行");

            // ③ 阶段推导闭环终局 + 幂等二次回调
            assertEquals("CLOSED", reworkOrderService.phaseOf(rw.getId()), "闭环后阶段 CLOSED");
            ((com.erp.service.qms.LotReleaseListener) reworkOrderService).onReleased(lot); // 幂等不抛不重复入账
            assertEquals(0, jdbc.queryForObject(
                    "SELECT AVAILABLE_QTY FROM erp_inv_stock WHERE ITEM_CODE=? AND BATCH_NO='B-RWB'",
                    BigDecimal.class, code).compareTo(new BigDecimal("3")), "幂等后可用仍 3");
        } finally {
            sysParam.setValue("FAI_ENFORCE", "ON", "ENUM", "MRP", "测试恢复", "test");
        }
    }
    // ================= 3.9 返工良率预警复用（FR-4.5-7-5：5.7 冻结挂点，仅冻本 RW 单） =================

    @Test
    void rwYieldFreezeScopedToOwnOrder() {
        MdmItem product = productWithBom("返工良率产品");
        String code = product.getItemCode();
        publishReworkRoute(product);
        String srcNo = mkNcr(code, "返工良率产品", "B-RWC", "5", "REWORK", "REWORKING");
        mkQcStock(code, "B-RWC", "5", "0");
        mkFreeze(code, "B-RWC", "QUALITY", "5");
        MrpMo rw = reworkCreate("NCR", srcNo, code, "5");

        moService.submit(rw.getId());
        moService.onApproved(rw.getId());
        moService.release(rw.getId());
        List<GenReq> rows = dispatchService.pending(null).stream()
                .filter(r -> rw.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        MrpDispatch dispatch = dispatchService.generate(rows).get(0);
        dispatch = dispatchService.confirm(dispatch.getId());

        // 低良率：合格 4 / 投入 5 = 80%，标准 98 → 偏差 18.4%（10% < d ≤ 2×10% → MINOR → MO 级；
        // 偏差 > 2×阈值会按 5.7 分级条款自动升 WC——spec 场景「仅冻结本返工单」走小超差带）
        MrpReporting rpt = reportingService.open(dispatch.getId(), null);
        reportingService.saveOutput(rpt.getId(), new BigDecimal("4"), new BigDecimal("1"),
                "SCRAP", false);
        reportingService.confirm(rpt.getId());

        // 冻结行：仅本 RW 单（MO 作用域），YIELD 原因
        Map<String, Object> fz = jdbc.queryForMap(
                "SELECT SCOPE, REASON, ACTIVE_FLAG FROM erp_mrp_mo_freeze WHERE MO_ID=?", rw.getId());
        assertEquals("MO", fz.get("SCOPE"), "作用域=工单级（spec 场景「仅冻结本返工单」）");
        assertEquals("YIELD", fz.get("REASON"), "良率超差原因");
        assertEquals("1", fz.get("ACTIVE_FLAG"), "活动冻结");
        Long alert = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='YIELD_ALERT'", Long.class);
        assertTrue(alert >= 1, "良率预警推送：" + alert);

        // 冻结期：RW 自身派工/完工被阻断（assertNotFrozen 挂点）
        var blocked = assertThrows(ServiceException.class,
                () -> freezeService.assertNotFrozen(rw.getId(), null));
        assertEquals(422, blocked.getCode(), "RW 冻结期阻断：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("良率超差冻结"), "提示冻结原因：" + blocked.getMessage());

        // 同产品正常工单不受影响（MO 作用域不误伤——spec「同产品正常工单派工不受影响」）
        freezeService.assertNotFrozen("other-mo-not-frozen", null);
        MdmItem normalP = productWithBom("返工良率对照产品");
        MrpMo normal = createNormal(normalP, "4");
        freezeService.assertNotFrozen(normal.getId(), null);

        // 解冻（回拨口径）：释放验证解冻通路（5.7 既有）
        assertTrue(freezeService.release(rw.getId(), "测试解冻", "TEST"), "释放成功");
        assertEquals(0L, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo_freeze WHERE MO_ID=? AND ACTIVE_FLAG='1'",
                Long.class, rw.getId()), "活动冻结清零");
    }
}
