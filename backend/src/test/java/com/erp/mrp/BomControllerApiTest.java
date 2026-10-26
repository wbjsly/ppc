package com.erp.mrp;

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
 * BomController 路由与 HTTP 层认证（change add-bom-management，任务 6.1）：
 * 9 个端点全部注册（doc.html 聚合的来源）/ 未认证 GET、POST 均 401（JWT 入口）。
 * 写路径的服务层 401/403 矩阵见 BomPermissionDbTest；带 token 的实机 curl 冒烟见集成验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BomControllerApiTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void allNineEndpointsRegistered() {
        Set<String> paths = new HashSet<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            PathPatternsRequestCondition pc = info.getPathPatternsCondition();
            if (pc != null) {
                pc.getPatterns().forEach(pat -> {
                    if (pat.getPatternString().startsWith("/api/mrp/boms")) {
                        paths.add(pat.getPatternString());
                    }
                });
            }
            if (info.getPatternsCondition() != null) {
                info.getPatternsCondition().getPatterns().forEach(p -> {
                    if (p.startsWith("/api/mrp/boms")) {
                        paths.add(p);
                    }
                });
            }
        }
        assertTrue(paths.containsAll(List.of(
                        "/api/mrp/boms",
                        "/api/mrp/boms/{id}",
                        "/api/mrp/boms/{id}/copy",
                        "/api/mrp/boms/{id}/change",
                        "/api/mrp/boms/{id}/submit",
                        "/api/mrp/boms/{id}/obsolete",
                        "/api/mrp/boms/scan",
                        "/api/mrp/boms/substitute-candidates"
                )),
                "端点缺失，实际注册：" + paths);
    }

    @Test
    void unauthenticatedHttpRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/mrp/boms")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/mrp/boms")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/mrp/boms/scan")).andExpect(status().isUnauthorized());
    }
}
