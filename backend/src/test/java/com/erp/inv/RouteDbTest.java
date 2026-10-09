package com.erp.inv;

import com.erp.common.ServiceException;
import com.erp.entity.inv.InvRoute;
import com.erp.service.inv.RouteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 配送线路 DB 集成（真实 MySQL，spec wave-management 配送线路主数据，任务 2.1）：
 * 编码唯一 409、停用退役不物理删除（C-0-05）、写权限 401/403。
 */
@SpringBootTest
class RouteDbTest {

    @Autowired
    private RouteService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void auth() {
        asWarehouse();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM erp_inv_route WHERE ROUTE_CODE LIKE 'RT-E2E-%'");
        SecurityContextHolder.clearContext();
    }

    @Test
    void createDuplicateCodeConflict() {
        InvRoute r = new InvRoute();
        r.setRouteCode("RT-E2E-01");
        r.setRouteName("华东线路");
        Map<String, Object> created = service.create(r);
        assertEquals("RT-E2E-01", created.get("routeCode"));
        assertEquals(InvRoute.ST_ACTIVE, created.get("status"));

        InvRoute dup = new InvRoute();
        dup.setRouteCode("RT-E2E-01");
        dup.setRouteName("重复");
        ServiceException ex = assertThrows(ServiceException.class, () -> service.create(dup));
        assertEquals(409, ex.getCode());
    }

    @Test
    void deactivateKeepsRowAndExcludedFromActive() {
        InvRoute r = new InvRoute();
        r.setRouteCode("RT-E2E-02");
        r.setRouteName("华南线路");
        @SuppressWarnings("unchecked")
        Map<String, Object> created = service.create(r);
        String id = (String) created.get("id");

        service.changeStatus(id, InvRoute.ST_INACTIVE);

        // C-0-05：停用=退役，行保留
        Integer cnt = jdbc.queryForObject("SELECT COUNT(*) FROM erp_inv_route WHERE ID = ?",
                Integer.class, id);
        assertEquals(1, cnt, "停用不物理删除（C-0-05）");
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_inv_route WHERE ID = ?",
                String.class, id);
        assertEquals(InvRoute.ST_INACTIVE, st);

        // 启用列表排除停用线（聚类/上拉选只见 ACTIVE）
        List<InvRoute> active = service.listActive();
        assertTrue(active.stream().noneMatch(x -> x.getId().equals(id)),
                "停用线不出现在启用列表");
    }

    @Test
    void writeRequiresRole() {
        SecurityContextHolder.clearContext();
        ServiceException noAuth = assertThrows(ServiceException.class,
                () -> service.create(new InvRoute()));
        assertEquals(401, noAuth.getCode());

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("seller", null, "ROLE_SALES"));
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> service.create(new InvRoute()));
        assertEquals(403, forbidden.getCode());
    }

    private void asWarehouse() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_WAREHOUSE"));
    }
}
