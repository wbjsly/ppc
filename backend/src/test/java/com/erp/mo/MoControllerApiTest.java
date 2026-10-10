package com.erp.mo;

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
 * MoController 路由与 HTTP 层认证（change add-work-order-management，任务 7.1）：
 * 15 个端点全部注册（/doc.html 聚合的来源）/ 未认证 GET、POST 均 401（JWT 入口）。
 * 写路径的服务层 401/403 矩阵见 MoPermissionDbTest；带 token 的实机冒烟见 mo-smoke.sh。
 */
@SpringBootTest
@AutoConfigureMockMvc
class MoControllerApiTest {

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
                    if (pat.getPatternString().startsWith("/api/mrp/mos")) {
                        paths.add(pat.getPatternString());
                    }
                });
            }
            if (info.getPatternsCondition() != null) {
                info.getPatternsCondition().getPatterns().forEach(p -> {
                    if (p.startsWith("/api/mrp/mos")) {
                        paths.add(p);
                    }
                });
            }
        }
        assertTrue(paths.containsAll(List.of(
                        "/api/mrp/mos",
                        "/api/mrp/mos/{id}",
                        "/api/mrp/mos/candidate-pmos",
                        "/api/mrp/mos/from-pmo",
                        "/api/mrp/mos/{id}/shortages",
                        "/api/mrp/mos/{id}/submit",
                        "/api/mrp/mos/{id}/release",
                        "/api/mrp/mos/{id}/hold",
                        "/api/mrp/mos/{id}/resume",
                        "/api/mrp/mos/{id}/cancel",
                        "/api/mrp/mos/{id}/split",
                        "/api/mrp/mos/{id}/complete",
                        "/api/mrp/mos/{id}/close-precheck",
                        "/api/mrp/mos/{id}/close",
                        "/api/mrp/mos/in-process"
                )),
                "端点缺失，实际注册：" + paths);
    }

    @Test
    void unauthenticatedHttpRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/mrp/mos")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/mrp/mos")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/mrp/mos/x/submit")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mrp/mos/candidate-pmos")).andExpect(status().isUnauthorized());
    }
}
