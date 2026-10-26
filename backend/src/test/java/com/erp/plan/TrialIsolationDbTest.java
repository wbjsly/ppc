package com.erp.plan;

import com.erp.common.ServiceException;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpSuggestion;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.MrpPlanService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 试产隔离库测（change add-trial-production-management，任务 3.2/3.3）：
 * 3.2 MRP/排产隔离——毛需求并入排除 TRIAL（RW 保留并入，核对结论 D6）、
 *     缺料预警看板不含试产行、转采购出口试产 422、待派工按 moType 分池；
 * 3.3 齐套正常口径——试产单齐套公式与门限同正常单、缺料置标记、明细可查。
 */
@SpringBootTest
class TrialIsolationDbTest {

    @Autowired
    private MrpPlanService planService;
    @Autowired
    private KittingService kittingService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    @BeforeEach
    void seed() {
        PlanSeed.cleanup(jdbc);
        // 上一轮残留快照行（PlanSeed 不覆盖）——前置清保证插入不冲突
        jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE ID LIKE 'bom-iso-%'");
        jdbc.update("DELETE FROM erp_mrp_mo_op WHERE ID LIKE 'op-iso-%'");
        categoryCode = PlanSeed.mkCategory(jdbc);
        PlanTestAuth.login("trial-iso-tester", "ROLE_PLANNER");
    }

    @AfterEach
    void tearDown() {
        PlanTestAuth.logout();
        // PlanSeed.cleanup 不覆盖工单快照两表——按本测试 ID 前缀补清（防跨轮 Duplicate）
        jdbc.update("DELETE FROM erp_mrp_mo_bom WHERE ID LIKE 'bom-iso-%'");
        jdbc.update("DELETE FROM erp_mrp_mo_op WHERE ID LIKE 'op-iso-%'");
        PlanSeed.cleanup(jdbc);
    }

    /** 造试产工单（MO_TYPE=TRIAL + 阶段；缺省 RELEASED） */
    private String mkTrialMo(String productCode, String qty) {
        String moId = PlanSeed.mkMo(jdbc, productCode, qty, "RELEASED");
        jdbc.update("UPDATE erp_mrp_mo SET MO_TYPE='TRIAL', TRIAL_STAGE='EVT' WHERE ID=?", moId);
        return moId;
    }

    /** 造返工工单（MO_TYPE=RW） */
    private String mkRwMo(String productCode, String qty) {
        String moId = PlanSeed.mkMo(jdbc, productCode, qty, "RELEASED");
        jdbc.update("UPDATE erp_mrp_mo SET MO_TYPE='RW' WHERE ID=?", moId);
        return moId;
    }

    private boolean hasSuggestion(String itemCode) {
        List<Map<String, Object>> sugs = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, null, null, itemCode, false);
        return sugs.stream().anyMatch(s -> itemCode.equals(s.get("itemCode")));
    }

    private BigDecimal suggestionNetReq(String itemCode) {
        List<Map<String, Object>> sugs = planService.suggestions(
                MrpSuggestion.TYPE_PURCHASE, null, null, itemCode, false);
        return sugs.stream()
                .filter(s -> itemCode.equals(s.get("itemCode")))
                .map(s -> (BigDecimal) s.get("netReq"))
                .findFirst().orElseThrow();
    }

    // ================= 3.2 MRP 毛需求并入排除 =================

    @Test
    void mrpExcludesTrialShortageButKeepsRw() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "隔离MRP产品", "MAKE");
        MdmItem child = PlanSeed.mkItem(itemService, categoryCode, "隔离MRP子件", "BUY");

        // 仅试产缺料 → 不并入（BR-4.5-03）
        String trialMo = mkTrialMo(product.getItemCode(), "10");
        PlanSeed.mkShortage(jdbc, trialMo, child.getItemCode(), "20", "0", "20");
        planService.run("FULL", null);
        assertTrue(planService.suggestions(MrpSuggestion.TYPE_PURCHASE, null, null,
                        child.getItemCode(), false).isEmpty(),
                "试产缺料不并入毛需求（BR-4.5-03，spec mrp delta 场景）");

        // 追加 RW 缺料 → 并入且净需求仅含 RW 量（核对结论 D6：返工追加料真实需求保留）
        String rwMo = mkRwMo(product.getItemCode(), "5");
        PlanSeed.mkShortage(jdbc, rwMo, child.getItemCode(), "30", "0", "30");
        planService.run("FULL", null);
        assertTrue(hasSuggestion(child.getItemCode()), "RW 缺料正常并入产出建议");
        assertEquals(0, suggestionNetReq(child.getItemCode()).compareTo(new BigDecimal("30")),
                "净需求 = RW 缺料 30（试产 20 未混入）");
    }

    // ================= 3.2 缺料预警看板排除 =================

    @Test
    void shortageBoardExcludesTrialRows() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "隔离看板产品", "MAKE");
        MdmItem child = PlanSeed.mkItem(itemService, categoryCode, "隔离看板子件", "BUY");
        String normalMo = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        PlanSeed.mkShortage(jdbc, normalMo, child.getItemCode(), "5", "0", "5");
        String trialMo = mkTrialMo(product.getItemCode(), "8");
        PlanSeed.mkShortage(jdbc, trialMo, child.getItemCode(), "7", "0", "7");

        List<Map<String, Object>> board = kittingService.aggregate(null, null);
        // 同物料聚合组内不得出现试产工单明细
        for (Map<String, Object> group : board) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mos = (List<Map<String, Object>>) group.get("mos");
            for (Map<String, Object> d : mos) {
                assertTrue(!String.valueOf(d.get("moId")).equals(trialMo),
                        "预警看板不含试产行（spec kitting delta 场景）：" + d.get("moNo"));
            }
        }
        // 正常行仍在（对照）
        assertTrue(board.stream().anyMatch(g -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> mos = (List<Map<String, Object>>) g.get("mos");
            return mos.stream().anyMatch(d -> String.valueOf(d.get("moId")).equals(normalMo));
        }), "正常工单缺料行仍在看板");
        // 总缺料量仅含正常行 5（试产 7 不计入）
        BigDecimal total = board.stream()
                .filter(g -> child.getItemCode().equals(g.get("itemCode")))
                .map(g -> (BigDecimal) g.get("totalShortQty"))
                .findFirst().orElseThrow();
        assertEquals(0, total.compareTo(new BigDecimal("5")),
                "看板总缺料量不含试产行：" + total);
    }

    // ================= 3.2 转采购出口 422 =================

    @Test
    void convertFromShortageBlocksTrial() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "隔离转采购产品", "MAKE");
        MdmItem child = PlanSeed.mkItem(itemService, categoryCode, "隔离转采购子件", "BUY");
        String trialMo = mkTrialMo(product.getItemCode(), "10");
        String shortageId = PlanSeed.mkShortage(jdbc, trialMo, child.getItemCode(),
                "20", "0", "20");

        var blocked = assertThrows(ServiceException.class,
                () -> planService.convertFromShortage(List.of(shortageId)));
        assertEquals(422, blocked.getCode(), "试产缺料转采购 422：" + blocked.getMessage());
        assertTrue(blocked.getMessage().contains("试产工单缺料行不可转采购"), blocked.getMessage());
        Integer sugs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mrp_suggestion WHERE ITEM_CODE=?",
                Integer.class, child.getItemCode());
        assertEquals(0, sugs, "422 后零建议产生");
    }

    // ================= 3.2 待派工按池过滤 =================

    @Test
    void dispatchPendingPoolsByMoType() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "隔离派工产品", "MAKE");
        // 正常单 + 试产单（各带一道 PENDING 工序快照）
        String normalMo = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        String trialMo1 = mkTrialMo(product.getItemCode(), "4");
        String trialMo2 = mkTrialMo(product.getItemCode(), "6");
        // 显式错开创建时间（试产池按申请时间升序断言）
        jdbc.update("UPDATE erp_mrp_mo SET CREATE_DATE='2026-01-01 08:00:00' WHERE ID=?", trialMo1);
        jdbc.update("UPDATE erp_mrp_mo SET CREATE_DATE='2026-01-02 08:00:00' WHERE ID=?", trialMo2);
        for (String moId : new String[]{normalMo, trialMo1, trialMo2}) {
            jdbc.update("INSERT INTO erp_mrp_mo_op (ID, MO_ID, OP_SEQ, OP_CODE, OP_NAME,"
                            + " OP_STATUS) VALUES (?,?,10,'ISOL-OP','隔离工序','PENDING')",
                    "op-iso-" + moId, moId);
        }

        // NORMAL 池不含试产工单
        List<Map<String, Object>> normalPool = dispatchService.pending(null, "NORMAL");
        assertTrue(normalPool.stream().noneMatch(r -> trialMo1.equals(r.get("moId"))
                        || trialMo2.equals(r.get("moId"))),
                "NORMAL 池不含试产工单（spec trial-order 场景）");
        assertTrue(normalPool.stream().anyMatch(r -> normalMo.equals(r.get("moId"))),
                "NORMAL 池含正常工单");

        // TRIAL 池仅试产且按申请（创建）时间升序
        List<Map<String, Object>> trialPool = dispatchService.pending(null, "TRIAL");
        assertEquals(2, trialPool.size(), "TRIAL 池仅试产两单工序行");
        assertEquals(trialMo1, trialPool.get(0).get("moId"), "试产池按申请时间升序（早的在前）");
        assertEquals(trialMo2, trialPool.get(1).get("moId"));

        // 不传 moType = 全部类型（既有语义兼容，返工链不破；共享库可能有跨族残留故按成员断言）
        List<Map<String, Object>> all = dispatchService.pending(null, null);
        assertTrue(all.stream().anyMatch(r -> normalMo.equals(r.get("moId"))),
                "moType 空 = 含正常工单");
        assertTrue(all.stream().anyMatch(r -> trialMo1.equals(r.get("moId")))
                        && all.stream().anyMatch(r -> trialMo2.equals(r.get("moId"))),
                "moType 空 = 含试产工单（全部类型语义）");
    }

    // ================= 3.3 试产齐套正常口径 =================

    @Test
    void trialKittingUsesNormalFormula() {
        MdmItem product = PlanSeed.mkItem(itemService, categoryCode, "隔离齐套产品", "MAKE");
        MdmItem child = PlanSeed.mkItem(itemService, categoryCode, "隔离齐套子件", "BUY");
        // 两单同构：BOM 快照 1 行 用量 2 损耗 0，库存 0 → 需求 = 剩余 × 2
        String normalMo = PlanSeed.mkMo(jdbc, product.getItemCode(), "10", "RELEASED");
        String trialMo = mkTrialMo(product.getItemCode(), "10");
        for (String moId : new String[]{normalMo, trialMo}) {
            jdbc.update("INSERT INTO erp_mrp_mo_bom (ID, MO_ID, LINE_NO, TREE_LEVEL,"
                            + " ITEM_CODE, UNIT_QTY, LOSS_RATE) VALUES (?,?,1,1,?,?,0)",
                    "bom-iso-" + moId, moId, child.getItemCode(), new BigDecimal("2"));
        }

        BigDecimal normalRate = kittingService.check(normalMo);
        BigDecimal trialRate = kittingService.check(trialMo);
        assertEquals(normalRate, trialRate,
                "试产齐套公式与门限同正常单（spec kitting delta 场景）");
        assertEquals(0, trialRate.compareTo(BigDecimal.ZERO), "库存 0 → 齐套率 0%");
        // 缺料置标记（供 C-4.5-11 派工 L1 卡控）
        assertEquals("1", jdbc.queryForObject(
                "SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID=?", String.class, trialMo),
                "试产缺料置「缺料待料」标记");
        // 缺料明细可查（试产单详情页数据源）
        assertEquals(1, moService.shortages(trialMo).size(), "试产单缺料明细可查");

        // 补库存至需求 20 → 两单均 100% 清标记（100% 门限同正常单）
        PlanSeed.mkStock(jdbc, child.getItemCode(), "30");
        BigDecimal normal2 = kittingService.check(normalMo);
        BigDecimal trial2 = kittingService.check(trialMo);
        assertEquals(0, trial2.compareTo(new BigDecimal("100")), "补料后试产 100%");
        assertEquals(normal2, trial2, "补料后两单一致");
        assertEquals("0", jdbc.queryForObject(
                "SELECT SHORTAGE_FLAG FROM erp_mrp_mo WHERE ID=?", String.class, trialMo),
                "齐套后标记清除");
    }
}
