package com.erp.plan;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.condition.PathPatternsRequestCondition;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MrpPlanController 路由与 HTTP 层认证（change add-mrp-demand-planning，任务 7.1/7.2）：
 * 运行/建议/审核/转正/处置端点全部注册（doc.html 聚合来源）/ 未认证 GET、POST 均 401。
 * 写路径的服务层 401/403 矩阵见 MrpPermissionDbTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MrpPlanControllerApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void allEndpointsRegistered() {
        Set<String> paths = new HashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            PathPatternsRequestCondition pc = info.getPathPatternsCondition();
            if (pc != null) {
                pc.getPatterns().forEach(pat -> {
                    if (pat.getPatternString().startsWith("/api/mrp/runs")
                            || pat.getPatternString().startsWith("/api/mrp/suggestions")) {
                        paths.add(pat.getPatternString());
                    }
                });
            }
            if (info.getPatternsCondition() != null) {
                info.getPatternsCondition().getPatterns().forEach(p -> {
                    if (p.startsWith("/api/mrp/runs") || p.startsWith("/api/mrp/suggestions")) {
                        paths.add(p);
                    }
                });
            }
        }
        assertTrue(paths.containsAll(List.of(
                        "/api/mrp/runs",
                        "/api/mrp/suggestions",
                        "/api/mrp/suggestions/{id}/confirm",
                        "/api/mrp/suggestions/{id}/cancel",
                        "/api/mrp/suggestions/convert-pr",
                        "/api/mrp/suggestions/convert-mo",
                        "/api/mrp/suggestions/{id}/handle"
                )),
                "端点缺失，实际注册：" + paths);
    }

    @Test
    void unauthenticatedHttpRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/mrp/suggestions")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mrp/runs")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/mrp/runs")).andExpect(status().isUnauthorized());
    }
}
