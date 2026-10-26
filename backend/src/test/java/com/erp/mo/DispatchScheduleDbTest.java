package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.dao.mrp.MrpReservationDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpOperation;
import com.erp.entity.mrp.MrpReservation;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排程与确认/取消链库测（task 4.2 + 4.3）：
 * 跨天顺延、超交期风险标、重排按 MO 优先级后移（留痕、已确认固定）；
 * 确认权限矩阵（PLANNER 403 / SHOP_FLOOR 通过）、预扣触发、
 * 取消链（原因必填、opStatus 回退、末张取消才释放预扣）。
 */
@SpringBootTest
class DispatchScheduleDbTest {

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
    private MrpDispatchDao dispatchDao;
    @Autowired
    private MrpMoOpDao moOpDao;
    @Autowired
    private MrpReservationDao reservationDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;
    private String wcCode;

    private static final LocalDate START = LocalDate.of(2028, 6, 28); // 周三
    private static final LocalDate END = LocalDate.of(2028, 7, 28);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("sched-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        // 专属清理（含按物料子查询的路线）须在 MoSeed.cleanup 删物料前
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        MoSeed.cleanup(jdbc);
    }

    /** 既有占用（RELEASED 固定，直接造） */
    private void occupy(String id, String wc, LocalDate start, LocalDate end, String hours) {
        jdbc.update("INSERT INTO erp_mrp_dispatch (ID, DISPATCH_NO, MO_ID, MO_NO, OP_SEQ, "
                        + "WC_CODE, WC_TYPE, PLAN_START, PLAN_END, DURATION_HOURS, STATUS, CREATE_BY) "
                        + "VALUES (?,?,?,?,?,?,'INTERNAL',?,?,?, 'RELEASED', 'junit')",
                id, "DP-OCC-" + id, "mo-occ-" + id, "MO-OCC-1", 10,
                wc, start, end, new BigDecimal(hours));
    }

    /** 共享 WC/工序（两张工单同队列才谈得上重排挤占） */
    private String initSharedWc() {
        int n = (int) (System.nanoTime() % 100000);
        wcCode = "MOWC-SH" + n;
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcCode(wcCode);
        wc.setWcName("重排共享中心" + n);
        workCenterService.create(wc);
        MrpOperation op = new MrpOperation();
        op.setOpCode("MOOP-SH" + n);
        op.setOpName("重排共享工序" + n);
        operationService.create(op);
        MrpOpWcStandard std = new MrpOpWcStandard();
        std.setOpCode(op.getOpCode());
        std.setWcCode(wcCode);
        std.setRunHours(BigDecimal.ONE);
        standardService.create(std);
        return op.getOpCode();
    }

    /** 把某产品发布到共享 WC（同 opCode/wcCode，多路线引用同一工序） */
    private void routeOnSharedWc(MdmItem product, String opCode) {
        MrpRouting head = new MrpRouting();
        head.setItemCode(product.getItemCode());
        MrpRoutingOp row = new MrpRoutingOp();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        row.setLeadTime(BigDecimal.ONE);
        MrpRouting draft = routingService.create(head, List.of(row));
        jdbc.update("UPDATE erp_mrp_routing SET STATUS='PENDING' WHERE ID=?", draft.getId());
        routingService.publishApproved(draft.getId());
    }

    private MrpMo releasedMo(String nameSuffix, int priority) {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "排程产品" + nameSuffix, "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "排程子件" + nameSuffix, "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        MrpMo mo = MoSeed.releaseMo(moService, product, "10", START, END);
        jdbc.update("UPDATE erp_mrp_mo SET PRIORITY=? WHERE ID=?", priority, mo.getId());
        return mo;
    }

    private Integer firstOpSeq(String moId) {
        return moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId).orderByAsc(MrpMoOp::getOpSeq)).get(0).getOpSeq();
    }

    // ---------- 4.2 ----------

    @Test
    void crossDayRollAndDueDateRisk() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "跨天产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "跨天子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        wcCode = MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        // 工单窗口只有 1 天（06-28 当天），但当天已被占满 8h → 顺延次日 → 超交期风险
        MrpMo mo = MoSeed.releaseMo(moService, product, "10", START, START);
        occupy("occ-full", wcCode, START, START, "8");

        MrpDispatch d = dispatchService.generate(List.of(
                new GenReq(mo.getId(), firstOpSeq(mo.getId()), null))).get(0);

        assertEquals(0, d.getPlanStart().compareTo(START.plusDays(1)),
                "当日占满 → 顺延次日（跨天拆分）：" + d.getPlanStart());
        assertEquals("1", d.getRiskFlag(), "超 planEndDate → 交期风险标");
        // 同窗口宽松工单不标风险
        MdmItem product2 = MoSeed.mkItem(itemService, categoryCode, "宽松产品", "MAKE");
        MdmItem child2 = MoSeed.mkItem(itemService, categoryCode, "宽松子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product2, child2, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product2, 3);
        MoSeed.mkStock(jdbc, child2.getItemCode(), "9999");
        MrpMo mo2 = MoSeed.releaseMo(moService, product2, "10", START, END);
        MrpDispatch d2 = dispatchService.generate(List.of(
                new GenReq(mo2.getId(), firstOpSeq(mo2.getId()), null))).get(0);
        assertEquals("0", d2.getRiskFlag(), "宽松窗口不标风险");
    }

    @Test
    void rescheduleReordersByPriorityWithAudit() {
        // 三个产品：低优先级 p3 先生成（抢到首日最后 1h），高优先级 p1 后生成（被挤到次日）
        MdmItem prodP3 = MoSeed.mkItem(itemService, categoryCode, "重排产品低", "MAKE");
        MdmItem childP3 = MoSeed.mkItem(itemService, categoryCode, "重排子件低", "BUY");
        MoSeed.publishBom(bomService, jdbc, prodP3, childP3, "1", "0");
        String sharedOp = initSharedWc();
        routeOnSharedWc(prodP3, sharedOp);
        MoSeed.mkStock(jdbc, childP3.getItemCode(), "9999");
        MrpMo moP3 = MoSeed.releaseMo(moService, prodP3, "10", START, END);
        jdbc.update("UPDATE erp_mrp_mo SET PRIORITY=3 WHERE ID=?", moP3.getId());

        occupy("occ-7h", wcCode, START, START, "7"); // 首日仅剩 1h
        MrpDispatch dP3 = dispatchService.generate(List.of(
                new GenReq(moP3.getId(), firstOpSeq(moP3.getId()), null))).get(0);
        assertEquals(0, dP3.getPlanStart().compareTo(START), "低优先级占到首日最后 1h");

        MdmItem prodP1 = MoSeed.mkItem(itemService, categoryCode, "重排产品高", "MAKE");
        MdmItem childP1 = MoSeed.mkItem(itemService, categoryCode, "重排子件高", "BUY");
        MoSeed.publishBom(bomService, jdbc, prodP1, childP1, "1", "0");
        routeOnSharedWc(prodP1, sharedOp);
        MoSeed.mkStock(jdbc, childP1.getItemCode(), "9999");
        MrpMo moP1 = MoSeed.releaseMo(moService, prodP1, "10", START, END);
        jdbc.update("UPDATE erp_mrp_mo SET PRIORITY=1 WHERE ID=?", moP1.getId());
        MrpDispatch dP1 = dispatchService.generate(List.of(
                new GenReq(moP1.getId(), firstOpSeq(moP1.getId()), null))).get(0);
        assertEquals(0, dP1.getPlanStart().compareTo(START.plusDays(1)),
                "高优先级反被挤到次日（生成序 ≠ 优先级）");

        // 重排：p1 抢回首日，p3 后移；已确认占用固定、留痕
        List<MrpDispatch> out = dispatchService.reschedule(wcCode, START);
        assertEquals(2, out.size(), "两张未确认单受影响");
        MrpDispatch r1 = dispatchDao.selectById(dP1.getId());
        MrpDispatch r3 = dispatchDao.selectById(dP3.getId());
        assertEquals(0, r1.getPlanStart().compareTo(START), "重排后高优先级回到首日");
        assertEquals(0, r3.getPlanStart().compareTo(START.plusDays(1)),
                "低优先级自动后移（FR-4.5-4-7）");
        assertTrue(r1.getRemark() != null && r1.getRemark().contains("重排"), "重排留痕");
        assertEquals("sched-tester", r1.getUpdateBy(), "留痕操作人");
        MrpDispatch occ = dispatchDao.selectById("occ-7h");
        assertEquals(0, occ.getPlanStart().compareTo(START), "已确认占用固定不动");
        assertEquals(0, occ.getPlanEnd().compareTo(START), "已确认占用结束日不变");
    }

    // ---------- 4.3 确认与取消 ----------

    @Test
    void confirmPermissionAndCancelChain() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "确认产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "确认子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, product, 2);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        MrpMo mo = MoSeed.releaseMo(moService, product, "10", START, END);
        List<Integer> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId()).orderByAsc(MrpMoOp::getOpSeq))
                .stream().map(MrpMoOp::getOpSeq).toList();
        MrpDispatch d1 = dispatchService.generate(List.of(
                new GenReq(mo.getId(), ops.get(0), null))).get(0);

        // 权限：PLANNER 不能确认（确认 = SHOP_FLOOR/PLAN_MGR/ADMIN）
        MoTestAuth.logout();
        MoTestAuth.login("sched-planner", "ROLE_PLANNER");
        ServiceException pe = assertThrows(ServiceException.class,
                () -> dispatchService.confirm(d1.getId()));
        assertEquals(403, pe.getCode(), "PLANNER 确认 → 403：" + pe.getMessage());

        // SHOP_FLOOR 确认 → RELEASED + 预扣创建
        MoTestAuth.logout();
        MoTestAuth.login("sched-floor", "ROLE_SHOP_FLOOR");
        MrpDispatch confirmed = dispatchService.confirm(d1.getId());
        assertEquals(MrpDispatch.ST_RELEASED, confirmed.getStatus(), "确认生效");
        assertEquals("sched-floor", confirmed.getConfirmBy(), "确认人留痕");
        assertNotNull(confirmed.getConfirmAt(), "确认时间留痕");
        assertEquals(1, reservationDao.selectCount(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, mo.getId())
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE)),
                "确认触发物料预扣（活动行 1 条）");

        // 重复确认 422
        ServiceException re = assertThrows(ServiceException.class,
                () -> dispatchService.confirm(d1.getId()));
        assertEquals(422, re.getCode(), "非待确认态确认 → 422");

        // 第二道工序：生成 + 确认（同工单预扣幂等不重复扣）
        MoTestAuth.logout();
        MoTestAuth.login("sched-tester", "ROLE_PLANNER", "ROLE_ADMIN");
        MrpDispatch d2 = dispatchService.generate(List.of(
                new GenReq(mo.getId(), ops.get(1), null))).get(0);
        MoTestAuth.logout();
        MoTestAuth.login("sched-floor", "ROLE_SHOP_FLOOR");
        dispatchService.confirm(d2.getId());
        BigDecimal availAfterBoth = avail(child.getItemCode());
        BigDecimal reservedSum = reservationDao.selectList(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, mo.getId())).stream()
                .map(MrpReservation::getReservedQty)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, reservedSum.compareTo(new BigDecimal("10.0000")),
                "两张确认预扣不重复扣（幂等）：累计预扣 = 需求 10");
        assertEquals(0, availAfterBoth.compareTo(new BigDecimal("9989.0000")),
                "可用 = 9999 − 10");

        // 取消无原因 422
        MoTestAuth.logout();
        MoTestAuth.login("sched-tester", "ROLE_PLANNER", "ROLE_ADMIN");
        ServiceException ce = assertThrows(ServiceException.class,
                () -> dispatchService.cancel(d1.getId(), " "));
        assertEquals(422, ce.getCode(), "取消原因必填 → 422");

        // 取消第一张（另一张仍活动）→ opStatus 回退但预扣保留
        MrpDispatch c1 = dispatchService.cancel(d1.getId(), "工序改期");
        assertEquals(MrpDispatch.ST_CANCELLED, c1.getStatus(), "取消生效");
        assertEquals("工序改期", c1.getCancelReason(), "原因留痕");
        assertEquals("PENDING", moOpDao.selectById(d1.getId() == null ? null : opStatusId(mo.getId(), ops.get(0))).getOpStatus(),
                "首道工序回 PENDING");
        assertEquals(1, reservationDao.selectCount(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, mo.getId())
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE)),
                "另一张仍活动 → 预扣保留");

        // 取消最后一张 → 释放预扣
        MrpDispatch c2 = dispatchService.cancel(d2.getId(), "计划调整");
        assertEquals(MrpDispatch.ST_CANCELLED, c2.getStatus());
        assertEquals(0, reservationDao.selectCount(new LambdaQueryWrapper<MrpReservation>()
                .eq(MrpReservation::getMoId, mo.getId())
                .eq(MrpReservation::getStatus, MrpReservation.ST_ACTIVE)),
                "末张取消 → 预扣释放（无活动行）");
        assertEquals(0, avail(child.getItemCode()).compareTo(new BigDecimal("9999.0000")),
                "释放后可用量回补");
    }

    // ---------- helpers ----------

    /** 按 (moId, opSeq) 取工序行 ID 的 opStatus 判定用（直接查状态字符串更简） */
    private String opStatusId(String moId, Integer opSeq) {
        return moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .eq(MrpMoOp::getOpSeq, opSeq)).get(0).getId();
    }

    private BigDecimal avail(String itemCode) {
        BigDecimal v = jdbc.queryForObject(
                "SELECT COALESCE(SUM(AVAILABLE_QTY),0) FROM erp_inv_stock WHERE ITEM_CODE=?",
                BigDecimal.class, itemCode);
        return v == null ? BigDecimal.ZERO : v;
    }
}
