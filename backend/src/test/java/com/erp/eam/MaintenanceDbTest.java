package com.erp.eam;

import com.erp.entity.eam.EamAssetCard;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamSafetyLockDao;
import com.erp.entity.eam.EamSafetyLock;
import com.erp.service.eam.EamSafetyLockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-asset-maintenance：资产运维 DbTest（spec asset-maintenance）。
 * 覆盖挂锁阻断/计划批量生成/报修转工单/状态机/超领/成本归集/升级规则/MTBF。
 */
@SpringBootTest
class MaintenanceDbTest {

    @Autowired
    private EamSafetyLockService safetyLockService;
    @Autowired
    private com.erp.service.eam.EamMaintPlanService planService;
    @Autowired
    private com.erp.service.eam.EamRepairReportService reportService;
    @Autowired
    private com.erp.service.eam.EamWorkOrderService woService;
    @Autowired
    private com.erp.service.eam.EamMaintainStatsService statsService;
    @Autowired
    private EamSafetyLockDao lockDao;
    @Autowired
    private EamAssetCardDao cardDao;
    @Autowired
    private com.erp.service.SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        asAdmin();
    }

    @AfterEach
    void tearDown() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void asAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, "ROLE_ADMIN"));
    }

    private void asRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("junit", null, role));
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_eam_safety_lock WHERE CARD_NO LIKE 'ZC-TEST-%'");
        jdbc.update("DELETE FROM erp_eam_work_order_time WHERE WO_NO LIKE 'WO-%'");
        jdbc.update("DELETE FROM erp_eam_work_order WHERE CARD_NO LIKE 'ZC-TEST-%'");
        jdbc.update("DELETE FROM erp_eam_repair_report WHERE CARD_NO LIKE 'ZC-TEST-%'");
        jdbc.update("DELETE FROM erp_eam_maint_plan WHERE CARD_NO LIKE 'ZC-TEST-%'");
        jdbc.update("DELETE FROM erp_inv_material_issue_line WHERE ISSUE_ID IN "
                + "(SELECT ID FROM erp_inv_material_issue WHERE ISSUE_TYPE='MAINT')");
        jdbc.update("DELETE FROM erp_inv_material_issue WHERE ISSUE_TYPE='MAINT'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE LIKE 'SPK-%'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN ('AssetOverhaul','MaintBudgetDeviation') "
                + "AND (BIZ_ID LIKE 'ZC-TEST-%' OR BIZ_ID LIKE 'WO-%')");
        jdbc.update("UPDATE erp_sys_param SET DEL_FLAG='0' WHERE PARAM_KEY='EAM_LABOR_RATE_DEFAULT'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'ZC-TEST-%'");
    }

    /** 造测试卡片（在用） */
    private EamAssetCard mkCard(String cardNo) {
        EamAssetCard card = new EamAssetCard();
        card.setCardNo(cardNo);
        card.setAssetName("CQT 运维测试卡 " + cardNo);
        card.setCategoryCode("MACH");
        card.setAssetCost(new BigDecimal("500000"));
        card.setLifeYears(10);
        card.setResidualRate(new BigDecimal("0.05"));
        card.setDepreciationMethod("STRAIGHT_LINE");
        card.setEffectiveDate(java.time.LocalDate.of(2026, 1, 15));
        card.setStatus("IN_USE");
        card.setDeprStatus("DEPRECIATING");
        card.setAccumDepr(BigDecimal.ZERO);
        card.setCreateBy("junit");
        cardDao.insert(card);
        return card;
    }

    // ================= 2.1 挂锁：阻断 / 解锁 / 放行 =================

    @Test
    void safetyLockBlocksAndReleaseRestores() {
        EamAssetCard card = mkCard("ZC-TEST-L001");

        // 挂锁
        Map<String, Object> out = safetyLockService.lock(card.getId(), "主轴异响待排查", null);
        EamSafetyLock lock = (EamSafetyLock) out.get("lock");
        assertNotNull(lock.getLockNo(), "锁编号系统生成");
        assertTrue(lock.getLockNo().startsWith("LOCK"), "锁编号前缀 LOCK：" + lock.getLockNo());

        // 重复挂锁 → 409
        RuntimeException dup = assertThrows(RuntimeException.class,
                () -> safetyLockService.lock(card.getId(), "重复挂锁", null));
        assertTrue(dup.getMessage().contains("已存在未解除挂锁"), "重复挂锁应 409：" + dup.getMessage());

        // 阻断：assertNoActiveLock 抛 422 且含锁信息
        RuntimeException blocked = assertThrows(RuntimeException.class,
                () -> safetyLockService.assertNoActiveLock(card.getId()));
        assertTrue(blocked.getMessage().contains(lock.getLockNo()), "阻断信息含锁编号");
        assertTrue(blocked.getMessage().contains("junit"), "阻断信息含挂锁人");
        assertTrue(blocked.getMessage().contains("BR-4.13-16"), "标注规则号");

        // 非主管解锁 → 403
        asRole("ROLE_EQUIP_ENG");
        RuntimeException forbidden = assertThrows(RuntimeException.class,
                () -> safetyLockService.release(lock.getId(), "尝试解锁"));
        assertEquals(403, codeOf(forbidden), "工程师不可解锁");

        // 主管解锁
        asRole("ROLE_EQUIP_MGR");
        safetyLockService.release(lock.getId(), "排查完成");
        EamSafetyLock reloaded = lockDao.selectById(lock.getId());
        assertEquals("RELEASED", reloaded.getStatus(), "解锁后状态 RELEASED");
        assertEquals("junit", reloaded.getReleaseBy(), "解除人留痕");
        assertNotNull(reloaded.getReleaseTime(), "解除时间留痕");

        // 放行
        asAdmin();
        assertDoesNotThrow(() -> safetyLockService.assertNoActiveLock(card.getId()), "解锁后放行");

        // 重复解锁 → 409
        RuntimeException reRelease = assertThrows(RuntimeException.class,
                () -> safetyLockService.release(lock.getId(), "再解"));
        assertEquals(409, codeOf(reRelease), "已解锁不可重复解锁");
    }

    @Test
    void safetyLockListFilterByStatus() {
        EamAssetCard card = mkCard("ZC-TEST-L002");
        safetyLockService.lock(card.getId(), "冒烟挂锁", null);
        List<EamSafetyLock> active = lockDao.selectActiveByCard(card.getId());
        assertEquals(1, active.size(), "ACTIVE 锁一条");
        assertEquals(card.getCardNo(), active.get(0).getCardNo(), "锁关联卡片");
    }

    private int codeOf(RuntimeException ex) {
        return ((com.erp.common.ServiceException) ex).getCode();
    }

    // ================= 2.2 计划批量生成：幂等 / 审批阻断 / 停机顺延 =================

    @Test
    void planBatchGenerateIdempotent() {
        EamAssetCard card = mkCard("ZC-TEST-P001");
        var plan = new com.erp.entity.eam.EamMaintPlan();
        plan.setCardId(card.getId());
        plan.setItemName("CQT 月度点检");
        plan.setCycle("M");
        plan.setEstHours(new BigDecimal("2"));
        plan.setNextPlanDate(java.time.LocalDate.now().plusDays(1));
        plan = (com.erp.entity.eam.EamMaintPlan) planService.savePlan(plan).get("plan");
        assertEquals("NOT_REQUIRED", plan.getApprovalStatus(), "非关键计划无需审批");

        Map<String, Object> out1 = planService.generatePlanOrders();
        @SuppressWarnings("unchecked")
        var created1 = (List<String>) out1.get("created");
        assertEquals(1, created1.size(), "首次生成 1 张工单");

        // 二次执行幂等：不重复建单
        Map<String, Object> out2 = planService.generatePlanOrders();
        @SuppressWarnings("unchecked")
        var created2 = (List<String>) out2.get("created");
        assertTrue(created2.isEmpty(), "二次执行不重复建单：" + created2);

        // 工单字段核对
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_eam_work_order WHERE PLAN_ID = ? AND WO_TYPE='PREVENTIVE'",
                Integer.class, plan.getId());
        assertEquals(1, cnt, "计划仅一张有效工单");
        String src = jdbc.queryForObject(
                "SELECT SRC_TYPE FROM erp_eam_work_order WHERE PLAN_ID = ?", String.class, plan.getId());
        assertEquals("PLAN", src, "来源类型 PLAN");
    }

    @Test
    void criticalPlanBlockedWithoutApproval() {
        EamAssetCard card = mkCard("ZC-TEST-P002");
        var plan = new com.erp.entity.eam.EamMaintPlan();
        plan.setCardId(card.getId());
        plan.setItemName("CQT 关键设备保养");
        plan.setCycle("M");
        plan.setCriticalFlag("1");
        plan.setNextPlanDate(java.time.LocalDate.now().plusDays(1));
        plan = (com.erp.entity.eam.EamMaintPlan) planService.savePlan(plan).get("plan");
        assertEquals("PENDING_APPROVE", plan.getApprovalStatus(), "关键计划待审批");

        Map<String, Object> out = planService.generatePlanOrders();
        @SuppressWarnings("unchecked")
        var blocked = (List<String>) out.get("blocked");
        @SuppressWarnings("unchecked")
        var created = (List<String>) out.get("created");
        assertFalse(blocked.isEmpty(), "未审批关键计划被阻断");
        assertTrue(created.isEmpty(), "阻断项不建单");

        // 审批通过回调后可生成
        planService.approveFromApproval(plan.getId());
        Map<String, Object> out2 = planService.generatePlanOrders();
        @SuppressWarnings("unchecked")
        var created2 = (List<String>) out2.get("created");
        assertEquals(1, created2.size(), "审批通过后可生成");
    }

    @Test
    void planPostponedDuringActiveLock() {
        EamAssetCard card = mkCard("ZC-TEST-P003");
        safetyLockService.lock(card.getId(), "安全停机中", null);
        var plan = new com.erp.entity.eam.EamMaintPlan();
        plan.setCardId(card.getId());
        plan.setItemName("CQT 停机顺延点检");
        plan.setCycle("M");
        plan.setNextPlanDate(java.time.LocalDate.now().plusDays(1));
        plan = (com.erp.entity.eam.EamMaintPlan) planService.savePlan(plan).get("plan");

        Map<String, Object> out = planService.generatePlanOrders();
        @SuppressWarnings("unchecked")
        var created = (List<String>) out.get("created");
        @SuppressWarnings("unchecked")
        var postponed = (List<String>) out.get("postponed");
        assertTrue(created.isEmpty(), "停机期不建单");
        assertFalse(postponed.isEmpty(), "计划日顺延并留痕");
    }

    // ================= 2.3 报修：转工单 / 安全风险挂锁 / 驳回 =================

    @Test
    void repairReportConvertsWithLockCheck() {
        EamAssetCard card = mkCard("ZC-TEST-R001");
        var report = new com.erp.entity.eam.EamRepairReport();
        report.setCardId(card.getId());
        report.setFaultSymptom("主轴异响");
        report.setFaultTime(LocalDateTime.now());
        report.setDetail("CQT 冒烟报修");
        @SuppressWarnings("unchecked")
        var submitted = (com.erp.entity.eam.EamRepairReport) reportService.submit(report).get("report");
        assertEquals("PENDING", submitted.getStatus(), "报修初始待确认");
        assertTrue(submitted.getReportNo().startsWith("RB"), "报修单号前缀 RB");

        // 确认转工单
        @SuppressWarnings("unchecked")
        var out = reportService.convertToWorkOrder(submitted.getId(), "eng1", "SELF", null);
        var wo = (com.erp.entity.eam.EamWorkOrder) out.get("wo");
        assertEquals("REPAIR", wo.getWoType(), "维修工单");
        assertEquals("ASSIGNED", wo.getStatus(), "转工单即已派工");
        assertEquals("主轴异响", wo.getFaultSymptom(), "故障现象带入");

        // 二次转换 → 409
        RuntimeException dup = assertThrows(RuntimeException.class,
                () -> reportService.convertToWorkOrder(submitted.getId(), "eng1", "SELF", null));
        assertEquals(409, codeOf(dup), "已转工单不可重复转换");
    }

    @Test
    void repairWithSafetyRiskAutoLocks() {
        EamAssetCard card = mkCard("ZC-TEST-R002");
        var report = new com.erp.entity.eam.EamRepairReport();
        report.setCardId(card.getId());
        report.setFaultSymptom("液压管路漏油");
        report.setFaultTime(LocalDateTime.now());
        report.setSafetyRisk("1");
        reportService.submit(report);
        // 自动挂锁
        assertFalse(lockDao.selectActiveByCard(card.getId()).isEmpty(), "安全风险报修自动挂锁");
        // 转工单被阻断（挂锁中）
        var pending = reportDaoStub(card.getId());
        RuntimeException blocked = assertThrows(RuntimeException.class,
                () -> reportService.convertToWorkOrder(pending, "eng1", "SELF", null));
        assertTrue(blocked.getMessage().contains("安全停机"), "挂锁中不可派工：" + blocked.getMessage());
    }

    @Test
    void repairRejectKeepsReason() {
        EamAssetCard card = mkCard("ZC-TEST-R003");
        var report = new com.erp.entity.eam.EamRepairReport();
        report.setCardId(card.getId());
        report.setFaultSymptom("误操作停机");
        report.setFaultTime(LocalDateTime.now());
        @SuppressWarnings("unchecked")
        var submitted = (com.erp.entity.eam.EamRepairReport) reportService.submit(report).get("report");
        @SuppressWarnings("unchecked")
        var out = reportService.reject(submitted.getId(), "复位后正常，无需维修");
        var rej = (com.erp.entity.eam.EamRepairReport) out.get("report");
        assertEquals("REJECTED", rej.getStatus(), "驳回状态");
        assertEquals("复位后正常，无需维修", rej.getRejectReason(), "驳回原因留痕");
    }

    /** 取该卡最新 PENDING 报修单 ID */
    private String reportDaoStub(String cardId) {
        return jdbc.queryForObject(
                "SELECT ID FROM erp_eam_repair_report WHERE CARD_ID = ? AND STATUS = 'PENDING' "
                        + "ORDER BY CREATE_DATE DESC LIMIT 1", String.class, cardId);
    }

    // ================= 2.4 状态机：跳序 / 双验收 =================

    @Test
    void stateMachineBlocksSkip() {
        EamAssetCard card = mkCard("ZC-TEST-S001");
        String woNo = mkAssignedWo(card, "ZC-TEST-S001");
        // PENDING→EXECUTING 跳序（用另一张 PENDING 单验证）
        var plan = new com.erp.entity.eam.EamMaintPlan();
        plan.setCardId(card.getId());
        plan.setItemName("CQT 跳序测试");
        plan.setCycle("M");
        plan.setNextPlanDate(java.time.LocalDate.now().plusDays(1));
        plan = (com.erp.entity.eam.EamMaintPlan) planService.savePlan(plan).get("plan");
        planService.generatePlanOrders();
        String pendingNo = jdbc.queryForObject(
                "SELECT WO_NO FROM erp_eam_work_order WHERE PLAN_ID = ? AND STATUS = 'PENDING'",
                String.class, plan.getId());
        RuntimeException skip = assertThrows(RuntimeException.class,
                () -> woService.startExecution(pendingNo));
        assertEquals(422, codeOf(skip), "跳序 422");
        assertTrue(skip.getMessage().contains("防跳序"), "提示状态机：" + skip.getMessage());

        // 正常流转 PENDING→ASSIGNED→EXECUTING
        woService.assign(pendingNo, "eng1");
        @SuppressWarnings("unchecked")
        var out = woService.startExecution(pendingNo);
        var wo = (com.erp.entity.eam.EamWorkOrder) out.get("wo");
        assertEquals("EXECUTING", wo.getStatus(), "正常流转到执行中");
        assertNotNull(woNo);
    }

    @Test
    void repairDualAcceptanceRequired() {
        EamAssetCard card = mkCard("ZC-TEST-S002");
        // mkAssignedWo 产出自报修转工单（已是 ASSIGNED）
        String woNo = mkAssignedWo(card, "ZC-TEST-S002");
        woService.startExecution(woNo);
        woService.submitForAcceptance(woNo);
        // 仅工程师确认 → 未完成
        Boolean acc1 = (Boolean) woService.accept(woNo, "ENG", "GOOD").get("accepted");
        assertEquals(Boolean.FALSE, acc1, "REPAIR 须双确认");
        // 操作员补签 → 完成
        Boolean acc2 = (Boolean) woService.accept(woNo, "OP", null).get("accepted");
        assertEquals(Boolean.TRUE, acc2, "双确认齐验收完成");
    }

    /** 造一张 REPAIR 工单（报修→转工单，ASSIGNED 前置） */
    private String mkAssignedWo(EamAssetCard card, String anchor) {
        var report = new com.erp.entity.eam.EamRepairReport();
        report.setCardId(card.getId());
        report.setFaultSymptom(anchor + " 故障现象");
        report.setFaultTime(LocalDateTime.now());
        @SuppressWarnings("unchecked")
        var submitted = (com.erp.entity.eam.EamRepairReport) reportService.submit(report).get("report");
        @SuppressWarnings("unchecked")
        var out = reportService.convertToWorkOrder(submitted.getId(), "eng1", "SELF", null);
        var wo = (com.erp.entity.eam.EamWorkOrder) out.get("wo");
        return wo.getWoNo();
    }

    // ================= 3.1 备件领用：超领阻断 / 建单 =================

    @Test
    void spareOverQuantityBlockedWithoutReason() {
        EamAssetCard card = mkCard("ZC-TEST-B001");
        // 直插 EXECUTING 工单 + BOM 快照（滤芯×2）
        jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                        + "STATUS, BOM_JSON, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wo-b001','WO-B001','PREVENTIVE','MANUAL',?,'ZC-TEST-B001','EXECUTING',"
                        + "'[{\"itemCode\":\"SPK-B001\",\"itemName\":\"滤芯\",\"qty\":2}]','junit','0',0)",
                card.getId());
        // 备件库存
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "BIN_CODE, QTY, AVAILABLE_QTY, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('st-b001','WH-MAIN','SPK-B001','滤芯','BATCH-B001','A-01',10,10,'junit','0',0)");

        var line = Map.<String, Object>of("itemCode", "SPK-B001", "qty", 5);
        // 超领无原因 → 422 L1
        RuntimeException blocked = assertThrows(RuntimeException.class,
                () -> woService.requestSpares("WO-B001", List.of(line), null));
        assertEquals(422, codeOf(blocked), "超领无原因 L1 阻断");
        assertTrue(blocked.getMessage().contains("BR-4.13-17"), "标注规则：" + blocked.getMessage());

        // 超领有原因 → 建 MAINT 领料单
        @SuppressWarnings("unchecked")
        var out = (Map<String, Object>) woService.requestSpares("WO-B001", List.of(line), "滤芯老化加重");
        @SuppressWarnings("unchecked")
        var issue = (com.erp.entity.vmi.MaterialIssue) out.get("issue");
        assertEquals("MAINT", issue.getIssueType(), "领料单类型 MAINT");
        assertEquals("WO-B001", issue.getWorkOrderNo(), "锚定工单号");

        // 不超领（清单内 qty=2）无原因放行
        var within = Map.<String, Object>of("itemCode", "SPK-B001", "qty", 2);
        assertDoesNotThrow(() -> woService.requestSpares("WO-B001", List.of(within), null),
                "清单内领用放行");
    }

    @Test
    void spareRequestOnlyWhenExecuting() {
        EamAssetCard card = mkCard("ZC-TEST-B002");
        String woNo = mkAssignedWo(card, "ZC-TEST-B002"); // ASSIGNED
        var line = Map.<String, Object>of("itemCode", "SPK-X", "qty", 1);
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> woService.requestSpares(woNo, List.of(line), null));
        assertTrue(ex.getMessage().contains("仅执行中"), "非执行中不可领料：" + ex.getMessage());
    }

    // ================= 3.2 关闭前置（BR-20）+ 成本三构成 =================

    @Test
    void closeBlockedByOpenItems() {
        EamAssetCard card = mkCard("ZC-TEST-C001");
        // 工单：预计 4h、未回填工时；挂一张 DRAFT 领料 + 一个未闭子工单
        jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                        + "STATUS, EST_HOURS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wo-c001','WO-C001','REPAIR','MANUAL',?,'ZC-TEST-C001','PENDING_ACCEPT',"
                        + "4,'junit','0',0)", card.getId());
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('mi-c001','MI-C001','MAINT','WO-C001','DRAFT','junit','0',0)");
        jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                        + "STATUS, PARENT_WO_NO, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wo-c001s','WO-C001S','REPAIR','MANUAL',?,'ZC-TEST-C001','EXECUTING',"
                        + "'WO-C001','junit','0',0)", card.getId());

        RuntimeException ex = assertThrows(RuntimeException.class, () -> woService.close("WO-C001"));
        assertEquals(422, codeOf(ex), "存在未完事项 422");
        assertTrue(ex.getMessage().contains("BR-4.13-20"), "标注规则");
        assertTrue(ex.getMessage().contains("未过账领料"), "列出未过账领料");
        assertTrue(ex.getMessage().contains("实际工时"), "列出未回填工时");
        assertTrue(ex.getMessage().contains("子工单"), "列出未闭子工单");
    }

    @Test
    void closeAggregatesThreeCostParts() {
        EamAssetCard card = mkCard("ZC-TEST-C002");
        // 工单：外协 ¥500、无预计工时（免工时校验）
        jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                        + "STATUS, RESOLVE_PLAN, OUTSOURCE_AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wo-c002','WO-C002','REPAIR','MANUAL',?,'ZC-TEST-C002','PENDING_ACCEPT',"
                        + "'OUTSOURCE',500,'junit','0',0)", card.getId());
        // 已过账 MAINT 领料 ¥800
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, POST_BY, POST_DATE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('mi-c002','MI-C002','MAINT','WO-C002','POSTED','junit',NOW(),'junit','0',0)");
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                        + "ITEM_NAME, QTY, UNIT_PRICE, AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('mil-c002','mi-c002',1,'SPK-C002','轴承',2,400,800,'junit','0',0)");
        // 工时 4h TECH；费率种子行存在但为空串 → 置 100（测试后恢复空）
        jdbc.update("INSERT INTO erp_eam_work_order_time (ID, WO_NO, WORKER, POST_TYPE, WORK_HOURS, "
                        + "WORK_DATE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wt-c002','WO-C002','tech1','TECH',4,CURDATE(),'junit','0',0)");
        sysParamService.setValue("EAM_LABOR_RATE_TECH", "100", "NUMBER", "EAM", "junit", "junit");
        // 外协验收单（关闭前置④）
        jdbc.update("UPDATE erp_eam_work_order SET OUTSOURCE_ACCEPT_FILE='acc-c002.pdf' WHERE ID='wo-c002'");

        @SuppressWarnings("unchecked")
        var out = (Map<String, Object>) woService.close("WO-C002");
        @SuppressWarnings("unchecked")
        var wo = (com.erp.entity.eam.EamWorkOrder) out.get("wo");
        assertEquals("CLOSED", wo.getStatus(), "关闭成功");
        assertEquals(0, new BigDecimal("800.00").compareTo(wo.getSpareCost()), "备件成本 ¥800");
        assertEquals(0, new BigDecimal("400.00").compareTo(wo.getLaborCost()), "人工成本 4h×100=¥400");
        assertEquals(0, new BigDecimal("500").compareTo(wo.getOutsourceAmount()), "外协 ¥500");
        assertEquals(0, new BigDecimal("1700.00").compareTo(wo.getTotalCost()), "合计 ¥1,700");
        assertEquals("0", wo.getLaborRatePending(), "费率齐全不标待调整");
        sysParamService.setValue("EAM_LABOR_RATE_TECH", "", "NUMBER", "EAM", "junit", "junit");
    }

    @Test
    void closeMarksLaborRatePendingWhenMissing() {
        EamAssetCard card = mkCard("ZC-TEST-C003");
        jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wo-c003','WO-C003','PREVENTIVE','MANUAL',?,'ZC-TEST-C003','PENDING_ACCEPT',"
                        + "'junit','0',0)", card.getId());
        jdbc.update("INSERT INTO erp_eam_work_order_time (ID, WO_NO, WORKER, POST_TYPE, WORK_HOURS, "
                        + "WORK_DATE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wt-c003','WO-C003','eng1','OTHER',3,CURDATE(),'junit','0',0)");
        // 强制 DEFAULT 费率为空（种子即空串=缺参语义；API 写入刷缓存）
        sysParamService.setValue("EAM_LABOR_RATE_DEFAULT", "", "NUMBER", "EAM", "junit", "junit");
        try {
            @SuppressWarnings("unchecked")
            var out = (Map<String, Object>) woService.close("WO-C003");
            @SuppressWarnings("unchecked")
            var wo = (com.erp.entity.eam.EamWorkOrder) out.get("wo");
            assertEquals("CLOSED", wo.getStatus(), "费率缺失不阻断关闭（FR-4.13-3-6 容错）");
            assertEquals("1", wo.getLaborRatePending(), "标费率待调整");
            assertEquals(0, BigDecimal.ZERO.compareTo(wo.getLaborCost()), "人工成本=0");
        } finally {
            // 种子语义即空串，无需恢复
        }
    }

    private String sysParamValue(String key) {
        try {
            return jdbc.queryForObject(
                    "SELECT PARAM_VALUE FROM erp_sys_param WHERE PARAM_KEY = ? AND DEL_FLAG='0'",
                    String.class, key);
        } catch (Exception ex) {
            return null;
        }
    }

    // ================= 3.3 升级规则：BR-19 冻结解冻 / BR-18 预算偏差 =================

    @Test
    void threeSameFaultTriggersFreezeAndUnfreeze() {
        EamAssetCard card = mkCard("ZC-TEST-F001");
        String symptom = "主轴异响";
        // 前两张同故障已关闭（近 12 月）
        for (int i = 1; i <= 2; i++) {
            jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                            + "FAULT_SYMPTOM, STATUS, ACCEPT_DATE, CLOSE_DATE, CREATE_BY, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,?, 'ZC-TEST-F001', ?, 'CLOSED', DATE_SUB(NOW(), INTERVAL ? DAY), "
                            + "DATE_SUB(NOW(), INTERVAL ? DAY), 'junit','0',0)",
                    "wo-f001-" + i, "WO-F001-" + i, "REPAIR", "MANUAL", card.getId(), symptom,
                    100 - i * 10, 98 - i * 10);
        }
        // 第三张走全链 → 验收完成时触发评估
        String woNo = mkAssignedWo(card, "ZC-TEST-F001");
        jdbc.update("UPDATE erp_eam_work_order SET FAULT_SYMPTOM = ? WHERE WO_NO = ?", symptom, woNo);
        woService.startExecution(woNo);
        woService.submitForAcceptance(woNo);
        woService.accept(woNo, "ENG", "GOOD");
        Boolean done = (Boolean) woService.accept(woNo, "OP", null).get("accepted");
        assertEquals(Boolean.TRUE, done, "双验收完成");

        // 触发大修评估 + 冻结
        var wo = jdbc.queryForMap("SELECT ID, OVERHAUL_ASSESS_FLAG, ASSESS_STATUS "
                + "FROM erp_eam_work_order WHERE WO_NO = ?", woNo);
        assertEquals("1", String.valueOf(wo.get("OVERHAUL_ASSESS_FLAG")), "BR-19 评估已触发");
        assertEquals("PENDING_APPROVE", String.valueOf(wo.get("ASSESS_STATUS")), "评估待审批");

        // 同现象新报修 → 转工单被冻结 422
        var report = new com.erp.entity.eam.EamRepairReport();
        report.setCardId(card.getId());
        report.setFaultSymptom(symptom);
        report.setFaultTime(LocalDateTime.now());
        @SuppressWarnings("unchecked")
        var submitted = (com.erp.entity.eam.EamRepairReport) reportService.submit(report).get("report");
        RuntimeException frozen = assertThrows(RuntimeException.class,
                () -> reportService.convertToWorkOrder(submitted.getId(), "eng1", "SELF", null));
        assertTrue(frozen.getMessage().contains("BR-4.13-19"), "冻结提示规则：" + frozen.getMessage());

        // 评估归档 → 解冻
        woService.completeOverhaulAssess(String.valueOf(wo.get("id")), "APPROVED");
        @SuppressWarnings("unchecked")
        var out = reportService.convertToWorkOrder(submitted.getId(), "eng1", "SELF", null);
        assertNotNull(out.get("wo"), "评估归档后解冻可转工单");
    }

    @Test
    void budgetDeviationGeneratesNotice() {
        EamAssetCard card = mkCard("ZC-TEST-D001");
        jdbc.update("UPDATE erp_eam_asset_card SET MAINT_BUDGET = 10000 WHERE ID = ?", card.getId());
        // 关闭一张成本 11500 的工单（偏差 15% > 阈值 10%）
        jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('wo-d001','WO-D001','REPAIR','MANUAL',?,'ZC-TEST-D001','PENDING_ACCEPT',"
                        + "'junit','0',0)", card.getId());
        jdbc.update("INSERT INTO erp_inv_material_issue (ID, ISSUE_NO, ISSUE_TYPE, WORK_ORDER_NO, "
                        + "STATUS, POST_BY, POST_DATE, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('mi-d001','MI-D001','MAINT','WO-D001','POSTED','junit',NOW(),'junit','0',0)");
        jdbc.update("INSERT INTO erp_inv_material_issue_line (ID, ISSUE_ID, LINE_NO, ITEM_CODE, "
                        + "ITEM_NAME, QTY, UNIT_PRICE, AMOUNT, CREATE_BY, DEL_FLAG, VER_NO) "
                        + "VALUES ('mil-d001','mi-d001',1,'SPK-D001','主轴配件',1,11500,11500,'junit','0',0)");

        woService.close("WO-D001");
        Integer notices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE = 'MaintBudgetDeviation' "
                        + "AND BIZ_ID = 'ZC-TEST-D001'", Integer.class);
        assertTrue(notices >= 1, "BR-4.13-18 资本化评估提示已生成，实际 " + notices);
    }

    // ================= 4.1 报表：MTBF / 履历聚合 =================

    @Test
    void statsComputesMtbfAndMttr() {
        EamAssetCard card = mkCard("ZC-TEST-M001");
        // 三张已验收 REPAIR：故障间隔 90/120 天 → MTBF=105；报修→验收 2h → MTTR=2
        // 故障时间偏移 210/120/0 天 → 间隔 90/120 天 → MTBF=105；验收=故障+2h → MTTR=2
        String[][] rows = {
                {"wo-m001-1", "WO-M001-1", "210", "210"},
                {"wo-m001-2", "WO-M001-2", "120", "120"},
                {"wo-m001-3", "WO-M001-3", "0", "0"},
        };
        for (String[] r : rows) {
            jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, SRC_TYPE, CARD_ID, CARD_NO, "
                            + "FAULT_SYMPTOM, STATUS, FAULT_TIME, ACCEPT_DATE, CLOSE_DATE, TOTAL_COST, "
                            + "CREATE_BY, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,?, 'ZC-TEST-M001', '故障X', 'CLOSED', "
                            + "DATE_SUB(NOW(), INTERVAL ? DAY), "
                            + "DATE_ADD(DATE_SUB(NOW(), INTERVAL ? DAY), INTERVAL 2 HOUR), "
                            + "DATE_ADD(NOW(), INTERVAL -1 HOUR), 100, 'junit','0',0)",
                    r[0], r[1], "REPAIR", "MANUAL", card.getId(), r[2], r[3]);
        }
        @SuppressWarnings("unchecked")
        var out = statsService.stats(card.getId(), 12);
        assertEquals(3, out.get("faultCount"), "故障次数 3");
        assertEquals(0, new BigDecimal("105.00").compareTo((BigDecimal) out.get("mtbfDays")),
                "MTBF = (90+120)/2 = 105 天");
        assertEquals(0, new BigDecimal("2.00").compareTo((BigDecimal) out.get("mttrHours")),
                "MTTR = 2 小时");
    }

    @Test
    void historyAggregatesWorkOrdersAndLocks() {
        EamAssetCard card = mkCard("ZC-TEST-H001");
        String woNo = mkAssignedWo(card, "ZC-TEST-H001");
        safetyLockService.lock(card.getId(), "履历挂锁", null);
        @SuppressWarnings("unchecked")
        var out = statsService.maintenanceHistory(card.getId());
        @SuppressWarnings("unchecked")
        var wos = (List<?>) out.get("workOrders");
        @SuppressWarnings("unchecked")
        var locks = (List<?>) out.get("safetyLocks");
        assertTrue(wos.size() >= 1, "履历含工单");
        assertEquals(1, locks.size(), "履历含挂锁记录");
        assertNotNull(woNo);
    }
}
