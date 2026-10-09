package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.inv.InvFreezeDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.inv.InvFreeze;
import com.erp.entity.inv.InvStock;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.FreezeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 冻结位行拆分与明细回补（change add-bin-assignment，spec freeze-management 内部机制，
 * design D7，任务 6.1）：同批次跨多仓位 FIFO 拆行平移 + DETAIL_JSON 行级明细、
 * 解冻原路回补、明细行被合并消失后缺口兜底到现存位行、逐行恒等式保持。
 */
@SpringBootTest
class FreezeBinSplitDbTest {

    private static final String WH = "WH-FZB";
    private static final String ITEM = "IT-FZB-01";
    private static final String BATCH = "B-FZB";
    private static final String BIN_A = "FZ-A-01";
    private static final String BIN_C = "FZ-C-01";

    @Autowired
    private FreezeService freezeService;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private InvFreezeDao freezeDao;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> ids = new ArrayList<>();

    @BeforeEach
    void setUp() {
        purge();
        login("fzb-eng", "ROLE_QUALITY_ENG");
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval a ON t.APPR_ID = a.ID "
                + "WHERE a.BIZ_TYPE IN ('Freeze','Unfreeze') AND a.BIZ_ID IN "
                + "(SELECT ID FROM erp_inv_freeze WHERE WAREHOUSE_CODE = '" + WH + "')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('Freeze','Unfreeze') "
                + "AND BIZ_ID IN (SELECT ID FROM erp_inv_freeze WHERE WAREHOUSE_CODE = '" + WH + "')");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_inv_stock WHERE WAREHOUSE_CODE = ?", WH);
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('FREEZE','UNFREEZE') "
                + "AND TITLE LIKE '%FZB%' OR CONTENT LIKE '%IT-FZB%'");
        ids.clear();
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    /** 同批次两位行：A（早入库）可用 30 / C（晚入库）可用 40 */
    private void seedTwoBins() {
        insertRow("fzb-a", BIN_A, "2026-10-01", "30");
        insertRow("fzb-c", BIN_C, "2026-10-05", "40");
    }

    private void insertRow(String id, String bin, String inbound, String avail) {
        InvStock s = new InvStock();
        s.setId(id);
        s.setWarehouseCode(WH);
        s.setItemCode(ITEM);
        s.setItemName("拆行冻结测试物料");
        s.setBatchNo(BATCH);
        s.setBinCode(bin);
        s.setInboundDate(LocalDate.parse(inbound));
        s.setAvailableQty(new BigDecimal(avail));
        s.setQcQty(BigDecimal.ZERO);
        s.setFinQty(BigDecimal.ZERO);
        s.setQty(new BigDecimal(avail));
        stockSave(s);
        ids.add(id);
    }

    private void stockSave(InvStock s) {
        try {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(
                            new com.baomidou.mybatisplus.core.MybatisConfiguration(), ""),
                    InvStock.class);
        } catch (Exception ignore) {
            // 已初始化
        }
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, "
                + "BATCH_NO, BIN_CODE, INBOUND_DATE, QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, "
                + "CREATE_BY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'junit')",
                s.getId(), s.getWarehouseCode(), s.getItemCode(), s.getItemName(),
                s.getBatchNo(), s.getBinCode(), s.getInboundDate().toString(), s.getQty(),
                s.getQcQty(), s.getFinQty(), s.getAvailableQty());
    }

    private InvFreeze applyAndApprove(String qty) {
        InvFreeze req = new InvFreeze();
        req.setFreezeType(InvFreeze.T_QUALITY);
        req.setWarehouseCode(WH);
        req.setItemCode(ITEM);
        req.setBatchNo(BATCH);
        req.setQty(new BigDecimal(qty));
        req.setReason("跨位拆行冻结测试");
        req.setScope(InvFreeze.SCOPE_BATCH);
        InvFreeze f = freezeService.apply(req);
        ids.add(f.getId());
        var tasks = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, f.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE")
                .orderByAsc(ApprovalTask::getSeq));
        ApprovalTask task = tasks.get(0);
        assertNotNull(task);
        login("fzb-mgr", "ROLE_QUALITY_MGR");
        engine.pass(task.getId(), "同意冻结");
        return freezeDao.selectById(f.getId());
    }

    private void unfreezeAndApprove(InvFreeze f) {
        login("fzb-eng", "ROLE_QUALITY_ENG");
        InvFreeze u = freezeService.applyUnfreeze(f.getId(), "已处理", "复检合格报告");
        ApprovalTask uTask = taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, u.getUnfreezeApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        assertNotNull(uTask);
        login("fzb-mgr", "ROLE_QUALITY_MGR");
        engine.pass(uTask.getId(), "同意解冻");
    }

    private BigDecimal col(String id, String col) {
        return jdbc.queryForObject("SELECT " + col + " FROM erp_inv_stock WHERE ID = ?",
                BigDecimal.class, id);
    }

    @Test
    void crossBinFreezeSplitsByFifoAndDetailRecorded() {
        seedTwoBins();
        InvFreeze done = applyAndApprove("50");

        assertEquals(InvFreeze.ST_ACTIVE, done.getStatus());
        // FIFO：A（10-01）冻 30 → avail 0；C（10-05）冻 20 → avail 20
        assertEquals(0, BigDecimal.ZERO.compareTo(col("fzb-a", "AVAILABLE_QTY")));
        assertEquals(0, new BigDecimal("30").compareTo(col("fzb-a", "QC_QTY")));
        assertEquals(0, new BigDecimal("20").compareTo(col("fzb-c", "AVAILABLE_QTY")));
        assertEquals(0, new BigDecimal("20").compareTo(col("fzb-c", "QC_QTY")));
        // 逐行恒等：qty = avail + qc + fin
        assertEquals(0, col("fzb-a", "QTY").compareTo(
                col("fzb-a", "AVAILABLE_QTY").add(col("fzb-a", "QC_QTY"))));
        assertEquals(0, col("fzb-c", "QTY").compareTo(
                col("fzb-c", "AVAILABLE_QTY").add(col("fzb-c", "QC_QTY"))));
        // 行级明细记录
        String detail = done.getDetailJson();
        assertNotNull(detail, "冻结单须记行级明细");
        assertTrue(detail.contains(BIN_A) && detail.contains(BIN_C), detail);

        // 解冻：按明细原路回补
        unfreezeAndApprove(done);
        assertEquals(0, new BigDecimal("30").compareTo(col("fzb-a", "AVAILABLE_QTY")), "A 原路回补");
        assertEquals(0, BigDecimal.ZERO.compareTo(col("fzb-a", "QC_QTY")));
        assertEquals(0, new BigDecimal("40").compareTo(col("fzb-c", "AVAILABLE_QTY")), "C 原路回补");
        assertEquals(0, BigDecimal.ZERO.compareTo(col("fzb-c", "QC_QTY")));
        assertEquals(InvFreeze.ST_RELEASED, freezeDao.selectById(done.getId()).getStatus());
    }

    @Test
    void detailRowMergedAwayFallsBackToSurvivors() {
        seedTwoBins();
        InvFreeze done = applyAndApprove("50");
        // 模拟上架合并：A 位行（QC 30）并入 C 位行后物理消失（design D6）
        jdbc.update("UPDATE erp_inv_stock SET QC_QTY = QC_QTY + 30, QTY = QTY + 30 "
                + "WHERE ID = 'fzb-c'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ID = 'fzb-a'");
        // C 现状：avail 20、qc 20+30=50、qty 70（合并守恒）

        unfreezeAndApprove(done);

        // 明细 A 缺失 → 先按明细回补 C 的 20，缺口 30 兜底回补 C 剩余 → 全部落 C
        assertEquals(0, new BigDecimal("70").compareTo(col("fzb-c", "AVAILABLE_QTY")),
                "缺口兜底回补到现存位行");
        assertEquals(0, BigDecimal.ZERO.compareTo(col("fzb-c", "QC_QTY")));
        assertEquals(0, col("fzb-c", "QTY").compareTo(col("fzb-c", "AVAILABLE_QTY")),
                "合并后行恒等保持");
        assertEquals(InvFreeze.ST_RELEASED, freezeDao.selectById(done.getId()).getStatus());
    }
}
