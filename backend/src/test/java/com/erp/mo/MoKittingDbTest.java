package com.erp.mo;

import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoShortage;
import com.erp.dao.mrp.MrpMoShortageDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoService;
import com.erp.service.SysParamService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 齐套计算库测（task 4.2）：缺料清单与标记、齐套恢复自动清除、创建与释放同一算法。
 */
@SpringBootTest
class MoKittingDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private KittingService kittingService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private MrpMoShortageDao shortageDao;
    @Autowired
    private SysParamService sysParam;
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
        // 在途过滤开关恢复迁移 121 数据护栏默认 OFF（dev 库 req_date 空值率 80.8%，偏差 D5）
        sysParam.setValue("KITTING_TRANSIT_DATE_FILTER", "OFF", "ENUM", "MRP", "测试恢复默认", "test");
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

    @Test
    void shortageRowsAndFlagGenerated() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品一", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件一", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0.05");
        // 无库存：需求 = 10 × 2 × 1.05 = 21
        MrpMo mo = createMo(product, "10");
        assertEquals("1", mo.getShortageFlag(), "缺料置标记");

        List<MrpMoShortage> rows = kittingService.shortages(mo.getId());
        assertEquals(1, rows.size(), "缺料清单一行");
        assertEquals(0, rows.get(0).getReqQty().compareTo(new BigDecimal("21.0000")),
                "需求数量 = QTY×用量×(1+损耗)：" + rows.get(0).getReqQty());
        assertEquals(0, rows.get(0).getShortQty().compareTo(new BigDecimal("21.0000")), "缺料 = 需求 − 0");
        assertEquals(child.getItemCode(), rows.get(0).getItemCode(), "子项编码");
    }

    @Test
    void kittingRecoveryClearsFlag() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品二", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件二", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "10"); // 需求 20，无库存 → flag=1
        assertEquals("1", mo.getShortageFlag(), "初始缺料");
        // 在途到货（补库存 20）→ 重算 → 齐套恢复 100%
        MoSeed.mkStock(jdbc, child.getItemCode(), "20");
        BigDecimal rate = kittingService.check(mo.getId());
        assertEquals(0, rate.compareTo(BigDecimal.valueOf(100).setScale(1)), "齐套率 100");
        assertTrue(shortageDao.selectList(new LambdaQueryWrapper<MrpMoShortage>()
                .eq(MrpMoShortage::getMoId, mo.getId())).isEmpty(), "清单清空");
        MrpMo after = moService.list(null, product.getItemCode(), null).get(0);
        assertEquals("0", after.getShortageFlag(), "缺料标记自动清除（spec 场景）");
    }

    @Test
    void releaseRerunsSameKittingAlgorithm() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品三", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件三", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10"); // flag=1
        // 补料后再释放：释放时重跑同一算法 → 标记清除
        MoSeed.mkStock(jdbc, child.getItemCode(), "10");
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        MrpMo released = moService.release(mo.getId());
        assertEquals(MrpMo.ST_RELEASED, released.getStatus(), "释放成功（缺料不阻断的反面：已齐套）");
        assertEquals("0", released.getShortageFlag(), "释放时重跑算法清除标记");
        assertNotNull(released.getReleaseBy(), "释放留痕");
    }

    @Test
    void shortageDoesNotBlockRelease() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "齐套产品四", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "齐套子件四", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10"); // 缺料
        moService.submit(mo.getId());
        moService.onApproved(mo.getId());
        MrpMo released = moService.release(mo.getId()); // 缺料仍可释放（L2496 场景）
        assertEquals("1", released.getShortageFlag(), "缺料释放后标「缺料待料」");
        assertEquals(1, kittingService.shortages(released.getId()).size(), "缺料清单可见");
    }

    /** 口径修正①（change add-kitting-check 任务 2.1，spec L2833）：需求基数 = 计划数量 − 合格产出 */
    @Test
    void demandBaseUsesRemainingQty() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "剩余量产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "剩余量子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        // 计划 10、单耗 2 → 初始需求 20，无库存 → 缺 20
        MrpMo mo = createMo(product, "10");
        assertEquals("1", mo.getShortageFlag(), "初始缺料");
        assertEquals(0, kittingService.shortages(mo.getId()).get(0).getShortQty()
                .compareTo(new BigDecimal("20.0000")), "初始缺料 = 20");
        // 模拟部分完工 6 → 剩余 4 → 需求 8；补库存 8 → 齐套（旧口径按 10 算会仍缺 12 = 假缺料）
        MoSeed.setQualified(jdbc, mo.getId(), "6");
        MoSeed.mkStock(jdbc, child.getItemCode(), "8");
        BigDecimal rate = kittingService.check(mo.getId());
        assertEquals(0, rate.compareTo(BigDecimal.valueOf(100).setScale(1)),
                "剩余量基数：4×2=8 ≤ 库存8 → 齐套 100%（无假缺料）");
        MrpMo after = moService.list(null, product.getItemCode(), null).get(0);
        assertEquals("0", after.getShortageFlag(), "标记清除");
    }

    /** 口径修正②（change add-kitting-check 任务 2.2，spec「派工日前在途」）：ON 过滤 / OFF 全计入 */
    @Test
    void transitDateFilterOnExcludesLateArrival() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "在途过滤产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "在途过滤子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        // 计划 10 → 需求 10；在途 10 但 reqDate 晚于计划开工日 30 天
        MrpMo mo = createMo(product, "10");
        MoSeed.mkPo(jdbc, child.getItemCode(), "10", LocalDate.now().plusDays(30));
        // OFF（数据护栏默认）：在途全计入 → 齐套
        BigDecimal offRate = kittingService.check(mo.getId());
        assertEquals(0, offRate.compareTo(BigDecimal.valueOf(100).setScale(1)),
                "OFF：晚到在途计入 → 齐套（旧行为）");
        // ON：晚于计划开工日的在途不计入 → 缺 10
        sysParam.setValue("KITTING_TRANSIT_DATE_FILTER", "ON", "ENUM", "MRP", "测试开启", "test");
        BigDecimal onRate = kittingService.check(mo.getId());
        assertEquals(0, onRate.compareTo(BigDecimal.ZERO.setScale(1)),
                "ON：晚到在途不计入 → 齐套率 0");
        assertEquals(1, kittingService.shortages(mo.getId()).size(), "ON：缺料行保留");
        assertEquals(0, kittingService.shortages(mo.getId()).get(0).getShortQty()
                .compareTo(new BigDecimal("10.0000")), "缺料 = 10（在途被过滤）");
    }

    /** 口径修正②补充：ON 模式下 reqDate 为空的在途不计入（保守报缺，偏差 D5） */
    @Test
    void transitDateFilterOnExcludesNullReqDate() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "空日期产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "空日期子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10");
        MoSeed.mkPo(jdbc, child.getItemCode(), "10", null); // reqDate 为空
        sysParam.setValue("KITTING_TRANSIT_DATE_FILTER", "ON", "ENUM", "MRP", "测试开启", "test");
        BigDecimal rate = kittingService.check(mo.getId());
        assertEquals(0, rate.compareTo(BigDecimal.ZERO.setScale(1)),
                "ON：reqDate 空 → 不计入可用（保守报缺）");
        // OFF：空日期在途计入 → 齐套
        sysParam.setValue("KITTING_TRANSIT_DATE_FILTER", "OFF", "ENUM", "MRP", "测试关闭", "test");
        BigDecimal offRate = kittingService.check(mo.getId());
        assertEquals(0, offRate.compareTo(BigDecimal.valueOf(100).setScale(1)),
                "OFF：空日期在途计入 → 齐套");
    }

    // ---------- 任务 5.1：kitting-detail 逐行明细 + 替代候选 ----------

    @Test
    void detailReturnsLineRowsWithSubstituteCandidates() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "明细产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "明细子件", "BUY");
        MdmItem alt = MoSeed.mkItem(itemService, categoryCode, "明细替代料", "BUY");
        String bomId = MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        // 源 BOM 挂行级替代（比例 1.2、优先级 1）——读源 BOM 非快照（偏差 D4）
        String bomItemId = jdbc.queryForObject(
                "SELECT ID FROM erp_mrp_bom_item WHERE BOM_ID=? AND ITEM_CODE=?",
                String.class, bomId, child.getItemCode());
        jdbc.update("INSERT INTO erp_mrp_bom_substitute (ID, BOM_ITEM_ID, SUBSTITUTE_ITEM_CODE, " +
                        "SUBSTITUTE_ITEM_NAME, RATIO, PRIORITY) VALUES (?,?,?,?,?,1)",
                "sub-md-" + bomItemId, bomItemId, alt.getItemCode(), alt.getItemName(),
                new BigDecimal("1.2"));
        // 替代料可用 80 → 可抵 = 80 / 1.2 = 66.6667
        MoSeed.mkStock(jdbc, alt.getItemCode(), "80");
        MrpMo mo = createMo(product, "10"); // 需求 20，无主料库存 → 缺 20

        var detail = kittingService.detail(mo.getId());
        assertEquals(0, BigDecimal.ZERO.setScale(1)
                .compareTo(new BigDecimal(detail.get("rate").toString())),
                "单行 BOM 全缺 → 齐套率 0%");
        @SuppressWarnings("unchecked")
        var lines = (java.util.List<java.util.Map<String, Object>>) detail.get("lines");
        assertEquals(1, lines.size(), "单行 BOM 明细");
        java.util.Map<String, Object> line = lines.get(0);
        assertEquals(Boolean.TRUE, line.get("shortage"), "缺料行");
        assertEquals(0, new BigDecimal("20.0000")
                .compareTo(new BigDecimal(line.get("reqQty").toString())), "需求 10×2");
        @SuppressWarnings("unchecked")
        var subs = (java.util.List<java.util.Map<String, Object>>) line.get("substitutes");
        assertEquals(1, subs.size(), "替代候选一条");
        assertEquals(alt.getItemCode(), subs.get(0).get("itemCode"), "替代料编码");
        assertEquals(0, new BigDecimal("66.6667").compareTo(
                new BigDecimal(subs.get(0).get("coverableQty").toString())),
                "可抵量 = 80 ÷ 1.2 = 66.6667（仅展示不参与判定）");
        // detail 只读：不新增缺料行（创建时 check 已落 1 行，detail 前后行数不变）
        int before = kittingService.shortages(mo.getId()).size();
        kittingService.detail(mo.getId());
        assertEquals(before, kittingService.shortages(mo.getId()).size(),
                "detail 只读不改缺料清单（校验走 check）");
    }

    @Test
    void detailNoSubstituteReturnsEmptyCandidates() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "无替产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "无替子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "2", "0");
        MrpMo mo = createMo(product, "10");
        var detail = kittingService.detail(mo.getId());
        @SuppressWarnings("unchecked")
        var lines = (java.util.List<java.util.Map<String, Object>>) detail.get("lines");
        @SuppressWarnings("unchecked")
        var subs = (java.util.List<java.util.Map<String, Object>>) lines.get(0).get("substitutes");
        assertTrue(subs.isEmpty(), "无替代关系返回空数组");
    }

    // ---------- 任务 5.2：缺料聚合看板 ----------

    @Test
    void aggregateGroupsShortageByItemAcrossMos() {
        MdmItem product1 = MoSeed.mkItem(itemService, categoryCode, "聚合产品一", "MAKE");
        MdmItem product2 = MoSeed.mkItem(itemService, categoryCode, "聚合产品二", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "聚合共用子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product1, child, "2", "0");
        MoSeed.publishBom(bomService, jdbc, product2, child, "3", "0");
        // 两工单缺同一物料：mo1 需求 10×2=20、mo2 需求 10×3=30，均无库存 → 各缺
        MrpMo mo1 = createMo(product1, "10");
        kittingService.check(mo1.getId(), false);
        MrpMo mo2 = createMo(product2, "10");
        kittingService.check(mo2.getId(), false);

        var groups = kittingService.aggregate(null, null);
        java.util.Map<String, Object> shared = groups.stream()
                .filter(g -> child.getItemCode().equals(g.get("itemCode")))
                .findFirst().orElse(null);
        assertNotNull(shared, "共用子件缺料成组");
        assertEquals(2, shared.get("moCount"), "两工单缺同一料 → moCount=2");
        assertEquals(0, new BigDecimal("50.0000")
                .compareTo(new BigDecimal(shared.get("totalShortQty").toString())),
                "总缺料量 = 20 + 30 = 50");
        @SuppressWarnings("unchecked")
        var mos = (java.util.List<?>) shared.get("mos");
        assertEquals(2, mos.size(), "明细下钻两条");

        // 筛选：按物料编码
        var filtered = kittingService.aggregate(child.getItemCode(), null);
        assertEquals(1, filtered.size(), "物料筛选命中一组");
        // 筛选：按工单号 → 只剩该工单明细
        var byMo = kittingService.aggregate(null, mo1.getMoNo());
        assertEquals(1, byMo.size(), "工单号筛选一组");
        @SuppressWarnings("unchecked")
        var byMoMos = (java.util.List<java.util.Map<String, Object>>) byMo.get(0).get("mos");
        assertEquals(1, byMoMos.size(), "只含该工单明细");
        assertEquals(mo1.getMoNo(), byMoMos.get(0).get("moNo"), "工单号匹配");

        // 补足库存 → 全齐套 → 该物料组消失（按物料隔离断言：库中可能有其他测试/冒烟的缺料数据）
        MoSeed.mkStock(jdbc, child.getItemCode(), "1000");
        kittingService.check(mo1.getId(), false);
        kittingService.check(mo2.getId(), false);
        var after = kittingService.aggregate(null, null);
        assertTrue(after.stream().noneMatch(g -> child.getItemCode().equals(g.get("itemCode"))),
                "本物料补足库存后聚合组消失（全齐套）");
        assertTrue(kittingService.aggregate(child.getItemCode(), null).isEmpty(),
                "物料筛选直接返回空");
    }

    /** 权限门（design D9）：无四角色 → 403；未登录 → 401 */
    @Test
    void checkUserPermissionGate() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "权限产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "权限子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MrpMo mo = createMo(product, "10");
        // 非四角色 → 403
        MoTestAuth.login("viewer", "ROLE_USER");
        var ex = org.junit.jupiter.api.Assertions.assertThrows(com.erp.common.ServiceException.class,
                () -> kittingService.checkUser(List.of(mo.getId())));
        assertEquals(403, ex.getCode(), "非四角色 403");
        // 未登录 → 401
        MoTestAuth.logout();
        var ex2 = org.junit.jupiter.api.Assertions.assertThrows(com.erp.common.ServiceException.class,
                () -> kittingService.checkUser(List.of(mo.getId())));
        assertEquals(401, ex2.getCode(), "未登录 401");
        // 四角色（仓库）→ 放行
        MoTestAuth.login("wh", "ROLE_WAREHOUSE");
        var results = kittingService.checkUser(List.of(mo.getId()));
        assertEquals(1, results.size(), "仓库角色放行");
    }
}
