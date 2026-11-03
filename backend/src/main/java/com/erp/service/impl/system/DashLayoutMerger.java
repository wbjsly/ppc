package com.erp.service.impl.system;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作台三层差量合并纯函数（change add-configurable-workbench，spec personal-workbench
 * 场景「模板改版触达已配置用户 / 用户移除不回补 / 个人覆盖优先」）。
 * design D2：合并只存在于此一处，输入三层 JSON（可空），输出 merged JSON。
 */
public final class DashLayoutMerger {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_T = new TypeReference<>() {
    };
    private static final TypeReference<List<Map<String, Object>>> LIST_T = new TypeReference<>() {
    };

    private DashLayoutMerger() {
    }

    public static String merge(String globalJson, String roleJson, String userJson) {
        // 1) items：GLOBAL 底 → ROLE 覆盖 → USER 覆盖（同 id 覆盖，新 id 追加，LinkedHashMap 保序）
        Map<String, Map<String, Object>> items = new LinkedHashMap<>();
        List<Map<String, Object>> gItems = itemsOf(globalJson);
        List<Map<String, Object>> rItems = itemsOf(roleJson);
        List<Map<String, Object>> uItems = itemsOf(userJson);
        for (List<Map<String, Object>> layer : List.of(gItems, rItems, uItems)) {
            for (Map<String, Object> it : layer) {
                Object id = it.get("id");
                if (id == null || String.valueOf(id).isBlank()) {
                    continue;
                }
                items.put(String.valueOf(id), it);
            }
        }

        // 2) excluded：三层并集（显式移除永远赢过模板新增）——Arrays.asList 允许 null 层
        Set<String> excluded = new HashSet<>();
        for (String json : Arrays.asList(globalJson, roleJson, userJson)) {
            excluded.addAll(stringsOf(json, "excluded"));
        }
        items.keySet().removeAll(excluded);

        // 3) shortcuts：整体按 USER → ROLE → GLOBAL 首个非空层
        List<String> shortcuts = firstNonEmptyShortcuts(userJson, roleJson, globalJson);

        // 4) 输出按 (y,x) 稳定排序，保证 gridstack 恢复顺序确定
        List<Map<String, Object>> out = new ArrayList<>(items.values());
        out.sort(Comparator
                .<Map<String, Object>>comparingDouble(m -> num(m.get("y")))
                .thenComparingDouble(m -> num(m.get("x"))));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("v", 1);
        result.put("items", out);
        result.put("excluded", new ArrayList<>(excluded.stream().sorted().toList()));
        result.put("shortcuts", shortcuts);
        try {
            return OM.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("工作台布局序列化失败", e);
        }
    }

    private static List<Map<String, Object>> itemsOf(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> node = OM.readValue(json, MAP_T);
            Object items = node.get("items");
            if (items instanceof List<?> list && !list.isEmpty()) {
                return OM.convertValue(items, LIST_T);
            }
            return List.of();
        } catch (Exception e) {
            // 结构未知/损坏：按空层降级（design Risks：读到未知版本按最接近逻辑降级而非报错）
            return List.of();
        }
    }

    private static List<String> stringsOf(String json, String key) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            Map<String, Object> node = OM.readValue(json, MAP_T);
            Object val = node.get(key);
            if (val instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                for (Object o : list) {
                    if (o != null) {
                        out.add(String.valueOf(o));
                    }
                }
                return out;
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> firstNonEmptyShortcuts(String... layers) {
        for (String json : layers) {
            List<String> sc = stringsOf(json, "shortcuts");
            if (!sc.isEmpty()) {
                return sc;
            }
        }
        return List.of();
    }

    private static double num(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return 0d;
        }
    }
}
