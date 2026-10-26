package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.KittingService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 派工生成/校验/门禁库测（task 4.1 + 4.4 + 4.5）：
 * 批量 3 工序出 3 单、防重 422、opStatus 流转、快照默认值与工时带入、
 * pending 排序与筛选；WC 停用/外协无供应商/供应商冻结/不存在四类 422；
 * 齐套 L1 生成阻断（含齐套率与缺料清单）→ 恢复放行 + 确认双点。
 */
@SpringBootTest
class DispatchDbTest {

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
    private KittingService kittingService;
    @Autowired
    private MrpDispatchDao dispatchDao;
    @Autowired
    private MrpMoOpDao moOpDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    /** 计划窗口：2028-06-28（周三）起一个月，避开周末与种子日历（WEEKDAY 降级恒 8h/工作日） */
    private static final LocalDate START = LocalDate.of(2028, 6, 28);
    private static final LocalDate END = LocalDate.of(2028, 7, 28);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("disp-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        // 本类专属清理须在 MoSeed.cleanup 删物料前（路线删除按物料子查询）
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
        MoSeed.cleanup(jdbc);
    }

    // ---------- 4.1 + pending ----------

    @Test
    void batchGenerateThreeOpsAndPendingOrdering() {
        // 三工序产品（A，priority 后设 3）
        MdmItem productA = MoSeed.mkItem(itemService, categoryCode, "派工产品A", "MAKE");
        MdmItem childA = MoSeed.mkItem(itemService, categoryCode, "派工子件A", "BUY");
        MoSeed.publishBom(bomService, jdbc, productA, childA, "1", "0");
        String wcA = MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, productA, 3);
        MoSeed.mkStock(jdbc, childA.getItemCode(), "9999");
        MrpMo moA = MoSeed.releaseMo(moService, productA, "10", START, END);
        jdbc.update("UPDATE erp_mrp_mo SET PRIORITY=3 WHERE ID=?", moA.getId());

        // 单工序产品（B，priority 1）——待派列表排序对照
        MdmItem productB = MoSeed.mkItem(itemService, categoryCode, "派工产品B", "MAKE");
        MdmItem childB = MoSeed.mkItem(itemService, categoryCode, "派工子件B", "BUY");
        MoSeed.publishBom(bomService, jdbc, productB, childB, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, productB, 3);
        MoSeed.mkStock(jdbc, childB.getItemCode(), "9999");
        MrpMo moB = MoSeed.releaseMo(moService, productB, "5", START, END);
        jdbc.update("UPDATE erp_mrp_mo SET PRIORITY=1 WHERE ID=?", moB.getId());

        // pending：B(优先级1) 在前，A 的 3 道工序在后
        // （按本族工单过滤——dev 库其他测试遗留的 Released 单不隔离，全量跑时全局计数会漂移）
        List<Map<String, Object>> pending = dispatchService.pending(null);
        List<Map<String, Object>> own = pending.stream()
                .filter(r -> moA.getMoNo().equals(r.get("moNo"))
                        || moB.getMoNo().equals(r.get("moNo")))
                .toList();
        assertEquals(4, own.size(), "待派 = A 三工序 + B 一工序（本族过滤）");
        assertEquals(moB.getMoNo(), own.get(0).get("moNo"), "本族内 priority 1 排最前");
        assertEquals(3, own.stream().filter(r -> moA.getMoNo().equals(r.get("moNo"))).count(),
                "A 三工序行");
        // 关键字筛选
        assertEquals(1, dispatchService.pending(moB.getMoNo()).size(), "按工单号筛选");

        // 批量生成 A 的 3 道工序（规格场景：一张 3 工序工单 → 3 张派工单）
        List<GenReq> rows = pending.stream()
                .filter(r -> moA.getMoNo().equals(r.get("moNo")))
                .map(r -> new GenReq((String) r.get("moId"),
                        ((Number) r.get("opSeq")).intValue(), null))
                .toList();
        List<MrpDispatch> created = dispatchService.generate(rows);
        assertEquals(3, created.size(), "批量 3 工序出 3 单");
        created.forEach(d -> {
            assertEquals(MrpDispatch.ST_PENDING_CONFIRM, d.getStatus(), "生成态待确认");
            assertEquals(wcA, d.getWcCode(), "默认 WC 取快照");
            assertEquals(0, d.getDurationHours().compareTo(BigDecimal.ONE),
                    "工时 = setup(0) + run(1)");
            assertNotNull(d.getPlanStart(), "排程计划开始");
            assertNotNull(d.getPlanEnd(), "排程计划结束");
            assertEquals("0", d.getRiskFlag(), "窗口一个月不超期");
        });
        String no1 = created.get(0).getDispatchNo();
        assertTrue(no1.matches("DP\\d{8}\\d{3}"), "单号 DP+日期+流水：" + no1);
        assertEquals(created.stream().map(MrpDispatch::getDispatchNo).distinct().count(), 3L,
                "单号互不重复");

        // opStatus 流转 PENDING → DISPATCHED
        long dispatched = moOpDao.selectCount(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moA.getId())
                .eq(MrpMoOp::getOpStatus, "DISPATCHED"));
        assertEquals(3, dispatched, "三道工序 opStatus=DISPATCHED");

        // 防重：同工序再生成 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> dispatchService.generate(List.of(
                        new GenReq(moA.getId(), rows.get(0).opSeq(), null))));
        assertEquals(422, e.getCode(), "重复生成 → 422：" + e.getMessage());
        assertTrue(e.getMessage().contains("已派工") || e.getMessage().contains("防重"),
                "防重提示：" + e.getMessage());
    }

    // ---------- 4.4 WC 校验 ----------

    @Test
    void workCenterValidationFourGuards() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "派工校验产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "派工校验子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        MrpMo mo = MoSeed.releaseMo(moService, product, "10", START, END);
        Integer opSeq = firstOpSeq(mo.getId());
        GenReq req = new GenReq(mo.getId(), opSeq, null);

        // ① 不存在的工作中心
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> dispatchService.generate(List.of(new GenReq(mo.getId(), opSeq, "NOPE-WC"))));
        assertEquals(422, e1.getCode(), "不存在 WC → 422");

        // ② 停用工作中心（服务建内部 WC → 置停用）
        MrpWorkCenter stopped = new MrpWorkCenter();
        stopped.setWcCode("MOWC-STOP");
        stopped.setWcName("停用测试中心");
        workCenterService.create(stopped);
        jdbc.update("UPDATE erp_mrp_work_center SET STATUS='0' WHERE WC_CODE='MOWC-STOP'");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> dispatchService.generate(List.of(new GenReq(mo.getId(), opSeq, "MOWC-STOP"))));
        assertEquals(422, e2.getCode(), "停用 WC → 422");
        assertTrue(e2.getMessage().contains("停用"), e2.getMessage());

        // ③ 外协 WC 无供应商（台账保存卡控挡 service 建 → JDBC 直插绕过，测运行时二次防线）
        jdbc.update("INSERT INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, STATUS) "
                + "VALUES ('wc-os-nosup','MOWC-OS1','外协无供测试','OUTSOURCED','1')");
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> dispatchService.generate(List.of(new GenReq(mo.getId(), opSeq, "MOWC-OS1"))));
        assertEquals(422, e3.getCode(), "外协无供应商 → 422（C-4.5-05）");
        assertTrue(e3.getMessage().contains("C-4.5-05"), e3.getMessage());

        // ④ 外协 WC 供应商冻结（SUPPAYFRZ = FROZEN）
        jdbc.update("INSERT INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, SUPPLIER_CODE, STATUS) "
                + "VALUES ('wc-os-frozen','MOWC-OS2','外协冻结供测试','OUTSOURCED','SUPPAYFRZ','1')");
        ServiceException e4 = assertThrows(ServiceException.class,
                () -> dispatchService.generate(List.of(new GenReq(mo.getId(), opSeq, "MOWC-OS2"))));
        assertEquals(422, e4.getCode(), "供应商未启用 → 422（C-4.5-05）");
        assertTrue(e4.getMessage().contains("QUALIFIED") || e4.getMessage().contains("未启用"),
                e4.getMessage());

        // 上述全部 422 → 工序仍未派（可继续）
        assertEquals(1, dispatchService.generate(List.of(req)).size(), "合法 WC 生成成功");
    }

    // ---------- 4.5 齐套门禁 ----------

    @Test
    void kittingGateBlocksThenRecoveryAndConfirmDoubleGate() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "派工缺料产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "派工缺料子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRouting(routingService, operationService,
                workCenterService, standardService, jdbc, product, 3);
        // 无库存 → 释放即缺料
        MrpMo mo = MoSeed.releaseMo(moService, product, "10", START, END);
        assertEquals("1", mo.getShortageFlag(), "释放即缺料待料");
        Integer opSeq = firstOpSeq(mo.getId());

        // L1 阻断：422 含齐套率与缺料清单（spec：输出缺料清单与齐套率）
        ServiceException e = assertThrows(ServiceException.class,
                () -> dispatchService.generate(List.of(new GenReq(mo.getId(), opSeq, null))));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("C-4.5-11"), "卡控编号：" + e.getMessage());
        assertTrue(e.getMessage().contains("齐套率"), "含齐套率：" + e.getMessage());
        assertTrue(e.getMessage().contains(child.getItemCode()), "含缺料子项：" + e.getMessage());
        assertTrue(e.getMessage().contains("C-4.5-12"), "指引缺料派工申请：" + e.getMessage());

        // 恢复 → 放行
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        kittingService.check(mo.getId());
        String flag = jdbc.queryForObject(
                "SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID=?", String.class, mo.getId());
        assertEquals("0", flag, "齐套恢复清除标记");
        MrpDispatch d = dispatchService.generate(List.of(new GenReq(mo.getId(), opSeq, null))).get(0);
        assertNotNull(d.getDispatchNo(), "恢复后生成成功");

        // 确认双点：生成后到确认前缺料翻转 → confirm 422；恢复后再确认放行
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE=?", child.getItemCode());
        kittingService.check(mo.getId());
        MoTestAuth.logout();
        MoTestAuth.login("disp-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
        ServiceException ce = assertThrows(ServiceException.class,
                () -> dispatchService.confirm(d.getId()));
        assertEquals(422, ce.getCode(), "确认双点齐套校验 → 422");
        assertTrue(ce.getMessage().contains("C-4.5-11"), ce.getMessage());

        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        kittingService.check(mo.getId());
        MrpDispatch confirmed = dispatchService.confirm(d.getId());
        assertEquals(MrpDispatch.ST_RELEASED, confirmed.getStatus(), "恢复后确认放行");
        assertEquals("disp-tester", confirmed.getConfirmBy(), "确认人留痕");
    }

    // ---------- helpers ----------

    private Integer firstOpSeq(String moId) {
        List<MrpMoOp> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .orderByAsc(MrpMoOp::getOpSeq));
        assertTrue(!ops.isEmpty(), "工单须有工序快照");
        return ops.get(0).getOpSeq();
    }
}
