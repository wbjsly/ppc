package com.erp.inv;

import com.erp.dao.inv.InvCheckReportDao;
import com.erp.dao.inv.InvDailyBalanceDao;
import com.erp.dao.inv.InvStockDao;
import com.erp.dao.system.SysNoticeDao;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.entity.inv.InvCheckReport;
import com.erp.entity.inv.InvDailyBalance;
import com.erp.entity.inv.InvStock;
import com.erp.entity.system.SysNotice;
import com.erp.service.inv.StockSnapshotService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 恒等式校验与日结 DB 侧语义（真实 MySQL，spec stock-snapshot，任务 5.1/5.2）：
 * 差异识别 + 报告/通知落库 + 按运行日幂等；日结快照生成 + 按结算日幂等（重跑不重复）。
 */
@SpringBootTest
class StockReconcileDbTest {

    private static final String WH = "WH-CK";

    @Autowired
    private StockSnapshotService service;
    @Autowired
    private InvStockDao stockDao;
    @Autowired
    private InvCheckReportDao checkReportDao;
    @Autowired
    private InvDailyBalanceDao dailyBalanceDao;
    @Autowired
    private SysNoticeDao noticeDao;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> stockIds = new ArrayList<>();

    @BeforeEach
    void purgeResidue() {
        // 历史轮次残留：当日报告/昨日快照会触发幂等跳过，先物理清除保证可重入
        jdbc.update("DELETE FROM erp_inv_check_report WHERE RUN_DATE = ?", LocalDate.now());
        jdbc.update("DELETE FROM erp_inv_daily_balance WHERE BAL_DATE = ?",
                LocalDate.now().minusDays(1));
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('STOCK_CHECK','STOCK_DAYCLOSE')");
    }

    @AfterEach
    void cleanup() {
        if (!stockIds.isEmpty()) {
            String ph = String.join(",", java.util.Collections.nCopies(stockIds.size(), "?"));
            jdbc.update("DELETE FROM erp_inv_stock WHERE ID IN (" + ph + ")", stockIds.toArray());
            stockIds.clear();
        }
    }

    private InvStock insertStock(String suffix, String qty, String avail, String qc, String fin) {
        InvStock s = new InvStock();
        s.setId("junit-ck-" + suffix + "-" + System.nanoTime());
        stockIds.add(s.getId());
        s.setWarehouseCode(WH);
        s.setItemCode("IT-CK-" + suffix);
        s.setItemName("校验测试物料");
        s.setBatchNo("B-CK-" + suffix);
        s.setQty(new BigDecimal(qty));
        s.setAvailableQty(new BigDecimal(avail));
        s.setQcQty(new BigDecimal(qc));
        s.setFinQty(new BigDecimal(fin));
        stockDao.insert(s);
        return s;
    }

    /** 5.1 差异识别 → 报告 + 通知仓库主管 + 按运行日幂等（BR-4.4-15） */
    @Test
    void checkDetectsMismatchAndIsIdempotentPerDay() {
        insertStock("BAL", "80", "80", "0", "0");     // 恒等：80 = 80+0+0
        insertStock("BAD", "100", "80", "0", "0");    // 差异：100 ≠ 80（历史出库未扣 QTY 类）

        Map<String, Object> first = service.runCheckReport();
        assertEquals(Boolean.FALSE, first.get("skipped"));
        assertEquals(InvCheckReport.ST_MISMATCH, first.get("status"));
        assertTrue((Integer) first.get("mismatchCnt") >= 1, "至少识别出 BAD 维度");

        // 报告落库（当日唯一）+ 明细含差异维度
        List<InvCheckReport> reports = checkReportDao.selectList(
                new LambdaQueryWrapper<InvCheckReport>().eq(InvCheckReport::getRunDate,
                        LocalDate.now()));
        assertEquals(1, reports.size());
        assertTrue(reports.get(0).getDetailJson().contains("IT-CK-BAD"), "明细含差异维度");
        assertFalse(reports.get(0).getDetailJson().contains("IT-CK-BAL"), "恒等维度不入明细");

        // 通知仓库主管（仅落表）
        List<SysNotice> notices = noticeDao.selectList(new LambdaQueryWrapper<SysNotice>()
                .eq(SysNotice::getBizType, "STOCK_CHECK"));
        assertEquals(1, notices.size());
        assertEquals("ROLE_WAREHOUSE", notices.get(0).getTargetRole());

        // 幂等：同日重跑 → 跳过，不重复报告/通知
        Map<String, Object> second = service.runCheckReport();
        assertEquals(Boolean.TRUE, second.get("skipped"));
        assertEquals(1, checkReportDao.selectList(new LambdaQueryWrapper<InvCheckReport>()
                .eq(InvCheckReport::getRunDate, LocalDate.now())).size());
        assertEquals(1, noticeDao.selectList(new LambdaQueryWrapper<SysNotice>()
                .eq(SysNotice::getBizType, "STOCK_CHECK")).size());

        // 4.3.1 页面：差异维度标「待核实」
        Map<String, Object> snap = service.query(WH, "IT-CK-BAD", null, null);
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) snap.get("rows");
        assertEquals(Boolean.TRUE, rows.get(0).get("pendingVerify"));
    }

    /** 5.2 日结：昨日快照生成 + 二次执行不重复（插入即终态） */
    @Test
    void dayCloseSnapshotAndIdempotent() {
        insertStock("DC", "50", "40", "10", "0");
        LocalDate yesterday = LocalDate.now().minusDays(1);

        Map<String, Object> first = service.runDayClose();
        assertEquals(Boolean.FALSE, first.get("skipped"));
        assertTrue((Integer) first.get("inserted") >= 1);

        List<InvDailyBalance> rows = dailyBalanceDao.selectList(
                new LambdaQueryWrapper<InvDailyBalance>().eq(InvDailyBalance::getBalDate, yesterday)
                        .eq(InvDailyBalance::getItemCode, "IT-CK-DC"));
        assertEquals(1, rows.size());
        InvDailyBalance b = rows.get(0);
        assertEquals(0, new BigDecimal("50").compareTo(b.getQty()));
        assertEquals(0, new BigDecimal("10").compareTo(b.getQcQty()));

        // 快照后业务变动不改已生成快照（终态语义）
        InvStock s = stockDao.selectById(stockIds.get(0));
        s.setQty(new BigDecimal("10"));
        s.setAvailableQty(new BigDecimal("10"));
        s.setQcQty(BigDecimal.ZERO);
        stockDao.updateById(s);

        Map<String, Object> second = service.runDayClose();
        assertEquals(Boolean.TRUE, second.get("skipped"), "同结算日重跑跳过");
        List<InvDailyBalance> again = dailyBalanceDao.selectList(
                new LambdaQueryWrapper<InvDailyBalance>().eq(InvDailyBalance::getBalDate, yesterday)
                        .eq(InvDailyBalance::getItemCode, "IT-CK-DC"));
        assertEquals(1, again.size(), "不重复生成");
        assertEquals(0, new BigDecimal("50").compareTo(again.get(0).getQty()), "快照未被后续变动改写");
    }
}
