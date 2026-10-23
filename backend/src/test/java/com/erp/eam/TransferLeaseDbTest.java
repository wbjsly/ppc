package com.erp.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamDepreciationDetailDao;
import com.erp.dao.eam.EamDepreciationRunDao;
import com.erp.dao.eam.EamLeaseContractDao;
import com.erp.dao.eam.EamLeaseScheduleDao;
import com.erp.dao.eam.EamTransferOrderDao;
import com.erp.dao.eam.EamWorkOrderDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.dao.fin.FinVoucherLineDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamDepreciationDetail;
import com.erp.entity.eam.EamDepreciationRun;
import com.erp.entity.eam.EamLeaseContract;
import com.erp.entity.eam.EamLeaseSchedule;
import com.erp.entity.eam.EamTransferOrder;
import com.erp.entity.eam.EamWorkOrder;
import com.erp.entity.fin.FinVoucherLine;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.SysParamService;
import com.erp.service.eam.EamDepreciationService;
import com.erp.service.eam.EamLeaseService;
import com.erp.service.eam.EamTransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-asset-transfer-lease：调拨 + 租赁 + 闲置共享池 DbTest（spec asset-transfer-lease）。
 * BR-21 四前置 / 四节点链 / 交接双确认 / BR-22 当月回溯次月切换 / 跨法人双凭证+C-10 /
 * 合同条款阻断 / 分类确认 / PV 精确值 / 豁免留痕 / 月度幂等+冲回 / 重计量快照 /
 * 临期分级 / 核销差异阻断 / 购买转自有 / 闲置 12-24 月分级。
 */
@SpringBootTest
class TransferLeaseDbTest {

    @Autowired
    private EamTransferService transferService;
    @Autowired
    private EamLeaseService leaseService;
    @Autowired
    private EamDepreciationService depreciationService;
    @Autowired
    private EamTransferOrderDao trfDao;
    @Autowired
    private EamAssetCardDao cardDao;
    @Autowired
    private EamWorkOrderDao workOrderDao;
    @Autowired
    private EamLeaseContractDao contractDao;
    @Autowired
    private EamLeaseScheduleDao scheduleDao;
    @Autowired
    private EamDepreciationRunDao runDao;
    @Autowired
    private EamDepreciationDetailDao detailDao;
    @Autowired
    private FinVoucherDao voucherDao;
    @Autowired
    private FinVoucherLineDao voucherLineDao;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private SysParamService sysParamService;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        purge();
        login("junit");
        ensureParams();
        ensureLegalEntities();
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
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "erp_fin_voucher WHERE SOURCE_TYPE IN ('TRANSFER_OUT','TRANSFER_IN',"
                + "'LEASE_INTEREST','LEASE_PAYMENT','LEASE_RENT','LEASE_REMEASURE',"
                + "'LEASE_RETURN','LEASE_BUY','LEASE_DAMAGE','DEPRECIATION'))");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN ('TRANSFER_OUT','TRANSFER_IN',"
                + "'LEASE_INTEREST','LEASE_PAYMENT','LEASE_RENT','LEASE_REMEASURE',"
                + "'LEASE_RETURN','LEASE_BUY','LEASE_DAMAGE','DEPRECIATION')");
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'ZC-TL-%' "
                + "OR CARD_NO LIKE 'ZC-LSE-%'");
        jdbc.update("DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN (SELECT ID FROM "
                + "erp_sys_approval WHERE BIZ_TYPE IN ('AssetTransfer','AssetLeaseRemeasure'))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('AssetTransfer','AssetLeaseRemeasure')");
        jdbc.update("DELETE FROM erp_eam_lease_schedule WHERE LSE_NO LIKE 'LSE%'");
        jdbc.update("DELETE FROM erp_eam_lease_contract WHERE LSE_NO LIKE 'LSE%'");
        jdbc.update("DELETE FROM erp_eam_transfer_order WHERE TRF_NO LIKE 'TRF%'");
        jdbc.update("DELETE FROM erp_eam_work_order WHERE WO_NO LIKE 'WO-TL-%'");
        jdbc.update("DELETE FROM erp_eam_safety_lock WHERE LOCK_NO LIKE 'LOCK-TL-%'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'ZC-TL-%' "
                + "OR CREATE_BY = 'junit'");
        jdbc.update("DELETE FROM erp_mdm_cost_center WHERE CC_CODE LIKE 'CC-TL-%'");
    }

    private void ensureParams() {
        insertParamIfAbsent("TOLERANCE_DEFAULT", "0.01");
        insertParamIfAbsent("EAM_DEPR_CREDIT", "1602");
        insertParamIfAbsent("EAM_DEPR_ACCT_DEFAULT", "6602");
        insertParamIfAbsent("EAM_DEPR_ACCT_PROD", "5101");
        insertParamIfAbsent("EAM_INTERCO_ACCOUNT", "1221");
        insertParamIfAbsent("LEASE_DEFAULT_DISCOUNT_RATE", "4.5");
    }

    private void insertParamIfAbsent(String key, String value) {
        String cur = sysParamService.getValue(key);
        if (cur == null || cur.isBlank()) {
            jdbc.update("INSERT INTO erp_sys_param (PARAM_KEY, PARAM_VALUE, REMARK) VALUES (?,?,?)",
                    key, value, "TL 测试参数");
        }
    }

    /** 两条 USCC 不同的法人（CROSS_LE 判定数据） */
    private void ensureLegalEntities() {
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_mdm_legal_entity WHERE LE_CODE IN ('LE-TL-A','LE-TL-B')",
                Integer.class);
        if (cnt == null || cnt < 2) {
            jdbc.update("INSERT IGNORE INTO erp_mdm_legal_entity "
                    + "(ID, LE_CODE, LE_NAME, USCC, STATUS, BOOKKEEPING_CURRENCY) VALUES "
                    + "('le-tl-a','LE-TL-A','TL甲法人','91310000TLAAAA001X','1','CNY'),"
                    + "('le-tl-b','LE-TL-B','TL乙法人','91320500TLBBBB002Y','1','CNY')");
        }
    }

    // ================= 造数 =================

    private EamAssetCard mkCard(String cardNo, String cc, String dept, LocalDate effective) {
        EamAssetCard c = new EamAssetCard();
        c.setCardNo(cardNo);
        c.setAssetName("TL 测试资产-" + cardNo);
        c.setCategoryCode("MACH");
        c.setLegalEntity("LE-TL-A");
        c.setDeptName(dept);
        c.setCostCenterCode(cc);
        c.setAssetCost(new BigDecimal("1200000"));
        c.setLifeYears(10);
        c.setResidualRate(new BigDecimal("0.05"));
        c.setResidualValue(new BigDecimal("60000"));
        c.setDepreciationMethod("STRAIGHT_LINE");
        c.setEffectiveDate(effective);
        c.setStatus("IN_USE");
        c.setDeprStatus("NOT_DEPRECIATED");
        c.setAccumDepr(BigDecimal.ZERO);
        c.setLockFlag("1");
        c.setPledgedFlag("0");
        cardDao.insert(c);
        return c;
    }

    private EamTransferOrder mkAndSubmitTransfer(EamAssetCard card, String type, String toLe) {
        EamTransferOrder req = new EamTransferOrder();
        req.setCardId(card.getId());
        req.setTransferType(type);
        req.setToDept("TL调入部门");
        req.setToCc("CC-TL-TO");
        req.setToLocation("TL新仓");
        req.setToLe(toLe);
        req.setReason("TL 测试调拨");
        Map<String, Object> created = transferService.create(req);
        EamTransferOrder order = (EamTransferOrder) created.get("order");
        transferService.submit(order.getId());
        return trfDao.selectById(order.getId());
    }

    /** 按 SEQ 逐节点签署（ADMIN 免角色校验） */
    private void passAllNodes(String apprId) {
        for (int i = 0; i < 10; i++) {
            List<ApprovalTask> active = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                    .eq(ApprovalTask::getApprId, apprId)
                    .eq(ApprovalTask::getStatus, "ACTIVE"));
            if (active.isEmpty()) {
                return;
            }
            com.erp.service.approval.ApprovalEngine engine = applicationContext();
            engine.pass(active.get(0).getId(), "同意");
        }
    }

    @Autowired
    private com.erp.service.approval.ApprovalEngine approvalEngine;

    private com.erp.service.approval.ApprovalEngine applicationContext() {
        return approvalEngine;
    }

    // ================= 调拨：BR-21 前置 =================

    @Test
    void br21BlockersListedIndividually() {
        EamAssetCard card = mkCard("ZC-TL-0001", "CC-TL-A", "TL甲部门", LocalDate.of(2026, 1, 15));
        // 未关闭工单
        jdbc.update("INSERT INTO erp_eam_work_order (ID, WO_NO, WO_TYPE, CARD_ID, CARD_NO, "
                + "STATUS, CREATE_BY) VALUES ('wo-tl-1','WO-TL-0001','REPAIR',?,'ZC-TL-0001',"
                + "'EXECUTING','junit')", card.getId());
        // 安全挂锁
        jdbc.update("INSERT INTO erp_eam_safety_lock (ID, LOCK_NO, CARD_ID, CARD_NO, REASON, "
                + "LOCK_BY, LOCK_TIME, STATUS, CREATE_BY) VALUES ('lock-tl-1','LOCK-TL-0001',"
                + "?,'ZC-TL-0001','TL 测试挂锁','junit',?,'ACTIVE','junit')",
                card.getId(), LocalDateTime.now());

        EamTransferOrder req = new EamTransferOrder();
        req.setCardId(card.getId());
        req.setTransferType("DEPT");
        req.setToDept("X");
        req.setToCc("CC-TL-TO");
        req.setReason("前置校验");
        ServiceException ex = assertThrows(ServiceException.class, () -> transferService.create(req));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("WO-TL-0001"), "列示未关闭工单号");
        assertTrue(ex.getMessage().contains("LOCK-TL-0001"), "列示安全锁编号");
    }

    @Test
    void pledgedCardBlocked() {
        EamAssetCard card = mkCard("ZC-TL-0002", "CC-TL-A", "TL甲部门", LocalDate.of(2026, 1, 15));
        EamAssetCard upd = new EamAssetCard();
        upd.setId(card.getId());
        upd.setVerNo(card.getVerNo());
        upd.setPledgedFlag("1");
        cardDao.updateById(upd);

        EamTransferOrder req = new EamTransferOrder();
        req.setCardId(card.getId());
        req.setTransferType("DEPT");
        req.setToDept("X");
        req.setToCc("CC-TL-TO");
        req.setReason("质押阻断");
        ServiceException ex = assertThrows(ServiceException.class, () -> transferService.create(req));
        assertTrue(ex.getMessage().contains("质押"), "质押卡阻断");
    }

    @Test
    void duplicateInFlightBlocked() {
        EamAssetCard card = mkCard("ZC-TL-0003", "CC-TL-A", "TL甲部门", LocalDate.of(2026, 1, 15));
        mkAndSubmitTransfer(card, "DEPT", null);
        EamTransferOrder req = new EamTransferOrder();
        req.setCardId(card.getId());
        req.setTransferType("DEPT");
        req.setToDept("Y");
        req.setToCc("CC-TL-TO");
        req.setReason("重复发起");
        ServiceException ex = assertThrows(ServiceException.class, () -> transferService.create(req));
        assertTrue(ex.getMessage().contains("进行中的调拨单"), "重复发起阻断");
    }

    // ================= 调拨：审批链与交接 =================

    @Test
    void crossLeFourNodeChainAndReject() {
        EamAssetCard card = mkCard("ZC-TL-0004", "CC-TL-A", "TL甲部门", LocalDate.of(2026, 1, 15));
        EamTransferOrder order = mkAndSubmitTransfer(card, "CROSS_LE", "LE-TL-B");
        assertEquals("PENDING_APPROVE", order.getStatus());
        // 四节点：调出主管 → 调入主管 → 资产管理员 → 财务经理
        Long nodes = taskDao.selectCount(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, order.getApprId()));
        assertEquals(4, nodes, "CROSS_LE 四节点链");

        // 第二节点驳回
        List<ApprovalTask> active = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, order.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        approvalEngine.pass(active.get(0).getId(), "同意");
        List<ApprovalTask> second = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, order.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        approvalEngine.reject(second.get(0).getId(), "调入方条件不具备");
        EamTransferOrder after = trfDao.selectById(order.getId());
        assertEquals("REJECTED", after.getStatus(), "驳回后回可改态");
        assertTrue(after.getRejectReason().contains("调入方"), "驳回原因留痕");
    }

    @Test
    void handoverDoubleConfirmThenComplete() {
        EamAssetCard card = mkCard("ZC-TL-0005", "CC-TL-A", "TL甲部门", LocalDate.of(2026, 1, 15));
        EamTransferOrder order = mkAndSubmitTransfer(card, "DEPT", null);
        passAllNodes(order.getApprId());
        assertEquals("APPROVED", trfDao.selectById(order.getId()).getStatus(), "审批通过待交接");

        // 单侧确认不可完成
        transferService.handoverConfirm(order.getId(), "OUT");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> transferService.completeFromApproval(order.getId()));
        assertTrue(ex.getMessage().contains("调入方"), "待调入方确认阻断完成");

        // 双确认 → HANDOVERED → 完成即改卡片
        transferService.handoverConfirm(order.getId(), "IN");
        assertEquals("HANDOVERED", trfDao.selectById(order.getId()).getStatus());
        Map<String, Object> out = transferService.completeFromApproval(order.getId());
        assertEquals("COMPLETED", ((EamTransferOrder) out.get("order")).getStatus());
        EamAssetCard after = cardDao.selectById(card.getId());
        assertEquals("TL调入部门", after.getDeptName(), "完成即改部门");
        assertEquals("CC-TL-TO", after.getCostCenterCode(), "完成即改成本中心");
        assertEquals("TL新仓", after.getStorageLocation(), "完成即改地点");
    }

    // ================= BR-22：当月回溯 / 次月切换 =================

    @Test
    void br22AttributionRetrospectAndSwitch() {
        jdbc.update("INSERT INTO erp_mdm_cost_center (ID, CC_CODE, CC_NAME, COST_TYPE, STATUS, "
                + "TREE_LEVEL, PARENT_ID, LEGAL_ENTITY_ID) VALUES "
                + "('cc-tl-a','CC-TL-A','TL甲中心','PROD','1',1,'','le-tl-a'),"
                + "('cc-tl-to','CC-TL-TO','TL调入中心','ADMIN','1',1,'','le-tl-a')");
        EamAssetCard card = mkCard("ZC-TL-0006", "CC-TL-A", "TL甲部门", LocalDate.of(2026, 1, 15));
        EamTransferOrder order = mkAndSubmitTransfer(card, "DEPT", null);
        passAllNodes(order.getApprId());
        transferService.handoverConfirm(order.getId(), "OUT");
        transferService.handoverConfirm(order.getId(), "IN");
        transferService.completeFromApproval(order.getId());

        String cur = LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        String next = java.time.YearMonth.parse(cur.substring(0, 4) + "-" + cur.substring(4))
                .plusMonths(1).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));

        // 当月计提：明细归属 = 调出方 CC-TL-A（回溯），卡片已是调入方
        depreciationService.runDepreciation(cur);
        EamDepreciationRun run = runDao.selectByPeriod(cur);
        List<EamDepreciationDetail> d1 = detailDao.selectList(new LambdaQueryWrapper<EamDepreciationDetail>()
                .eq(EamDepreciationDetail::getRunId, run.getId())
                .eq(EamDepreciationDetail::getCardNo, "ZC-TL-0006"));
        assertEquals(1, d1.size());
        assertEquals("CC-TL-A", d1.get(0).getCostCenterCode(), "当月折旧归属调出方（BR-22 回溯）");
        assertEquals("5101", d1.get(0).getDebitAccount(), "调出 PROD → 5101");
        assertEquals("CC-TL-TO", cardDao.selectById(card.getId()).getCostCenterCode(), "卡片已是调入方");

        // 次月计提：归属自然切换调入方
        depreciationService.runDepreciation(next);
        EamDepreciationRun run2 = runDao.selectByPeriod(next);
        List<EamDepreciationDetail> d2 = detailDao.selectList(new LambdaQueryWrapper<EamDepreciationDetail>()
                .eq(EamDepreciationDetail::getRunId, run2.getId())
                .eq(EamDepreciationDetail::getCardNo, "ZC-TL-0006"));
        assertEquals("CC-TL-TO", d2.get(0).getCostCenterCode(), "次月归属调入方");
    }

    // ================= 跨法人 =================

    @Test
    void crossLeVouchersIntercoAndC10() {
        EamAssetCard card = mkCard("ZC-TL-0007", "CC-TL-A", "TL甲部门", LocalDate.of(2026, 1, 15));
        EamTransferOrder order = mkAndSubmitTransfer(card, "CROSS_LE", "LE-TL-B");
        passAllNodes(order.getApprId());
        transferService.handoverConfirm(order.getId(), "OUT");
        transferService.handoverConfirm(order.getId(), "IN");
        Map<String, Object> out = transferService.completeFromApproval(order.getId());

        assertNotNull(out.get("intercoNo"), "内部交易单号生成");
        EamTransferOrder after = trfDao.selectById(order.getId());
        assertEquals("COMPLETED", after.getStatus());
        assertNotNull(after.getIntercoNo());
        assertNotNull(after.getOutVoucherId());
        assertNotNull(after.getInVoucherId());
        assertEquals("LE-TL-B", cardDao.selectById(card.getId()).getLegalEntity(), "卡片法人即改");

        // 调出处置凭证：借 1606 NBV + 借 1602 累计折旧 / 贷 1601 原值
        List<FinVoucherLine> outLines = voucherLineDao.selectList(new LambdaQueryWrapper<FinVoucherLine>()
                .eq(FinVoucherLine::getVoucherId, after.getOutVoucherId()));
        BigDecimal drSum = outLines.stream().filter(l -> "DR".equals(l.getDirection()))
                .map(FinVoucherLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal crSum = outLines.stream().filter(l -> "CR".equals(l.getDirection()))
                .map(FinVoucherLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, drSum.compareTo(crSum), "调出凭证借贷平");
        assertTrue(outLines.stream().anyMatch(l -> "1606".equals(l.getAccountCode())
                && "DR".equals(l.getDirection())), "借 1606 清理 NBV");

        // 调入新卡原值 = NBV = 原值 − 累计折旧
        EamAssetCard newCard = cardDao.selectById(after.getNewCardId());
        assertEquals(0, new BigDecimal("1200000").compareTo(newCard.getAssetCost()), "零累计时 NBV=原值");
        assertEquals("LE-TL-B", newCard.getLegalEntity());
        // 原卡置 DISPOSED
        assertEquals("DISPOSED", cardDao.selectById(card.getId()).getStatus());

        // C-10 卡控：齐备时通过
        Map<String, Object> check = transferService.assertIntercoComplete();
        assertEquals(Boolean.TRUE, check.get("passed"));

        // 单据被删 → 卡控阻断列示
        jdbc.update("UPDATE erp_eam_transfer_order SET INTERCO_NO = NULL WHERE ID = ?", order.getId());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> transferService.assertIntercoComplete());
        assertTrue(ex.getMessage().contains(after.getTrfNo()), "列示缺失调拨单号");
    }

    // ================= 租赁：条款/分类/PV/豁免 =================

    @Test
    void leaseTermsIncompleteBlocked() {
        EamLeaseContract c = new EamLeaseContract();
        c.setLessor("TL出租方");
        // 缺资产描述/期限/租金/付款条款
        ServiceException ex = assertThrows(ServiceException.class, () -> leaseService.create(c));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("租金金额") && ex.getMessage().contains("租赁期限"),
                "逐项列示缺失字段");
    }

    @Test
    void leaseClassifyAndConfirmPvPrecise() {
        EamLeaseContract c = mkContract("2026-01-01", "2028-12-31",
                new BigDecimal("45000"), 3, new BigDecimal("0.045"));
        // 辅助判断：>12 月 → ROU 候选
        Map<String, Object> sug = leaseService.suggestClassify(c.getId());
        assertEquals("ROU", sug.get("suggest"));

        leaseService.classify(c.getId(), "ROU", "36 个月使用权资产适用", false);
        Map<String, Object> out = leaseService.confirm(c.getId());
        // PV 锚（口径 A：公式精确值；486,000 为规格叙事偏差）
        assertEquals(0, new BigDecimal("502367.58").compareTo((BigDecimal) out.get("pv")),
                "PV = 502367.58（月折现+付款期末）");
        // ROU 卡片：LEASE_FLAG=1，原值 = PV（无初始费用/预付/激励）
        EamAssetCard rou = (EamAssetCard) out.get("rouCard");
        assertEquals("1", rou.getLeaseFlag());
        assertEquals(c.getId(), rou.getLeaseContractId());
        assertEquals(0, new BigDecimal("502367.58").compareTo(rou.getAssetCost()));
        // 全周期计划：T0 + 36 月 = 37 行
        assertEquals(37, out.get("scheduleRows"));
        EamLeaseContract after = contractDao.selectById(c.getId());
        assertEquals("CONFIRMED", after.getStatus());
        assertEquals("INCREMENTAL", after.getRateBasis(), "利率取值依据留痕");
    }

    @Test
    void leaseExemptConfirmNoCardNoSchedule() {
        EamLeaseContract c = mkContract("2026-01-01", "2026-10-31",
                new BigDecimal("8000"), 1, null);   // 10 个月 → EXEMPT 候选
        Map<String, Object> sug = leaseService.suggestClassify(c.getId());
        assertEquals("EXEMPT", sug.get("suggest"));
        leaseService.classify(c.getId(), "EXEMPT", "短期租赁豁免", false);
        // 未填豁免理由 → 阻断
        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.confirm(c.getId()));
        assertTrue(ex.getMessage().contains("豁免"), "豁免理由必填");

        EamLeaseContract upd = contractDao.selectById(c.getId());
        upd.setExemptReason("10 个月短期租赁，选择豁免（BR-25）");
        leaseService.update(upd);
        leaseService.classify(c.getId(), "EXEMPT", "短期租赁豁免", false);
        Map<String, Object> out = leaseService.confirm(c.getId());
        assertEquals(Boolean.TRUE, out.get("exempt"), "豁免分支");
        assertNull(contractDao.selectById(c.getId()).getRouCardId(), "不建卡");
        assertEquals(0, scheduleDao.selectByContract(c.getId()).size(), "不出计划");
    }

    @Test
    void leaseMonthlyIdempotentAndReverse() {
        EamLeaseContract c = mkContract("2026-01-01", "2028-12-31",
                new BigDecimal("45000"), 3, new BigDecimal("0.045"));
        leaseService.classify(c.getId(), "ROU", "使用权资产适用", false);
        leaseService.confirm(c.getId());

        String period = "202602";   // 计划月（T0=202601，次月起逐月）
        Map<String, Object> out = leaseService.monthlyProcess(period);
        assertEquals(1, out.get("processedContracts"));
        // 利息凭证 + 当月无付款（季付第 2 月）
        assertTrue((Integer) out.get("interestVouchers") >= 1, "利息凭证生成");
        EamLeaseSchedule row = scheduleDao.selectList(new LambdaQueryWrapper<EamLeaseSchedule>()
                .eq(EamLeaseSchedule::getContractId, c.getId())
                .eq(EamLeaseSchedule::getPeriodTag, period)).get(0);
        assertEquals("1", row.getProcessedFlag());
        assertNotNull(row.getVoucherIntId());
        // 幂等：重复触发 422
        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.monthlyProcess(period));
        assertTrue(ex.getMessage().contains("幂等"), "月标签幂等阻断");

        // 冲回 → 复位 → 重提
        Map<String, Object> rev = leaseService.reverse(period);
        assertTrue((Integer) rev.get("reversedVouchers") >= 1);
        assertEquals("0", scheduleDao.selectById(row.getId()).getProcessedFlag(), "计划行复位");
        Map<String, Object> again = leaseService.monthlyProcess(period);
        assertEquals(1, again.get("processedContracts"), "冲回后可重提");
    }

    @Test
    void leaseRemeasureSnapshotsAndAdjusts() {
        EamLeaseContract c = mkContract("2026-01-01", "2028-12-31",
                new BigDecimal("45000"), 3, new BigDecimal("0.045"));
        leaseService.classify(c.getId(), "ROU", "使用权资产适用", false);
        Map<String, Object> confirmed = leaseService.confirm(c.getId());
        EamAssetCard rou = (EamAssetCard) confirmed.get("rouCard");
        BigDecimal costBefore = rou.getAssetCost();
        int rowsBefore = scheduleDao.selectByContract(c.getId()).size();   // 37

        // 提交 + 直接执行重计量（+12 月，绕审批回调由测试直调）
        leaseService.remeasure(c.getId(), new BigDecimal("0.045"), 12, null, "行使续租选择权");
        Map<String, Object> out = leaseService.executeRemeasure(c.getId());

        // 旧行快照归档：SNAPSHOT_SEQ>0 行存在
        List<EamLeaseSchedule> snaps = scheduleDao.selectSnapshots(c.getId());
        assertFalse(snaps.isEmpty(), "剩余行快照归档");
        // 新当前行 = T0 + 2 已处理?（本例 0 已处理 → T0 + 48 月 = 49 行）… 实际 rowsBefore + 12
        assertEquals(rowsBefore + 12, scheduleDao.selectByContract(c.getId()).size(),
                "剩余行重算 +12 月");
        // ROU 原值差额调整
        BigDecimal delta = (BigDecimal) out.get("delta");
        assertNotNull(delta);
        EamAssetCard rouAfter = cardDao.selectById(rou.getId());
        assertEquals(0, costBefore.add(delta).compareTo(rouAfter.getAssetCost()), "ROU 原值差额调整");
        assertEquals(48, contractDao.selectById(c.getId()).getTermsMonths(), "租期延长 12 月");
    }

    @Test
    void leaseExpiryLevels() {
        // 剩 ~5 月 → M6
        EamLeaseContract c1 = mkContract("2026-01-01",
                LocalDate.now().plusMonths(5).withDayOfMonth(1).toString(),
                new BigDecimal("45000"), 3, new BigDecimal("0.045"));
        leaseService.classify(c1.getId(), "ROU", "使用权资产适用", false);
        leaseService.confirm(c1.getId());
        // 剩 <1 月 → M1
        EamLeaseContract c2 = mkContract("2026-01-01",
                LocalDate.now().plusDays(10).toString(),
                new BigDecimal("45000"), 3, new BigDecimal("0.045"));
        leaseService.classify(c2.getId(), "ROU", "使用权资产适用", false);
        leaseService.confirm(c2.getId());

        List<Map<String, Object>> list = leaseService.expiryList();
        Map<String, Object> m6 = list.stream().filter(r -> c1.getId().equals(r.get("contractId")))
                .findFirst().orElseThrow();
        assertEquals("M6", m6.get("level"));
        Map<String, Object> m1 = list.stream().filter(r -> c2.getId().equals(r.get("contractId")))
                .findFirst().orElseThrow();
        assertEquals("M1", m1.get("level"), "剩 1 月升级分级");
        // 默示续租暂估标记
        assertEquals("1", contractDao.selectById(c2.getId()).getEstimatedFlag(), "暂估标记");
    }

    @Test
    void leaseTerminateWriteOffDiffBlocked() {
        EamLeaseContract c = mkContract("2026-01-01", "2028-12-31",
                new BigDecimal("45000"), 3, new BigDecimal("0.045"));
        leaseService.classify(c.getId(), "ROU", "使用权资产适用", false);
        leaseService.confirm(c.getId());
        // 处理全部计划行使终态，再人为制造期末余额差异
        for (EamLeaseSchedule r : scheduleDao.selectByContract(c.getId())) {
            if (r.getSeq() > 0) {
                jdbc.update("UPDATE erp_eam_lease_schedule SET PROCESSED_FLAG='1' WHERE ID=?",
                        r.getId());
            }
        }
        List<EamLeaseSchedule> rows = scheduleDao.selectByContract(c.getId());
        jdbc.update("UPDATE erp_eam_lease_schedule SET END_BAL = 800 WHERE ID = ?",
                rows.get(rows.size() - 1).getId());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> leaseService.terminate(c.getId(), "RETURN", null, "到期退租"));
        assertTrue(ex.getMessage().contains("核销差异"), "差异超容差 L1 阻断");
    }

    @Test
    void leaseTerminateBuyTransfersOwnership() {
        EamLeaseContract c = mkContract("2026-01-01", "2028-12-31",
                new BigDecimal("45000"), 3, new BigDecimal("0.045"));
        c.setBuyOption("1");
        c.setBuyPrice(new BigDecimal("50000"));
        contractDao.updateById(c);
        leaseService.classify(c.getId(), "ROU", "使用权资产适用", false);
        Map<String, Object> confirmed = leaseService.confirm(c.getId());
        EamAssetCard rou = (EamAssetCard) confirmed.get("rouCard");

        Map<String, Object> out = leaseService.terminate(c.getId(), "BUY", null, "到期购买");
        EamAssetCard newCard = (EamAssetCard) out.get("newCard");
        assertEquals(0, new BigDecimal("50000").compareTo(newCard.getAssetCost()), "买价建卡");
        assertEquals("IN_USE", newCard.getStatus());
        assertEquals("TERMINATED", contractDao.selectById(c.getId()).getStatus());
        assertEquals("DISPOSED", cardDao.selectById(rou.getId()).getStatus(), "ROU 卡关闭");
        assertNotNull(out.get("buyVoucherId"), "购买凭证");
    }

    @Test
    void idlePoolTwelveAndTwentyFourMonths() {
        // 13 个月闲置 → IDLE（入池可调拨）
        EamAssetCard c1 = mkCard("ZC-TL-0010", "CC-TL-A", "TL甲部门", LocalDate.now().minusMonths(20));
        jdbc.update("UPDATE erp_eam_asset_card SET LAST_USED_AT = ? WHERE ID = ?",
                LocalDate.now().minusMonths(13), c1.getId());
        // 25 个月闲置 → DISPOSAL_EVAL
        EamAssetCard c2 = mkCard("ZC-TL-0011", "CC-TL-A", "TL甲部门", LocalDate.now().minusMonths(30));
        jdbc.update("UPDATE erp_eam_asset_card SET LAST_USED_AT = ? WHERE ID = ?",
                LocalDate.now().minusMonths(25), c2.getId());
        // 3 个月 → 不入池
        EamAssetCard c3 = mkCard("ZC-TL-0012", "CC-TL-A", "TL甲部门", LocalDate.now().minusMonths(8));
        jdbc.update("UPDATE erp_eam_asset_card SET LAST_USED_AT = ? WHERE ID = ?",
                LocalDate.now().minusMonths(3), c3.getId());

        List<Map<String, Object>> pool = transferService.idlePool();
        Map<String, Object> r1 = pool.stream().filter(r -> "ZC-TL-0010".equals(r.get("cardNo")))
                .findFirst().orElseThrow();
        assertEquals("IDLE", r1.get("idleLevel"));
        assertEquals(Boolean.TRUE, r1.get("transferable"), "入池即可发起调拨（S-4.13-13）");
        Map<String, Object> r2 = pool.stream().filter(r -> "ZC-TL-0011".equals(r.get("cardNo")))
                .findFirst().orElseThrow();
        assertEquals("DISPOSAL_EVAL", r2.get("idleLevel"), "24 月处置评估分级");
        assertTrue(pool.stream().noneMatch(r -> "ZC-TL-0012".equals(r.get("cardNo"))), "3 月不入池");

        // 入池资产可直接发起调拨（BR-21 通过）
        EamTransferOrder req = new EamTransferOrder();
        req.setCardId(c1.getId());
        req.setTransferType("DEPT");
        req.setToDept("TL调入部门");
        req.setToCc("CC-TL-TO");
        req.setReason("共享池发起");
        assertDoesNotThrow(() -> transferService.create(req));
    }

    // ================= 造合同 =================

    @SuppressWarnings("unchecked")
    private EamLeaseContract mkContract(String start, String end, BigDecimal payment,
                                        int payCycle, BigDecimal annualRate) {
        EamLeaseContract c = new EamLeaseContract();
        c.setLessor("TL出租方");
        c.setAssetDesc("TL 叉车租赁");
        c.setStartDate(LocalDate.parse(start));
        c.setEndDate(LocalDate.parse(end));
        c.setPaymentAmount(payment);
        c.setPayCycleMonths(payCycle);
        c.setDiscountRate(annualRate);
        c.setRateBasis(annualRate == null ? null : "INCREMENTAL");
        c.setBuyOption("0");
        c.setInitDirectCost(BigDecimal.ZERO);
        c.setPrepaidRent(BigDecimal.ZERO);
        c.setLeaseIncentive(BigDecimal.ZERO);
        Map<String, Object> out = leaseService.create(c);
        return (EamLeaseContract) out.get("contract");
    }
}
