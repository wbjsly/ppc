package com.erp.system;

import com.erp.service.impl.system.DashLayoutMerger;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作台三层差量合并纯函数单测（change add-configurable-workbench，
 * spec personal-workbench 四个合并场景）。
 */
public class DashLayoutMergeUnitTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static Map<String, Object> mergeAsMap(String g, String r, String u) throws Exception {
        return OM.readValue(DashLayoutMerger.merge(g, r, u), new TypeReference<>() {
        });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> merged) {
        return (List<Map<String, Object>>) merged.get("items");
    }

    private static boolean hasItem(Map<String, Object> merged, String id) {
        return items(merged).stream().anyMatch(i -> id.equals(i.get("id")));
    }

    @Test
    public void 模板新增触达已配置用户() throws Exception {
        // 用户早年保存过个人层（只有 todo/notice 两件），之后 ROLE 层新增 kpi-alerts
        String global = "{\"v\":1,\"items\":[{\"id\":\"todo\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}],\"excluded\":[]}";
        String role = "{\"v\":1,\"items\":[" +
                "{\"id\":\"todo\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}," +
                "{\"id\":\"kpi-alerts\",\"x\":4,\"y\":0,\"w\":4,\"h\":4}],\"excluded\":[]}";
        String user = "{\"v\":1,\"items\":[" +
                "{\"id\":\"todo\",\"x\":8,\"y\":0,\"w\":4,\"h\":4}," +
                "{\"id\":\"notice\",\"x\":0,\"y\":4,\"w\":4,\"h\":4}],\"excluded\":[]}";

        Map<String, Object> merged = mergeAsMap(global, role, user);
        assertTrue(hasItem(merged, "kpi-alerts"), "模板新增组件必须出现在已配置用户画布");
        assertEquals(8, ((Number) items(merged).stream()
                .filter(i -> "todo".equals(i.get("id"))).findFirst().orElseThrow().get("x")).intValue(),
                "个人位置优先于模板");
    }

    @Test
    public void 用户移除的组件不回补() throws Exception {
        String global = "{\"v\":1,\"items\":[{\"id\":\"period\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}],\"excluded\":[]}";
        String role = "{\"v\":1,\"items\":[{\"id\":\"period\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}],\"excluded\":[]}";
        String user = "{\"v\":1,\"items\":[],\"excluded\":[\"period\"]}";

        Map<String, Object> merged = mergeAsMap(global, role, user);
        assertFalse(hasItem(merged, "period"), "用户移除的组件即使模板存在也不得回补");
        assertTrue(((List<?>) merged.get("excluded")).contains("period"));
    }

    @Test
    public void 个人覆盖优先() throws Exception {
        String global = "{\"v\":1,\"items\":[{\"id\":\"todo\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}],\"excluded\":[]}";
        String role = "{\"v\":1,\"items\":[{\"id\":\"todo\",\"x\":4,\"y\":0,\"w\":6,\"h\":4}],\"excluded\":[]}";
        String user = "{\"v\":1,\"items\":[{\"id\":\"todo\",\"x\":2,\"y\":1,\"w\":3,\"h\":5}],\"excluded\":[]}";

        Map<String, Object> merged = mergeAsMap(global, role, user);
        Map<String, Object> todo = items(merged).stream()
                .filter(i -> "todo".equals(i.get("id"))).findFirst().orElseThrow();
        assertEquals(2, ((Number) todo.get("x")).intValue());
        assertEquals(1, ((Number) todo.get("y")).intValue());
        assertEquals(3, ((Number) todo.get("w")).intValue());
        assertEquals(5, ((Number) todo.get("h")).intValue());
    }

    @Test
    public void 空个人层回落角色模板() throws Exception {
        String global = "{\"v\":1,\"items\":[{\"id\":\"todo\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}],\"excluded\":[]}";
        String role = "{\"v\":1,\"items\":[" +
                "{\"id\":\"todo\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}," +
                "{\"id\":\"period\",\"x\":4,\"y\":0,\"w\":4,\"h\":4}],\"excluded\":[]}";

        Map<String, Object> merged = mergeAsMap(global, role, null);
        assertTrue(hasItem(merged, "period"), "无个人层时应完整呈现角色模板");
        assertEquals(2, items(merged).size());
    }

    @Test
    public void 损坏层按空降级不抛错() throws Exception {
        String global = "{\"v\":1,\"items\":[{\"id\":\"todo\",\"x\":0,\"y\":0,\"w\":4,\"h\":4}]}";
        Map<String, Object> merged = mergeAsMap(global, "not-a-json", "{broken");
        assertTrue(hasItem(merged, "todo"), "损坏层按空降级，健康层照常合并");
    }
}
