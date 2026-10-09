package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.Ncr;
import com.erp.service.qms.NcrService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NCR 处置枚举扩 SCRAP（add-outbound-workbench 方案 a，spec ncr-management MODIFIED）：
 * 评审选报废走 SCRAPPING、CTQ 禁报废 422、处置确认缺报废单号 422、凭证齐全置 DISPOSED。
 */
@SpringBootTest
class NcrScrapDispositionDbTest {

    @Autowired
    private NcrService ncrService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asQualityEng();
        // 普通 NCR
        insertNcr("scr-ncr-1", "NCR-SCR-0001", "SCR-ITEM-01", "0");
        // 安全/法规 CTQ NCR
        insertNcr("scr-ncr-2", "NCR-SCR-0002", "SCR-ITEM-02", "1");
        // 已评审待处置确认的报废 NCR
        insertNcr("scr-ncr-3", "NCR-SCR-0003", "SCR-ITEM-03", "0");
        jdbc.update("UPDATE erp_qms_ncr SET STATUS = 'SCRAPPING', DISPOSITION = 'SCRAP' "
                + "WHERE ID = 'scr-ncr-3'");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID LIKE 'scr-ncr-%'");
        jdbc.update("DELETE FROM erp_qms_ncr WHERE ID LIKE 'scr-ncr-%'");
    }

    private void insertNcr(String id, String no, String item, String regulatory) {
        jdbc.update("INSERT INTO erp_qms_ncr (ID, NCR_NO, ITEM_CODE, ITEM_NAME, QTY, STATUS, "
                + "SEVERITY, REGULATORY_FLAG, CTQ_FLAG, CREATE_BY) VALUES "
                + "(?, ?, ?, 'SCRP测试不合格', 10, 'CREATED', 'MAJOR', ?, '0', 'junit')",
                id, no, item, regulatory);
    }

    @Test
    void reviewScrapGoesScrapping() {
        Ncr n = ncrService.review("scr-ncr-1", "SCRAP", "评审判定报废，转 4.5.4");
        assertEquals("SCRAPPING", n.getStatus(), "评审后进 SCRAPPING");
        assertEquals("SCRAP", n.getDisposition());
        String st = jdbc.queryForObject("SELECT STATUS FROM erp_qms_ncr WHERE ID = 'scr-ncr-1'",
                String.class);
        assertEquals("SCRAPPING", st, "落库状态");
    }

    @Test
    void regulatoryCtqScrapRejected() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.review("scr-ncr-2", "SCRAP", "也想报废"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("仅可退货或返工"), e.getMessage());
        // 状态未动
        assertEquals("CREATED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_ncr WHERE ID = 'scr-ncr-2'", String.class));
    }

    @Test
    void confirmScrapRequiresVoucherThenDisposes() {
        // 缺报废单号凭证 → 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> ncrService.confirmDisposed("scr-ncr-3", null));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("报废执行凭证"), e.getMessage());
        assertEquals("SCRAPPING", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_ncr WHERE ID = 'scr-ncr-3'", String.class));

        // 回填报废单号 → DISPOSED（不生成复检批）
        int lotsBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_inspection_lot", Integer.class);
        Ncr n = ncrService.confirmDisposed("scr-ncr-3", "SC202610-0001");
        assertEquals("DISPOSED", n.getStatus());
        int lotsAfter = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_inspection_lot", Integer.class);
        assertEquals(lotsBefore, lotsAfter, "报废不生成复检批");
    }

    private void asQualityEng() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_QUALITY_ENG"));
    }
}
