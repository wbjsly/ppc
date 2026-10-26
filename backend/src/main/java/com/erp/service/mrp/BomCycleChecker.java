package com.erp.service.mrp;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * BOM 循环引用校验引擎（change add-bom-management，spec bom-management FR-4.5-1-4 / BR-4.5-07）。
 * 三色标记（未访问 / 在栈中 / 已完成）跨 BOM 深度优先遍历组件图：
 * 再次遇到「在栈中」节点即成环，回溯输出完整环路径（如 A→B→C→A，供前端高亮）；
 * 深度超过系统参数（BOM_MAX_NESTING_DEPTH，默认 8）停止下钻并置超限标记——
 * 超限为提示口径而非死循环（FR-4.5-1-4 的 L1 阻断只针对「检测到环」）。
 * 保存内嵌校验与 5.1.4 循环校验工具页扫描共用本实现（同一口径，任务 3.3）。
 */
@Component
public class BomCycleChecker {

    /** 单次校验结果 */
    public static class Result {
        /** 检出的环路径集合，每条路径首尾为同一节点（A→B→C→A） */
        final List<List<String>> cycles = new ArrayList<>();
        /** 是否存在超过最大嵌套层数的分支（提示用） */
        boolean depthExceeded;
        /** 实际遍历节点数 */
        int visitedCount;

        public List<List<String>> getCycles() {
            return cycles;
        }

        public boolean isDepthExceeded() {
            return depthExceeded;
        }

        public int getVisitedCount() {
            return visitedCount;
        }
    }

    private enum Color {WHITE, GRAY, BLACK}

    /**
     * @param startNodes       起始父项（保存校验 = 被保存的父项；全量扫描 = 全部在用父项）
     * @param childrenProvider 父项 → 子项物料编码列表（跨 BOM 取数；调用方决定版本口径）
     * @param maxDepth         最大嵌套层数（BR-4.5-07，系统参数）
     */
    public Result check(List<String> startNodes, Function<String, List<String>> childrenProvider, int maxDepth) {
        Result result = new Result();
        Map<String, Color> color = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        for (String start : startNodes) {
            if (start == null || start.isEmpty()) {
                continue;
            }
            if (color.getOrDefault(start, Color.WHITE) == Color.WHITE) {
                dfs(start, childrenProvider, maxDepth, color, path, result, 1);
            }
        }
        return result;
    }

    private void dfs(String node, Function<String, List<String>> childrenProvider,
                     int maxDepth, Map<String, Color> color, Deque<String> path, Result result, int depth) {
        Color c = color.getOrDefault(node, Color.WHITE);
        if (c == Color.GRAY) {
            // 成环：node 仍在当前遍历栈中，回溯出完整环路径
            result.cycles.add(extractCycle(path, node));
            return;
        }
        if (c == Color.BLACK) {
            return;
        }
        if (depth > maxDepth) {
            // 超过最大嵌套层数：停止下钻，只记提示（BR-4.5-07 参数控制，非死循环）
            result.depthExceeded = true;
            return;
        }
        color.put(node, Color.GRAY);
        path.push(node);
        result.visitedCount++;
        List<String> children = childrenProvider.apply(node);
        if (children != null) {
            for (String child : children) {
                if (child == null || child.isEmpty()) {
                    continue;
                }
                dfs(child, childrenProvider, maxDepth, color, path, result, depth + 1);
            }
        }
        path.pop();
        color.put(node, Color.BLACK);
    }

    /**
     * 从栈中 node 首次出现处回溯至栈顶，并补回首节点。
     * ArrayDeque 迭代序 = 栈顶 → 栈底；成环时 node 在栈中唯一（GRAY）。
     * 例：栈顶→底 [C,B,A]，node=A → [A,B,C] + A = [A,B,C,A]
     */
    private List<String> extractCycle(Deque<String> path, String node) {
        List<String> topDown = new ArrayList<>(path);
        List<String> cycle = new ArrayList<>();
        int idx = topDown.indexOf(node);
        for (int i = idx; i >= 0; i--) {
            cycle.add(topDown.get(i));
        }
        cycle.add(node);
        return cycle;
    }
}
