package com.erp.routing;

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
 * RoutingController 路由与 HTTP 层认证（change add-routing-management，任务 6.1/6.2）：
 * 工序/工作中心/定额/路线四组端点全部注册（doc.html 聚合的来源）/ 未认证 GET、POST 均 401。
 * 写路径的服务层 401/403 矩阵见 RoutingPermissionDbTest。
 */
@SpringBootTest
@AutoConfigureMockMvc
class RoutingControllerApiTest {

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
                    if (pat.getPatternString().startsWith("/api/mrp/")) {
                        paths.add(pat.getPatternString());
                    }
                });
            }
            if (info.getPatternsCondition() != null) {
                info.getPatternsCondition().getPatterns().forEach(p -> {
                    if (p.startsWith("/api/mrp/")) {
                        paths.add(p);
                    }
                });
            }
        }
        assertTrue(paths.containsAll(List.of(
                        // 5.2.1 工序维护
                        "/api/mrp/operations",
                        "/api/mrp/operations/{id}",
                        "/api/mrp/operations/{id}/status",
                        // 5.2.2 工作中心
                        "/api/mrp/work-centers",
                        "/api/mrp/work-centers/{id}",
                        "/api/mrp/work-centers/{id}/status",
                        // 5.2.3 标准工时
                        "/api/mrp/op-wc-standards",
                        "/api/mrp/op-wc-standards/{id}",
                        // 5.2.4 路线装配
                        "/api/mrp/routings",
                        "/api/mrp/routings/{id}",
                        "/api/mrp/routings/{id}/change",
                        "/api/mrp/routings/{id}/submit",
                        "/api/mrp/routings/{id}/obsolete",
                        "/api/mrp/routings/published"
                )),
                "端点缺失，实际注册：" + paths);
    }

    @Test
    void unauthenticatedHttpRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/mrp/operations")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/mrp/operations")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mrp/routings")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/mrp/routings")).andExpect(status().isUnauthorized());
    }
}
