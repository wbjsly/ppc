package com.erp.mrp;

import com.erp.service.mrp.BomCycleChecker;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BOM 循环校验引擎纯逻辑（change add-bom-management，任务 3.1）：
 * 直接环 / 跨 BOM 间接环 A→B→C→A 路径输出 / 无环 / 超深度提示四场景。
 */
class BomCycleCheckerUnitTest {

    private final BomCycleChecker checker = new BomCycleChecker();

    private Function<String, List<String>> provider(Map<String, List<String>> graph) {
        return x -> graph.getOrDefault(x, List.of());
    }

    @Test
    void directSelfCycleDetected() {
        BomCycleChecker.Result r = checker.check(List.of("A"),
                provider(Map.of("A", List.of("A"))), 8);
        assertEquals(1, r.getCycles().size(), "直接环应检出");
        assertEquals(List.of("A", "A"), r.getCycles().get(0), "环路径 A→A");
    }

    @Test
    void crossBomIndirectCyclePathOutput() {
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("A", List.of("B"));
        graph.put("B", List.of("C"));
        graph.put("C", List.of("A"));
        BomCycleChecker.Result r = checker.check(List.of("A"), provider(graph), 8);
        assertEquals(1, r.getCycles().size(), "跨 BOM 间接环应检出");
        assertEquals(List.of("A", "B", "C", "A"), r.getCycles().get(0), "环路径 A→B→C→A");
    }

    @Test
    void noCycleReturnsEmptyAndVisitsAll() {
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("A", List.of("B"));
        graph.put("B", List.of("C"));
        BomCycleChecker.Result r = checker.check(List.of("A"), provider(graph), 8);
        assertTrue(r.getCycles().isEmpty(), "无环不应误报");
        assertEquals(3, r.getVisitedCount(), "A/B/C 全部遍历");
        assertFalse(r.isDepthExceeded(), "层数未超限");
    }

    @Test
    void depthExceededFlaggedWithoutFalseCycle() {
        // A→B→C→D→E，maxDepth=3：第 4 层停止下钻（BR-4.5-07 参数控制，非死循环）
        Map<String, List<String>> graph = new HashMap<>();
        graph.put("A", List.of("B"));
        graph.put("B", List.of("C"));
        graph.put("C", List.of("D"));
        graph.put("D", List.of("E"));
        BomCycleChecker.Result r = checker.check(List.of("A"), provider(graph), 3);
        assertTrue(r.isDepthExceeded(), "超深度应置提示标记");
        assertTrue(r.getCycles().isEmpty(), "超限不是环，不误报");
        assertEquals(3, r.getVisitedCount(), "A/B/C 遍历，D 起停止下钻");
    }
}
