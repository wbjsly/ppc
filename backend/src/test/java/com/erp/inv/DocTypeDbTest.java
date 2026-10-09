package com.erp.inv;

import com.erp.dao.inv.InvDocTypeDao;
import com.erp.entity.inv.InvDocType;
import com.erp.service.inv.StockDocTypeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 出入库类型 DB 侧语义（真实 MySQL，spec stock-doc-type，任务 2.2）：
 * 迁移 104 种子 13 类型、启停幂等、停用类型 ENABLED=0 标记返回。
 */
@SpringBootTest
class DocTypeDbTest {

    @Autowired
    private StockDocTypeService service;
    @Autowired
    private InvDocTypeDao docTypeDao;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void loginAdmin() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "db-tester", "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void restore() {
        SecurityContextHolder.clearContext();
        // 恢复种子默认态（本测试只动 MATERIAL_OUT 的启停）
        jdbc.update("UPDATE erp_inv_doc_type SET ENABLED = 1 WHERE TYPE_CODE = 'MATERIAL_OUT'");
    }

    @Test
    void seedHasAllKnownTypes() {
        List<InvDocType> all = service.list();
        // 104 种子 13 类 + 迁移 115 RECALL_IN（add-trace-recall 召回入库）
        assertEquals(14, all.size(), "迁移 104 种子 = 7 已建域 + 6 预注册 + 115 RECALL_IN");
        List<String> codes = all.stream().map(InvDocType::getTypeCode).toList();
        for (String expect : List.of("PURCHASE_IN", "SALES_OUT", "QUALITY_RETURN_OUT",
                "OTHER_RETURN_OUT", "SALES_RETURN_IN", "MATERIAL_OUT", "VMI_TRANSFER_IN",
                "WIP_IN", "SCRAP_OUT", "TRANSFER_IN", "TRANSFER_OUT", "ADJUST_IN", "ADJUST_OUT",
                "RECALL_IN")) {
            assertTrue(codes.contains(expect), "缺少类型 " + expect);
        }
        // 方向与分配默认正确
        assertEquals("IN", service.getByCode("PURCHASE_IN").getDirection());
        assertEquals("AUTO_FIFO", service.getByCode("SALES_OUT").getDefaultAlloc());
        assertEquals("MANUAL", service.getByCode("PURCHASE_IN").getDefaultAlloc());
    }

    @Test
    void disableFlagVisibleOnRead() {
        InvDocType mat = service.getByCode("MATERIAL_OUT");
        assertNotNull(mat);
        service.setEnabled(mat.getId(), false);
        // 读路径照常返回并携 ENABLED=0 标记（spec 场景：停用类型读取可见）
        InvDocType after = service.getByCode("MATERIAL_OUT");
        assertEquals(0, after.getEnabled());
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT ENABLED FROM erp_inv_doc_type WHERE TYPE_CODE = 'MATERIAL_OUT'");
        assertEquals(0, ((Number) row.get("ENABLED")).intValue());
        // 幂等：重复停用不报错
        service.setEnabled(mat.getId(), false);
        assertEquals(0, service.getByCode("MATERIAL_OUT").getEnabled());
    }
}
