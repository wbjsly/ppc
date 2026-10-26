package com.erp.mo;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.schedule.KittingSweepScheduler;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.KittingService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 齐套翻转通知 + 自动重检调度（change add-kitting-check 任务 4.1/4.2，spec kitting-check
 * 「状态翻转通知时机」「自动重检调度」两需求）：
 * - notifyOnFlip=false（人工源）零推送；=true 翻转推三角色、未读不重复、已读再翻转推新条
 * - 调度器双向：到货恢复→清标记+恢复通知；领料新缺料→置标记+缺料通知
 */
@SpringBootTest
class KittingSweepNotifyDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private KittingService kittingService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private KittingSweepScheduler scheduler;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("sweep-tester", "ROLE_PLANNER", "ROLE_ADMIN");
        // 隔离：清历史齐套通知（测试断言按 bizType+bizId 精确计数）
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('MO_SHORTAGE','MO_KITTING_RESTORED')");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        MoSeed.cleanup(jdbc);
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('MO_SHORTAGE','MO_KITTING_RESTORED')");
    }

    private MrpMo createMo(MdmItem product, String qty) {
        MrpMo h = new MrpMo();
        h.setProductCode(product.getItemCode());
        h.setQty(new BigDecimal(qty));
        h.setPlanStartDate(LocalDate.now());
        h.setPlanEndDate(LocalDate.now().plusDays(7));
        return (MrpMo) moService.create(h).get("mo");
    }

    private long noticeCount(String bizType, String moId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE=? AND BIZ_ID=?",
                Long.class, bizType, moId);
        return n == null ? 0 : n;
    }

    private void markAllRead(String moId) {
        jdbc.update("UPDATE erp_sys_notice SET READ_FLAG='1' WHERE BIZ_ID=? " +
                "AND BIZ_TYPE IN ('MO_SHORTAGE','MO_KITTING_RESTORED')", moId);
    }

    /** 4.1：notifyOnFlip=false 零推送（人工源页面反馈，偏差 D2） */
    @Test
    void silentByDefaultForManualSources() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "静默产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "静默子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "10"); // 翻转 0→1，但默认静默
        assertEquals("1", mo.getShortageFlag(), "标记已置");
        // 显式静默调用（创建/释放/拆分/手动批量路径）
        MoSeed.mkStock(jdbc, child.getItemCode(), "20");
        kittingService.check(mo.getId(), false); // 翻转 1→0
        assertEquals(0, noticeCount("MO_KITTING_RESTORED", mo.getId()), "静默源零通知");
        assertEquals(0, noticeCount("MO_SHORTAGE", mo.getId()), "静默源零通知");
    }

    /** 4.1：翻转推三角色 + 未读幂等 + 已读再翻转推新条 */
    @Test
    void flipPushesThreeRolesWithIdempotency() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "通知产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "通知子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "10"); // 静默建单：flag=1，零通知
        assertEquals(0, noticeCount("MO_SHORTAGE", mo.getId()), "创建静默");

        // 恢复翻转 1→0 + notifyOnFlip → 三角色各一条（幂等键含接收方）
        MoSeed.mkStock(jdbc, child.getItemCode(), "20");
        kittingService.check(mo.getId(), true);
        assertEquals(4, noticeCount("MO_KITTING_RESTORED", mo.getId()),
                "恢复通知推四角色（计划员/计划主管/仓库/车间主管，add-dispatch-management 5.3 回补）");
        Long plannerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='MO_KITTING_RESTORED' " +
                        "AND BIZ_ID=? AND TARGET_ROLE='ROLE_PLANNER'", Long.class, mo.getId());
        assertEquals(1L, (long) plannerRows, "每角色单条");

        // 未读期间再翻转（删库存→缺料）→ 推 MO_SHORTAGE 三角色；MO_KITTING_RESTORED 不重复
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE=?", child.getItemCode());
        kittingService.check(mo.getId(), true);
        assertEquals(4, noticeCount("MO_SHORTAGE", mo.getId()), "缺料通知四角色（5.3 回补车间主管）");
        assertEquals(4, noticeCount("MO_KITTING_RESTORED", mo.getId()),
                "同类型未读不重复（幂等）");

        // 同状态重算（无翻转）→ 零新增
        kittingService.check(mo.getId(), true);
        assertEquals(4, noticeCount("MO_SHORTAGE", mo.getId()), "无翻转零新增");

        // 已读后再翻转 → 推新条（再提醒）
        markAllRead(mo.getId());
        MoSeed.mkStock(jdbc, child.getItemCode(), "20");
        kittingService.check(mo.getId(), true);
        assertEquals(8, noticeCount("MO_KITTING_RESTORED", mo.getId()),
                "已读后再翻转推新条（4 角色 × 两轮）");
    }

    /** 4.2：调度器恢复方向——缺料工单到货后重检清标记 + 推恢复通知 */
    @Test
    void sweepRecoversArrivalAndNotifies() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "调度恢复产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "调度恢复子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10"); // 缺料 flag=1
        jdbc.update("UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID=?", mo.getId());
        // 到货（补库存）→ 扫描重检
        MoSeed.mkStock(jdbc, child.getItemCode(), "10");
        int checked = scheduler.doSweep();
        assertTrue(checked >= 1, "扫到已释放工单");
        String flag = jdbc.queryForObject("SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID=?",
                String.class, mo.getId());
        assertEquals("0", flag, "调度重检恢复清标记（spec L2845）");
        assertEquals(4, noticeCount("MO_KITTING_RESTORED", mo.getId()),
                "恢复推四角色（notifyOnFlip=true，5.3 回补车间主管）");
    }

    /** 4.2：调度器新缺料方向——齐套工单被扣减后置标记 + 推缺料通知（spec 场景双向） */
    @Test
    void sweepDetectsNewShortageFromStockDrop() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "调度缺料产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "调度缺料子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.mkStock(jdbc, child.getItemCode(), "10"); // 先备料 → 创建即齐套
        MrpMo mo = createMo(product, "10");
        assertEquals("0", mo.getShortageFlag(), "初始齐套");
        jdbc.update("UPDATE erp_mrp_mo SET STATUS='RELEASED' WHERE ID=?", mo.getId());
        // 其他工单领料（扣光库存）→ 扫描发现新缺料
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE=?", child.getItemCode());
        scheduler.doSweep();
        String flag = jdbc.queryForObject("SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID=?",
                String.class, mo.getId());
        assertEquals("1", flag, "齐套→缺料方向被扫出（只扫缺料标记会漏）");
        assertEquals(4, noticeCount("MO_SHORTAGE", mo.getId()), "缺料通知四角色（5.3 回补车间主管）");
    }

    /** 4.3 前置：批量=串行重算，重复调用结果一致且缺料行不重复堆积（重建式） */
    @Test
    void repeatedChecksStayConsistent() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "重复校验产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "重复校验子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "10"); // 创建时已 check 1 次
        kittingService.check(mo.getId(), false);
        kittingService.check(mo.getId(), false);
        // 活跃行恒 1（重建式：旧行逻辑删除，原生 COUNT 需带 DEL_FLAG 过滤）
        Long rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_mo_shortage WHERE MO_ID=? AND DEL_FLAG='0'",
                Long.class, mo.getId());
        assertEquals(1L, (long) rows, "重复校验缺料行不重复堆积（重建式）");
        assertEquals(1, kittingService.shortages(mo.getId()).size(), "服务层只见活跃行");
    }

    /** 4.3：批量串行 + 同批重复工单号去重执行、结果一一对应 */
    @Test
    void batchCheckDedupsAndSerializes() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "批量产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "批量子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "10");
        // 同一工单重复提交 3 次（含重复 ID）→ 去重后只执行一次，返回 1 条结果
        var results = kittingService.checkBatch(List.of(mo.getId(), mo.getId(), mo.getId()));
        assertEquals(1, results.size(), "同批重复 ID 去重执行");
        assertEquals(mo.getId(), results.get(0).get("moId"), "结果对应唯一工单");
        assertEquals("1", results.get(0).get("shortageFlag"), "缺料标记返回");
        assertTrue(results.get(0).get("rate") instanceof java.math.BigDecimal, "齐套率返回");
        // 不存在的工单静默跳过（不阻断整批）
        var withMissing = kittingService.checkBatch(List.of("no-such-mo", mo.getId()));
        assertEquals(1, withMissing.size(), "不存在工单跳过、其余照常");
        // 静默：批量不产生通知
        assertEquals(0, noticeCount("MO_SHORTAGE", mo.getId()), "批量人工源零通知");
    }
}
