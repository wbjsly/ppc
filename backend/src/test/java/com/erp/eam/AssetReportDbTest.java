package com.erp.eam;

import com.erp.common.ServiceException;
import com.erp.service.eam.EamReportService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-asset-report：资产报表 DbTest（spec asset-report）。
 * 七路事件流水口径 / 组合筛选倒序 / 三栏勾对平衡与异常标注 / 政策零金额 /
 * 折旧汇总环比标红 / 明细一致 / 净值台账排除与残值 / 盘点报告聚合与未结案 /
 * 导出超限 422 与筛选一致。
 */
@SpringBootTest
class AssetReportDbTest {

    @Autowired
    private EamReportService reportService;
    @Autowired
    private com.erp.service.SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    private static final String FROM = "2026-01-01";
    private static final String TO = "2026-12-31";

    @BeforeEach
    void setUp() {
        purge();
        login("junit");
        seedBase();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, "ROLE_ADMIN"));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_eam_count_diff WHERE CNT_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_eam_count_line WHERE CNT_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_eam_count_plan WHERE CNT_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_eam_asset_change WHERE CHANGE_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_eam_transfer_order WHERE TRF_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_eam_disposal_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_eam_disposal_order WHERE DSP_NO LIKE 'RPT-%') t1)");
        jdbc.update("DELETE FROM erp_eam_disposal_order WHERE DSP_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_eam_capitalization WHERE CAP_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_eam_rework_order WHERE RWF_NO LIKE 'RPT-%'");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION') t2)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION'");
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'RPT-CARD-%'");
        jdbc.update("DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'RPT-JZ-%'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'RPT-CARD-%'");
        sysParamService.setValue("EAM_REPORT_EXPORT_MAX", "50000", "NUMBER", "EAM", "junit 复位", "junit");
    }

    /** 造数：期间内各事件源一行 + 对应卡片（快照口径可勾平） */
    private void seedBase() {
        // 转资 + 卡片（生效日=期间内 → 快照增加侧一致）
        jdbc.update("INSERT INTO erp_eam_capitalization (ID,CAP_NO,MODE,SOURCE_ID,ASSET_NAME,"
                + "CATEGORY_CODE,TOTAL_AMOUNT,UNIT_AMOUNT,LIFE_YEARS,RESIDUAL_RATE,EFFECTIVE_DATE,"
                + "STATUS,CARD_COUNT,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES "
                + "('rpt-cap-1','RPT-CAP-0001','INVEST','src-1','RPT 转资资产','MACH',"
                + "100000,100000,10,0.05,'2099-03-01','COMPLETED',1,'junit','2099-03-01', '0',0)");
        mkCard("RPT-CARD-001", "MACH", "100000", "0", "2099-03-01", "IN_USE", null);
        // 处置（EXECUTED + 行 ORIG_COST）
        jdbc.update("INSERT INTO erp_eam_disposal_order (ID,DSP_NO,DISPOSAL_TYPE,APP_STATUS,"
                + "EXECUTED_AT,TOTAL_COST,TOTAL_NBV,GAIN_LOSS,RECEIVABLE_BAL,CREATE_BY,CREATE_DATE,"
                + "DEL_FLAG,VER_NO) VALUES ('rpt-dsp-1','RPT-DSP-0001','SCRAP','EXECUTED',"
                + "'2026-06-15 10:00:00',50000,10000,-40000,0,'junit','2026-06-15','0',0)");
        jdbc.update("INSERT INTO erp_eam_disposal_line (ID,ORDER_ID,CARD_ID,CARD_NO,ASSET_NAME,"
                + "CATEGORY_CODE,ORIG_COST,ACCUM_DEPR,NBV,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                + "VALUES ('rpt-dspl-1','rpt-dsp-1','card-x','RPT-CARD-002','RPT 处置资产','MACH',"
                + "50000,40000,10000,'junit','2026-06-15','0',0)");
        mkCard("RPT-CARD-002", "MACH", "50000", "40000", "2026-01-10", "DISPOSED", "2026-06-15 10:00:00");
        // 技改重建（新卡 + 原卡归档两行）
        jdbc.update("INSERT INTO erp_eam_rework_order (ID,RWF_NO,CARD_ID,CARD_NO,ASSET_NAME,"
                + "CATEGORY_CODE,CONTENT,BUDGET_AMOUNT,ORIG_NBV,NEW_CARD_ID,NEW_COST,NEW_LIFE_YEARS,"
                + "ACCEPT_DATE,REWORK_STATUS,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES "
                + "('rpt-rwk-1','RPT-RWK-0001','card-old','RPT-CARD-003','RPT 技改原卡','MACH',"
                + "'RPT 技改',96000,60000,'card-new',156000,6,'2026-08-20','REBUILT',"
                + "'junit','2026-08-01','0',0)");
        // 调拨完成
        jdbc.update("INSERT INTO erp_eam_transfer_order (ID,TRF_NO,TRANSFER_TYPE,CARD_ID,CARD_NO,"
                + "REASON,STATUS,CREATE_BY,CREATE_DATE,UPDATE_DATE,DEL_FLAG,VER_NO) VALUES "
                + "('rpt-trf-1','RPT-TRF-0001','DEPT','card-t','RPT-CARD-004','RPT 调拨',"
                + "'COMPLETED','junit','2026-05-01','2026-05-02 10:00:00','0',0)");
        // 盘点差异（SHORT 核销 + LONG 已闭环）
        jdbc.update("INSERT INTO erp_eam_count_plan (ID,CNT_NO,COUNT_TYPE,BASE_DATE,STATUS,"
                + "TOTAL_QTY,COUNTED_QTY,COVERAGE_RATE,REPORT_STATUS,REPORT_SUMMARY,CREATE_BY,"
                + "CREATE_DATE,DEL_FLAG,VER_NO) VALUES ('rpt-plan-1','RPT-CNT-0001','FULL',"
                + "'2026-04-30','CLOSED',10,10,1.0,'APPROVED','RPT 盘点总结摘要：覆盖率 100%，差异已闭环',"
                + "'junit','2026-04-30','0',0)");
        jdbc.update("INSERT INTO erp_eam_count_diff (ID,PLAN_ID,CNT_NO,LINE_ID,DIFF_TYPE,CARD_ID,"
                + "CARD_NO,SNAP_NAME,SNAP_DEPT,SNAP_CC,SNAP_CATEGORY,SNAP_COST,DIFF_AMOUNT,REASON,"
                + "ADJUST_STATUS,STATUS,CREATE_BY,UPDATE_DATE,DEL_FLAG,VER_NO) VALUES "
                + "('rpt-diff-1','rpt-plan-1','RPT-CNT-0001','line-1','SHORT','c5','RPT-CARD-005',"
                + "'RPT 盘亏资产','RPT 部门','CC-RPT','MACH',8000,8000,'保管不善丢失','DONE','CLOSED',"
                + "'junit','2026-05-10','0',0),"
                + "('rpt-diff-2','rpt-plan-1','RPT-CNT-0001','line-2','LONG','c6','RPT-CARD-006',"
                + "'RPT 盘盈资产','RPT 部门','CC-RPT','MACH',0,6000,'账外未入账','DONE','CLOSED',"
                + "'junit','2026-05-10','0',0),"
                + "('rpt-diff-3','rpt-plan-1','RPT-CNT-0001','line-3','STATUS_CONFLICT','c7',"
                + "'RPT-CARD-007','RPT 状态冲突','RPT 部门','CC-RPT','MACH',0,0,'实物报废未走流程',"
                + "'NONE','CLOSED','junit','2026-05-10','0',0),"
                + "('rpt-diff-4','rpt-plan-1','RPT-CNT-0001','line-4','TAG_ISSUE','c8','RPT-CARD-008',"
                + "'RPT 无标签','RPT 部门','CC-RPT','MACH',0,0,'标签脱落','NONE','CLOSED',"
                + "'junit','2026-05-10','0',0)");
        // 减值 + 政策（已批）
        jdbc.update("INSERT INTO erp_eam_asset_change (ID,CHANGE_NO,CHANGE_TYPE,CARD_ID,CARD_NO,"
                + "IMPAIR_AMOUNT,STATUS,CREATE_BY,UPDATE_DATE,DEL_FLAG,VER_NO) VALUES "
                + "('rpt-chg-1','RPT-CHG-IMP','IMPAIRMENT','card-i','RPT-CARD-009',3000,'APPROVED',"
                + "'junit','2026-07-10','0',0)");
        jdbc.update("INSERT INTO erp_eam_asset_change (ID,CHANGE_NO,CHANGE_TYPE,CARD_ID,CARD_NO,"
                + "BEFORE_LIFE_YEARS,AFTER_LIFE_YEARS,STATUS,CREATE_BY,UPDATE_DATE,DEL_FLAG,VER_NO) "
                + "VALUES ('rpt-chg-2','RPT-CHG-POL','POLICY','card-p','RPT-CARD-010',10,12,"
                + "'APPROVED','junit','2026-09-10','0',0)");
    }

    private void mkCard(String cardNo, String category, String cost, String accum,
                        String effective, String status, String disposedAt) {
        jdbc.update("INSERT INTO erp_eam_asset_card (ID,CARD_NO,ASSET_NAME,CATEGORY_CODE,DEPT_NAME,"
                + "COST_CENTER_CODE,ASSET_COST,LIFE_YEARS,RESIDUAL_RATE,EFFECTIVE_DATE,STATUS,"
                + "DEPR_STATUS,ACCUM_DEPR,IMPAIRMENT,LOCK_FLAG,PLEDGED_FLAG,COUNT_FREEZE_FLAG,"
                + "DISPOSED_AT,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) VALUES "
                + "(?,?,CONCAT('RPT 资产-',?),?,'RPT 部门','CC-RPT',?,?,0.05,?,?,'IN_PROGRESS',?,0,"
                + "'0','0','0',?,?,'2026-01-01','0',0)",
                "card-" + cardNo.toLowerCase(), cardNo, cardNo, category, new BigDecimal(cost),
                10, java.sql.Date.valueOf(effective), status, new BigDecimal(accum),
                disposedAt, "junit");
    }

    // ================= 用例 =================

    @Test
    void sevenRouteEventsEachRow() {
        // 共享库隔离：折旧/减值历史残留（BD 前缀）可近百行，首页 100 会把 RPT 行挤出（先例同 2099 窗口根治）
        Map<String, Object> page = reportService.changeEvents(1, 500, "2000-01-01", "2099-12-31", null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) page.get("records");
        // 仅断言本测试造数（RPT 前缀）；窗口内可能混有历史已批变更单事件
        List<Map<String, Object>> rows = all.stream()
                .filter(r -> String.valueOf(r.get("SOURCE_NO")).startsWith("RPT-")).toList();
        // 9 行：转资1+处置1+技改2（新卡+原卡）+调拨1+盘点2（SHORT+LONG）+减值1+政策1
        assertEquals(9, rows.size(), "七路九行事件（技改两行/盘点两行）");
        long cap = rows.stream().filter(r -> "CAPITALIZATION".equals(r.get("EVT_TYPE"))).count();
        long dsp = rows.stream().filter(r -> "DISPOSAL".equals(r.get("EVT_TYPE"))).count();
        long rwk = rows.stream().filter(r -> "REWORK_REBUILD".equals(r.get("EVT_TYPE"))).count();
        long cnt = rows.stream().filter(r -> "COUNT_ADJUST".equals(r.get("EVT_TYPE"))).count();
        assertEquals(1, cap); assertEquals(1, dsp); assertEquals(2, rwk); assertEquals(2, cnt);
        // 金额口径：转资 +100000、处置 −50000
        Map<String, Object> capRow = rows.stream()
                .filter(r -> "CAPITALIZATION".equals(r.get("EVT_TYPE"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("100000").compareTo(
                (BigDecimal) capRow.get("AMOUNT_DELTA")), "转资 +原值");
        Map<String, Object> dspRow = rows.stream()
                .filter(r -> "DISPOSAL".equals(r.get("EVT_TYPE"))).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("-50000").compareTo(
                (BigDecimal) dspRow.get("AMOUNT_DELTA")), "处置 −原值");
    }

    @Test
    void filterAndDescOrder() {
        Map<String, Object> page = reportService.changeEvents(1, 100, FROM, TO, "COUNT_ADJUST",
                "MACH", "CC-RPT");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("records");
        assertEquals(2, rows.size(), "类型+分类+成本中心组合筛选");
        assertTrue(String.valueOf(rows.get(0).get("EVT_DATE"))
                .compareTo(String.valueOf(rows.get(1).get("EVT_DATE"))) >= 0, "日期倒序");
    }

    @Test
    void summaryBalanced() {
        // 极端未来单日窗口 2099-03-01：任何测试/冒烟不可能造 2099 年生效卡，杜绝跨类污染
        Map<String, Object> s = reportService.changeSummary("2099-03-01", "2099-03-01");
        // 快照侧增加 = 生效日 2026-03-01 的 RPT-CARD-001（100000）；3 月无处置
        assertEquals(0, new BigDecimal("100000.00").compareTo((BigDecimal) s.get("increase")),
                "快照口径期间增加");
        assertEquals(Boolean.TRUE, s.get("reconOk"),
                "造数自洽（转资事件=快照增加）勾对平衡");
        // 期末 = 期初 + 增 − 减 恒等校验
        BigDecimal close = (BigDecimal) s.get("closeCost");
        BigDecimal open = (BigDecimal) s.get("openCost");
        BigDecimal inc = (BigDecimal) s.get("increase");
        BigDecimal dec = (BigDecimal) s.get("decrease");
        assertEquals(0, open.add(inc).subtract(dec).compareTo(close), "期末=期初+增−减");
    }

    @Test
    void summaryAnomalyFlagged() {
        // 快照多一张期间内生效但无事件源的卡（手工建卡）→ 增加侧口径差 → reconOk=false
        mkCard("RPT-CARD-099", "MACH", "20000", "0", "2099-03-01", "IN_USE", null);
        Map<String, Object> s = reportService.changeSummary("2099-03-01", "2099-03-01");
        assertEquals(Boolean.FALSE, s.get("reconOk"), "口径差超容差标注勾对异常");
        assertTrue(String.valueOf(s.get("reconNote")).contains("勾对异常"), "异常说明列示");
    }

    @Test
    void policyZeroAmount() {
        Map<String, Object> page = reportService.changeEvents(1, 100, "2000-01-01", "2099-12-31", "POLICY", null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = ((List<Map<String, Object>>) page.get("records")).stream()
                .filter(r -> String.valueOf(r.get("SOURCE_NO")).startsWith("RPT-")).toList();
        assertEquals(1, rows.size());
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) rows.get(0).get("AMOUNT_DELTA")),
                "政策变更零金额");
        assertTrue(String.valueOf(rows.get(0).get("REMARK")).contains("10年→12年"), "年限变动说明");
    }

    @Test
    void depreciationSummaryRoundAlert() {
        seedDepRun("202606", "RPT-JZ-A", "MACH", "CC-RPT", "RPT-CARD-001", new BigDecimal("9500"));
        seedDepRun("202607", "RPT-JZ-B", "MACH", "CC-RPT", "RPT-CARD-001", new BigDecimal("12300"));
        Map<String, Object> s = reportService.depreciationSummary("202607");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byCat = (List<Map<String, Object>>) s.get("byCategory");
        Map<String, Object> mach = byCat.stream().filter(r -> "MACH".equals(r.get("dim")))
                .findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("12300.00").compareTo((BigDecimal) mach.get("curAmt")));
        assertEquals(0, new BigDecimal("9500.00").compareTo((BigDecimal) mach.get("prevAmt")));
        assertEquals(Boolean.TRUE, mach.get("alerted"), "环比差异 2800 > 容差标红");
        Map<String, Object> run = (Map<String, Object>) s.get("run");
        assertEquals("RPT-JZ-B", run.get("runNo"), "页头计提单");
    }

    private void seedDepRun(String period, String runNo, String category, String cc,
                            String cardNo, BigDecimal amt) {
        jdbc.update("INSERT INTO erp_eam_depreciation_run (ID,RUN_NO,PERIOD_TAG,STATUS,TOTAL_DEPR,"
                + "RECON_DIFF,WARNING_COUNT,REVERSED_FLAG,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                + "VALUES (?,?,?,'POSTED',?,0,0,'0','junit','2026-01-01','0',0)",
                "rpt-run-" + period, runNo, period, amt);
        jdbc.update("INSERT INTO erp_eam_depreciation_detail (ID,RUN_ID,PERIOD_TAG,CARD_ID,CARD_NO,"
                + "BEGIN_NBV,PERIOD_DEPR,END_ACCUM_DEPR,END_NBV,COST_CENTER_CODE,ASSET_NAME,"
                + "ASSET_COST,REVERSED,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "(?,?,?,?,?,100000,?, ?, ?,?, 'RPT 资产',100000,'0','junit','0',0)",
                "rpt-det-" + period, "rpt-run-" + period, period, "card-" + cardNo.toLowerCase(),
                cardNo, amt, amt, new BigDecimal("100000").subtract(amt), cc);
    }

    @Test
    void depreciationDetailsConsistent() {
        seedDepRun("202606", "RPT-JZ-A", "MACH", "CC-RPT", "RPT-CARD-001", new BigDecimal("9500"));
        List<Map<String, Object>> details = reportService.depreciationDetails("202606");
        assertEquals(1, details.size(), "明细与计提单一致");
        assertEquals(0, new BigDecimal("9500.00").compareTo(
                (BigDecimal) details.get(0).get("PERIOD_DEPR")));
    }

    @Test
    void ledgerExcludesAndResidual() {
        // IN_USE 常驻 + DISPOSED/IN_REWORK/REPLACED 各一 + 已提足卡（净值=残值）
        mkCard("RPT-CARD-101", "MACH", "100000", "40000", "2026-01-01", "IN_USE", null);
        mkCard("RPT-CARD-102", "MACH", "50000", "10000", "2026-01-01", "IN_REWORK", null);
        mkCard("RPT-CARD-103", "MACH", "50000", "10000", "2026-01-01", "REPLACED", null);
        Map<String, Object> page = reportService.netAssetLedger(1, 100, null, null, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) page.get("records");
        assertTrue(rows.stream().noneMatch(r -> "RPT-CARD-102".equals(r.get("CARD_NO"))),
                "在建卡不入台账");
        assertTrue(rows.stream().noneMatch(r -> "RPT-CARD-103".equals(r.get("CARD_NO"))),
                "归档卡不入台账");
        Map<String, Object> inUse = rows.stream()
                .filter(r -> "RPT-CARD-101".equals(r.get("CARD_NO"))).findFirst().orElseThrow();
        // 净值 = 100000 − 40000 − 0 = 60000
        assertEquals(0, new BigDecimal("60000.00").compareTo((BigDecimal) inUse.get("NET_VALUE")),
                "净值=原值−累计−减值");
    }

    @Test
    void countReportAggregationAndNotReady() {
        // 已归档计划：四类分布 + TOP + 原因
        Map<String, Object> d = reportService.countReportDetail("rpt-plan-1");
        assertEquals(Boolean.TRUE, d.get("reportReady"));
        assertEquals("RPT 盘点总结摘要：覆盖率 100%，差异已闭环", d.get("reportSummary"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byType = (List<Map<String, Object>>) d.get("diffByType");
        assertEquals(4, byType.size(), "四类差异分布");
        assertEquals(4, d.get("diffCount"), "差异笔数");
        // 差异率 = 4/10 = 0.4
        assertEquals(0, new BigDecimal("0.4000").compareTo((BigDecimal) d.get("diffRate")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> reasons = (List<Map<String, Object>>) d.get("reasons");
        assertEquals(4, reasons.size(), "原因聚合");
        // 未结案计划：仅进度
        jdbc.update("INSERT INTO erp_eam_count_plan (ID,CNT_NO,COUNT_TYPE,BASE_DATE,STATUS,"
                + "TOTAL_QTY,COUNTED_QTY,COVERAGE_RATE,CREATE_BY,CREATE_DATE,DEL_FLAG,VER_NO) "
                + "VALUES ('rpt-plan-2','RPT-CNT-0002','FULL','2026-10-01','ISSUED',20,5,0.25,"
                + "'junit','2026-10-01','0',0)");
        Map<String, Object> nr = reportService.countReportDetail("rpt-plan-2");
        assertEquals(Boolean.FALSE, nr.get("reportReady"), "未结案报告未生成");
        assertNotNull(nr.get("progress"), "仅显示进度");
        assertNull(nr.get("diffByType"), "不显示差异分析");
    }

    @Test
    void exportLimit422() {
        sysParamService.setValue("EAM_REPORT_EXPORT_MAX", "1", "NUMBER", "EAM", "junit 临时上限", "junit");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reportService.exportChangeCsv(FROM, TO, null, null, null));
        assertTrue(ex.getMessage().contains("上限"), "超限 422 提示缩小范围");
    }

    @Test
    void exportFilterConsistent() {
        String csv = reportService.exportChangeCsv(FROM, TO, "DISPOSAL", null, null);
        assertTrue(csv.startsWith("\uFEFF"), "含 BOM");
        assertTrue(csv.contains("变动日期,变动类型"), "中文表头");
        assertTrue(csv.contains("RPT-DSP-0001"), "筛选一致（仅处置事件）");
        assertFalse(csv.contains("RPT-CAP-0001"), "未命中类型不出现");
    }
}
