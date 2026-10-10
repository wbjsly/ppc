package com.erp.mo;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoBom;
import com.erp.dao.mrp.MrpMoBomDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.MoService;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工单生命周期库测（task 5.2 释放三场景 / 6.1 挂起取消三场景 / 6.2 拆分三场景 / 6.3 完工关闭四场景）。
 */
@SpringBootTest
class MoLifecycleDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private MrpMoBomDao moBomDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("mo-tester", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        MoSeed.cleanup(jdbc);
    }

    private MdmItem mkMake(String name) {
        return MoSeed.mkItem(itemService, categoryCode, name, "MAKE");
    }

    private MrpMo createMo(MdmItem product, String qty) {
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(7));
        return (MrpMo) moService.create(h).get("mo");
    }

    /** 走完审批到 CONFIRMED（回调同事务入口直驱，审批实例由 cleanup 清扫） */
    private MrpMo confirmed(MrpMo mo) {
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        return moService.list(null, mo.getProductCode(), null).stream()
                .filter(m -> m.getId().equals(mo.getId())).findFirst().orElseThrow();
    }

    private MrpMo released(MrpMo mo) {
        confirmed(mo);
        return moService.release(mo.getId());
    }

    // ---------- 5.2 释放 ----------

    @Test
    void releaseSuccessAndIllegalReleaseRejected() {
        MdmItem product = mkMake("生命周期产品一");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "生命周期子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10");
        // PLANNED 不可释放（非法迁移）
        ServiceException e = assertThrows(ServiceException.class, () -> moService.release(mo.getId()));
        assertEquals(422, e.getCode(), "PLANNED 释放被拒");
        MrpMo rel = released(mo);
        assertEquals(MrpMo.ST_RELEASED, rel.getStatus(), "CONFIRMED → RELEASED");
        assertNotNull(rel.getReleaseBy(), "释放人留痕");
        assertNotNull(rel.getReleaseAt(), "释放时间留痕");
    }

    // ---------- 6.1 挂起/恢复/取消 ----------

    @Test
    void holdResumeAndCancelLifecycle() {
        MdmItem product = mkMake("生命周期产品二");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "生命周期子件二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo rel = released(createMo(product, "10"));
        // 缺原因挂起 → 422
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> moService.hold(rel.getId(), " "));
        assertEquals(422, e1.getCode(), "挂起原因必填");
        // 挂起 → 恢复
        MrpMo held = moService.hold(rel.getId(), "设备检修");
        assertEquals(MrpMo.ST_HOLD, held.getStatus(), "HOLD");
        assertEquals("设备检修", held.getHoldReason(), "挂起原因留痕");
        MrpMo resumed = moService.resume(rel.getId());
        assertEquals(MrpMo.ST_RELEASED, resumed.getStatus(), "恢复回 RELEASED");
        // 取消：缺原因 422，带原因终态
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> moService.cancel(rel.getId(), null));
        assertEquals(422, e2.getCode(), "取消原因必填");
        MrpMo cancelled = moService.cancel(rel.getId(), "订单取消");
        assertEquals(MrpMo.ST_CANCELLED, cancelled.getStatus(), "CANCELLED 终态");
        assertEquals("订单取消", cancelled.getCancelReason(), "取消原因留痕");
        // 终态不可再动
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> moService.hold(rel.getId(), "x"));
        assertEquals(422, e3.getCode(), "终态封闭");
    }

    @Test
    void plannedCannotHoldCancelAllowedOnlyByWhitelist() {
        MdmItem product = mkMake("生命周期产品三");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "生命周期子件三", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "5");
        // PLANNED 不可挂起（白名单：HOLD 仅自 RELEASED）
        ServiceException e = assertThrows(ServiceException.class,
                () -> moService.hold(mo.getId(), "试挂起"));
        assertEquals(422, e.getCode(), "PLANNED 挂起被白名单拒绝");
        // PLANNED 可取消（白名单允许）
        assertEquals(MrpMo.ST_CANCELLED,
                moService.cancel(mo.getId(), "计划作废").getStatus(), "PLANNED 取消成功");
    }

    // ---------- 6.2 拆分 ----------

    @Test
    void splitClonesSnapshotAndReducesParent() {
        MdmItem product = mkMake("拆分产品一");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "拆分子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0.05");
        MrpMo rel = released(createMo(product, "10"));
        Map<String, Object> out = moService.split(rel.getId(),
                List.of(new BigDecimal("6"), new BigDecimal("4")), "分批交付");
        @SuppressWarnings("unchecked")
        List<MrpMo> children = (List<MrpMo>) out.get("children");
        assertEquals(2, children.size(), "两张子单");
        MrpMo parent = (MrpMo) out.get("parent");
        assertEquals(0, parent.getQty().compareTo(BigDecimal.ZERO), "原单减至 0");
        assertTrue(parent.getChangeReason().contains("拆分出 2 张子单"), "原单拆分留痕");
        for (MrpMo c : children) {
            assertEquals(MrpMo.ST_RELEASED, c.getStatus(), "子单继承已释放");
            assertEquals(rel.getMoNo(), c.getSplitFromMo(), "拆分链留痕");
            assertEquals(parent.getPlanEndDate(), c.getPlanEndDate(), "子单继承交期");
            assertTrue(c.getMoNo().startsWith("MO-"), "子单独立工单号");
            // 继承 BOM 快照
            List<MrpMoBom> lines = moBomDao.selectList(new LambdaQueryWrapper<MrpMoBom>()
                    .eq(MrpMoBom::getMoId, c.getId()));
            assertEquals(1, lines.size(), "子单快照行继承");
            assertEquals(0, lines.get(0).getUnitQty().compareTo(new BigDecimal("2")), "快照用量一致");
        }
        // 拆分产物与父单共存不触发唯一在途（split 豁免）
        assertEquals(3, moService.list(null, product.getItemCode(), null).size(), "父+两子共存");
    }

    @Test
    void splitSumMismatchBlocked() {
        MdmItem product = mkMake("拆分产品二");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "拆分子件二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo rel = released(createMo(product, "10"));
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.split(rel.getId(), List.of(new BigDecimal("6"), new BigDecimal("5")), null));
        assertEquals(422, e.getCode(), "C-4.5-15 数量合计校验");
        assertTrue(e.getMessage().contains("合计"), e.getMessage());
    }

    @Test
    void splitBlockedBeforeRelease() {
        MdmItem product = mkMake("拆分产品三");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "拆分子件三", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10");
        ServiceException e = assertThrows(ServiceException.class, () ->
                moService.split(mo.getId(), List.of(new BigDecimal("10")), null));
        assertEquals(422, e.getCode(), "PLANNED 不可拆分");
        assertTrue(e.getMessage().contains("不允许拆分"), e.getMessage());
    }

    // ---------- 6.3 完工与关闭 ----------

    @Test
    void completeThenCloseWithTraceAndHooks() {
        MdmItem product = mkMake("关闭产品一");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "关闭子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo rel = released(createMo(product, "10"));
        // RELEASED 不可关闭
        ServiceException e1 = assertThrows(ServiceException.class, () -> moService.close(rel.getId()));
        assertEquals(422, e1.getCode(), "未完工不可关闭");
        // 完工确认
        MrpMo done = moService.complete(rel.getId(), new BigDecimal("9"));
        assertEquals(MrpMo.ST_COMPLETED, done.getStatus(), "RELEASED → COMPLETED");
        assertEquals(0, done.getQualifiedQty().compareTo(new BigDecimal("9")), "合格产出留痕");
        assertNotNull(done.getCompleteBy(), "完工人留痕");
        // 关闭预检（状态 + 占位钩子空通过）
        Map<String, Object> pre = moService.precheckClose(rel.getId());
        assertEquals(Boolean.TRUE, pre.get("passed"), "预检通过：" + pre.get("checks"));
        // 关闭
        MrpMo closed = moService.close(rel.getId());
        assertEquals(MrpMo.ST_CLOSED, closed.getStatus(), "→ CLOSED 终态");
        assertNotNull(closed.getCloseBy(), "关闭人留痕");
        // 终态封闭
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> moService.complete(rel.getId(), BigDecimal.ONE));
        assertEquals(422, e2.getCode(), "CLOSED 不可再动");
    }

    @Test
    void missingQualifiedQtyBlocked() {
        MdmItem product = mkMake("关闭产品二");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "关闭子件二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo rel = released(createMo(product, "10"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> moService.complete(rel.getId(), null));
        assertEquals(422, e.getCode(), "合格产出必填");
    }

    @Test
    void inProcessExposesRemainingForDownstream() {
        MdmItem product = mkMake("在制产品一");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "在制子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo rel = released(createMo(product, "10"));
        Map<String, BigDecimal> ip = moService.inProcess(Set.of(product.getItemCode()));
        assertEquals(0, ip.get(product.getItemCode()).compareTo(new BigDecimal("10")),
                "RELEASED 工单剩余量 = 在制供给（下游契约②）");
        // 完工部分确认后剩余减少
        moService.complete(rel.getId(), new BigDecimal("4"));
        Map<String, BigDecimal> ip2 = moService.inProcess(Set.of(product.getItemCode()));
        assertEquals(0, ip2.get(product.getItemCode()).compareTo(new BigDecimal("6")),
                "COMPLETED 未关闭仍计在制（QTY−合格产出）");
    }
}
