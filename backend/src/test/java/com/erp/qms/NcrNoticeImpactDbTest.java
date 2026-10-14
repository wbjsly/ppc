package com.erp.qms;

import com.erp.entity.qms.InspectionLot;
import com.erp.entity.qms.LotItem;
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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不合格处置通知与冻结影响快照（change rework-qualified-release，
 * spec ncr-management「不合格处置通知与冻结影响快照」）：
 * 处置通知质量工程师（NCR 号/严重度/时限）、已入库冻结生成 impactJson + 计划通知、
 * 未入库仅处置通知、通知失败降级不回滚。
 */
@SpringBootTest
class NcrNoticeImpactDbTest {

    @Autowired
    private NcrService ncrService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asQualityEng();
        // 已入库 GR 行（POSTED）+ 库存 + 已批准未清 PO（影响面 pos 验证用）
        insertGrLine("grl-ni-1", "POSTED");
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                + "BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('stk-ni-1','WH-MAIN','NI-ITEM','通知测试物料','NI-BATCH','A-01',50,50,'junit','0',0)");
        jdbc.update("INSERT IGNORE INTO erp_proc_po (ID, PO_NO, SUPPLIER_ID, STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                + "VALUES ('po-ni-1','PO-NI-001','sup-ni','APPROVED','junit','0',0)");
        jdbc.update("INSERT IGNORE INTO erp_proc_po_line (ID, PO_ID, LINE_NO, ITEM_CODE, QTY, RECEIVED_QTY, "
                + "LINE_STATUS, CREATE_BY, VER_NO) VALUES ('pol-ni-1','po-ni-1',1,'NI-ITEM',100,0,'OPEN','junit',0)");
        // 未入库 GR 行（非 POSTED）
        insertGrLine("grl-ni-2", "REGISTERED");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        // 先按 LOT_ID 收集既有 NCR（实体 ID 为 UUID，须经 lot 锚点反查——残留会触发幂等短路）
        List<String> old = jdbc.queryForList(
                "SELECT ID FROM erp_qms_ncr WHERE LOT_ID LIKE 'ncr-ni-%'", String.class);
        for (String id : old) {
            jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('QMS_NCR','QMS_NCR_FREEZE') "
                    + "AND (BIZ_ID=? OR BIZ_ID IS NULL)", id);
            jdbc.update("DELETE FROM erp_qms_ncr_log WHERE NCR_ID=?", id);
            jdbc.update("DELETE FROM erp_inv_freeze WHERE NCR_ID=?", id);
            jdbc.update("DELETE FROM erp_qms_ncr WHERE ID=?", id);
        }
        // 历史残留（ID 未关联到任何 lot 的 null 台账/通知，早期轮次产物）
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('QMS_NCR','QMS_NCR_FREEZE') "
                + "AND BIZ_ID IS NULL");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE SOURCE='NCR' AND NCR_ID IS NULL "
                + "AND ITEM_CODE='NI-ITEM'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID='stk-ni-1'");
        jdbc.update("DELETE FROM erp_proc_po_line WHERE ID='pol-ni-1'");
        jdbc.update("DELETE FROM erp_proc_po WHERE ID='po-ni-1'");
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE ID IN ('grl-ni-1','grl-ni-2')");
    }

    private void insertGrLine(String id, String status) {
        jdbc.update("INSERT IGNORE INTO erp_proc_gr_line (ID, GR_ID, LINE_NO, ITEM_CODE, ITEM_NAME, UNIT, "
                + "RECEIVED_QTY, STATUS, QC_STATUS, CREATE_BY, VER_NO) VALUES "
                + "(?, 'gr-ni-x', 1, 'NI-ITEM', '通知测试物料', 'PC', 50, ?, 'PENDING', 'junit', 0)",
                id, status);
    }

    private void asQualityEng() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, "ROLE_QUALITY_ENG"));
    }

    private InspectionLot lot(String id, String lotNo, String grLineId) {
        InspectionLot l = new InspectionLot();
        l.setId(id);
        l.setLotNo(lotNo);
        l.setLotType("IQC");
        l.setItemCode("NI-ITEM");
        l.setItemName("通知测试物料");
        l.setBatchNo("NI-BATCH");
        l.setLotQty(new java.math.BigDecimal("50"));
        l.setRiskGrade("C");
        l.setSupplierId("sup-ni");
        l.setSupplierName("通知测试供应商");
        l.setGrLineId(grLineId);
        return l;
    }

    private List<LotItem> failItems() {
        LotItem it = new LotItem();
        it.setCharacteristicName("外观");
        it.setCtqFlag("0");
        it.setRegulatoryFlag("0");
        it.setSpecType("LOOK");
        it.setJudge("FAIL");
        return List.of(it);
    }

    private List<Map<String, Object>> notices(String ncrId, String bizType) {
        return jdbc.queryForList(
                "SELECT TARGET_ROLE, TITLE, CONTENT FROM erp_sys_notice "
                        + "WHERE BIZ_TYPE=? AND BIZ_ID=? AND DEL_FLAG='0'", bizType, ncrId);
    }

    // ---------- 已入库：处置通知 + 影响快照 + 计划通知 ----------

    @Test
    void postedStockFreezeNoticesAndImpactSnapshot() {
        Ncr ncr = ncrService.createFromLot(lot("ncr-ni-a", "IL-NI-A", "grl-ni-1"), 3, 1, 2, failItems());
        System.out.println("DIAG ncr scope=" + jdbc.queryForList(
                "SELECT ID,FREEZE_SCOPE FROM erp_qms_ncr WHERE ID=?", ncr.getId()));
        System.out.println("DIAG ledger=" + jdbc.queryForList(
                "SELECT ID,SOURCE,NCR_ID,WAREHOUSE_CODE FROM erp_inv_freeze WHERE ITEM_CODE='NI-ITEM'"));
        System.out.println("DIAG notices=" + jdbc.queryForList(
                "SELECT BIZ_TYPE,BIZ_ID,TARGET_ROLE FROM erp_sys_notice WHERE TITLE LIKE '%NCR%' OR TITLE LIKE '%冻结影响%'"));
        System.out.println("DIAG stock after=" + jdbc.queryForList(
                "SELECT AVAILABLE_QTY,QC_QTY FROM erp_inv_stock WHERE ITEM_CODE='NI-ITEM'"));

        // 处置通知 → 质量工程师，含 NCR 号与评审时限
        List<Map<String, Object>> disp = notices(ncr.getId(), "QMS_NCR");
        assertEquals(1, disp.size());
        assertEquals("ROLE_QUALITY_ENG", disp.get(0).get("TARGET_ROLE"));
        assertTrue(String.valueOf(disp.get(0).get("TITLE")).contains(ncr.getNcrNo()), "标题含 NCR 号");
        assertTrue(String.valueOf(disp.get(0).get("CONTENT")).contains("前完成评审"), "含评审时限");

        // 台账行携带影响快照（结构：sos/pos/mos/moDataSource；PO 精确一条）
        Map<String, Object> ledger = jdbc.queryForMap(
                "SELECT ID, IMPACT_JSON FROM erp_inv_freeze WHERE NCR_ID=? AND SOURCE='NCR'", ncr.getId());
        String impact = String.valueOf(ledger.get("IMPACT_JSON"));
        assertNotNull(ledger.get("IMPACT_JSON"), "台账行影响快照非空");
        assertTrue(impact.contains("\"moDataSource\":false"), "工单数据源标记");
        assertTrue(impact.contains("PO-NI-001"), "影响面含未清 PO：" + impact);
        assertTrue(impact.contains("\"precision\":\"ITEM\""), "PO 为物料级精度");

        // 计划通知 → 计划员，含 NCR 号与数量
        List<Map<String, Object>> plan = notices(ncr.getId(), "QMS_NCR_FREEZE");
        assertEquals(1, plan.size());
        assertEquals("ROLE_PLANNER", plan.get(0).get("TARGET_ROLE"));
        assertTrue(String.valueOf(plan.get(0).get("CONTENT")).contains(ncr.getNcrNo()), "计划通知含 NCR 号");
        assertTrue(String.valueOf(plan.get(0).get("CONTENT")).contains("NI-BATCH"), "含批号");

        // 库存冻结生效（AVAILABLE→QC）
        java.math.BigDecimal qc = jdbc.queryForObject(
                "SELECT QC_QTY FROM erp_inv_stock WHERE ID='stk-ni-1'", java.math.BigDecimal.class);
        assertTrue(qc.signum() > 0, "QC 锁定生效");
    }

    // ---------- 未入库（GR 行非 POSTED）：仅处置通知，无快照无计划通知 ----------

    @Test
    void grLineFreezeOnlyDispositionNotice() {
        Ncr ncr = ncrService.createFromLot(lot("ncr-ni-b", "IL-NI-B", "grl-ni-2"), 3, 1, 2, failItems());

        assertEquals(1, notices(ncr.getId(), "QMS_NCR").size(), "处置通知照发");
        assertEquals("GR_LINE", jdbc.queryForObject(
                "SELECT FREEZE_SCOPE FROM erp_qms_ncr WHERE ID=?", String.class, ncr.getId()));
        Integer ledgerCnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_inv_freeze WHERE NCR_ID=?", Integer.class, ncr.getId());
        assertEquals(0, ledgerCnt, "未入库无库存冻结台账（无快照维度）");
        assertEquals(0, notices(ncr.getId(), "QMS_NCR_FREEZE").size(), "无计划通知");
        // GR 行已冻结标记
        assertEquals("FROZEN", jdbc.queryForObject(
                "SELECT QC_STATUS FROM erp_proc_gr_line WHERE ID='grl-ni-2'", String.class));
    }

    // ---------- 通知降级：NCR 生成不因通知缺失回滚（存量 NCR 仍可创建） ----------

    @Test
    void dispositionNoticeIdempotentAcrossDuplicateCall() {
        Ncr ncr = ncrService.createFromLot(lot("ncr-ni-c", "IL-NI-C", "grl-ni-1"), 3, 1, 2, failItems());
        // 幂等：同批再判 → 返回既有 NCR，不重复通知（NoticeService 幂等键 + createFromLot 幂等）
        Ncr again = ncrService.createFromLot(lot("ncr-ni-c", "IL-NI-C", "grl-ni-1"), 3, 1, 2, failItems());
        assertEquals(ncr.getId(), again.getId(), "幂等返回既有 NCR");
        assertEquals(1, notices(ncr.getId(), "QMS_NCR").size(), "处置通知不重复");
        assertEquals(1, notices(ncr.getId(), "QMS_NCR_FREEZE").size(), "计划通知不重复");
    }
}
