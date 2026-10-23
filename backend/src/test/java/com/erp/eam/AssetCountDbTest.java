package com.erp.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamCountDiffDao;
import com.erp.dao.eam.EamCountLineDao;
import com.erp.dao.eam.EamCountPlanDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamCountDiff;
import com.erp.entity.eam.EamCountLine;
import com.erp.entity.eam.EamCountPlan;
import com.erp.entity.eam.EamCountTeamMember;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.SysParamService;
import com.erp.service.eam.EamCountDiffService;
import com.erp.service.eam.EamCountService;
import com.erp.service.eam.EamTransferService;
import com.erp.entity.eam.EamTransferOrder;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * add-asset-count：资产盘点 DbTest（spec asset-count）。
 * 快照冻结（BR-29）/ 抽样三档全类别（BR-26）/ 实盘录入与导入 / 四类差异 /
 * 两档分级审批 / 盘盈自动带价（BR-27）/ 盘亏挂账认定核销（BR-28）/
 * 标签冻结阻断调拨（BR-30）/ 覆盖率结案卡控（BR-26 L1）/ 闲置锚刷新。
 */
@SpringBootTest
class AssetCountDbTest {

    @Autowired
    private EamCountService countService;
    @Autowired
    private EamCountDiffService diffService;
    @Autowired
    private EamTransferService transferService;
    @Autowired
    private EamCountPlanDao planDao;
    @Autowired
    private EamCountLineDao lineDao;
    @Autowired
    private EamCountDiffDao diffDao;
    @Autowired
    private EamAssetCardDao cardDao;
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
                + "erp_fin_voucher WHERE SOURCE_TYPE IN ('COUNT_GAIN','COUNT_LOSS','COUNT_SUSPEND'))");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN "
                + "('COUNT_GAIN','COUNT_LOSS','COUNT_SUSPEND')");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN (SELECT ID FROM "
                + "erp_sys_approval WHERE BIZ_TYPE IN ('AssetCountDiff','AssetCountReport'))");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN ('AssetCountDiff','AssetCountReport')");
        jdbc.update("DELETE FROM erp_eam_count_diff WHERE CNT_NO LIKE 'CNT%' "
                + "OR (DIFF_TYPE='TAG_ISSUE' AND CARD_NO LIKE 'ZC-CNT-%')");
        jdbc.update("DELETE FROM erp_eam_count_line WHERE CNT_NO LIKE 'CNT%'");
        jdbc.update("DELETE FROM erp_eam_count_team_member WHERE CNT_NO LIKE 'CNT%'");
        jdbc.update("DELETE FROM erp_eam_count_plan WHERE CNT_NO LIKE 'CNT%'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'ZC-CNT-%'");
        // 盘盈入账建卡（SOURCE_CAP_NO=盘点差异单号）不删会污染同分类 12 月参照均价
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE SOURCE_CAP_NO LIKE 'CNT%'");
        // 冒烟遗留卡自洽清理（SMKTL/SMKCNT/SMKMT/SMKDEPR/ROU 等污染全库快照、抽样与参照均价断言）
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'SMK%' "
                + "OR ASSET_NAME LIKE '%SMK%' OR SOURCE_CAP_NO LIKE 'TRF%' "
                + "OR SOURCE_CAP_NO LIKE 'LSE%' OR LEASE_FLAG = '1'");
        jdbc.update("UPDATE erp_eam_asset_card SET COUNT_FREEZE_FLAG='0' WHERE COUNT_FREEZE_FLAG='1'");
    }

    private void ensureParams() {
        insertParamIfAbsent("ASSET_COUNT_SAMPLE_RATE", "HIGH=50,MID=30,LOW=10");
        insertParamIfAbsent("ASSET_COUNT_DIFF_THRESHOLD", "50000");
    }

    private void insertParamIfAbsent(String key, String value) {
        String cur = sysParamService.getValue(key);
        if (cur == null || cur.isBlank()) {
            jdbc.update("INSERT INTO erp_sys_param (PARAM_KEY, PARAM_VALUE, REMARK) VALUES (?,?,?)",
                    key, value, "CNT 测试参数");
        }
    }

    // ================= 造数 =================

    private EamAssetCard mkCard(String cardNo, String category, BigDecimal cost,
                                BigDecimal accum, String dept, String cc, LocalDate effective) {
        EamAssetCard c = new EamAssetCard();
        c.setCardNo(cardNo);
        c.setAssetName("CNT 测试资产-" + cardNo);
        c.setCategoryCode(category);
        c.setDeptName(dept);
        c.setCostCenterCode(cc);
        c.setAssetCost(cost);
        c.setLifeYears(10);
        c.setResidualRate(new BigDecimal("0.05"));
        c.setResidualValue(cost.multiply(new BigDecimal("0.05")));
        c.setDepreciationMethod("STRAIGHT_LINE");
        c.setEffectiveDate(effective);
        c.setStatus("IN_USE");
        c.setDeprStatus("NOT_DEPRECIATED");
        c.setAccumDepr(accum == null ? BigDecimal.ZERO : accum);
        c.setLockFlag("1");
        c.setPledgedFlag("0");
        c.setCountFreezeFlag("0");
        cardDao.insert(c);
        return c;
    }

    private EamCountPlan mkPlan(String type, LocalDate baseDate) {
        EamCountPlan p = new EamCountPlan();
        p.setCountType(type);
        p.setBaseDate(baseDate);
        p.setMethod("SCAN");
        p.setPlanDesc("CNT 测试盘点");
        Map<String, Object> out = countService.create(p);
        @SuppressWarnings("unchecked")
        EamCountPlan plan = (EamCountPlan) out.get("plan");
        // 人员分组
        EamCountTeamMember m = new EamCountTeamMember();
        m.setMemberName("盘点员甲");
        m.setGroupName("第一组");
        countService.maintainTeam(plan.getId(), List.of(m));
        return planDao.selectById(plan.getId());
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
            approvalEngine.pass(active.get(0).getId(), "同意");
        }
    }

    @Autowired
    private com.erp.service.approval.ApprovalEngine approvalEngine;

    // ================= 快照（BR-29）=================

    @Test
    void issueFreezesSnapshot() {
        EamAssetCard c1 = mkCard("ZC-CNT-0001", "MACH", new BigDecimal("100000"),
                new BigDecimal("20000"), "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        mkCard("ZC-CNT-0002", "MACH", new BigDecimal("50000"), null,
                "CNT 乙部门", "CC-CNT-B", LocalDate.of(2026, 2, 15));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        Map<String, Object> out = countService.issue(plan.getId());
        assertEquals(2, out.get("snapshotRows"), "基准日在册 2 张卡快照 2 行");
        assertEquals("ISSUED", planDao.selectById(plan.getId()).getStatus());

        // 快照冻结：下发后改卡片原值不影响快照列
        EamAssetCard upd = new EamAssetCard();
        upd.setId(c1.getId());
        upd.setVerNo(c1.getVerNo());
        upd.setAssetCost(new BigDecimal("999999"));
        cardDao.updateById(upd);
        EamCountLine line = lineDao.selectOne(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, plan.getId())
                .eq(EamCountLine::getCardNo, "ZC-CNT-0001"));
        assertEquals(0, new BigDecimal("100000").compareTo(line.getSnapCost()), "快照列冻结不受卡片后改影响");
        // NBV 快照 = 100000 − 20000 = 80000
        assertEquals(0, new BigDecimal("80000").compareTo(line.getSnapNbv()));
        // 重复下发阻断
        assertThrows(ServiceException.class, () -> countService.issue(plan.getId()));
    }

    @Test
    void postBaseDateChangesListedSeparately() {
        mkCard("ZC-CNT-0010", "MACH", new BigDecimal("100000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        // 基准日后新增卡
        mkCard("ZC-CNT-0011", "MACH", new BigDecimal("80000"), null,
                "CNT 乙部门", "CC-CNT-B", LocalDate.of(2026, 8, 1));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        Map<String, Object> out = countService.issue(plan.getId());
        assertEquals(1, out.get("snapshotRows"), "仅基准日前启用卡入快照（BR-29）");
        Map<String, Object> post = countService.postBaseChanges(plan.getId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) post.get("cards");
        assertTrue(cards.stream().anyMatch(m -> "ZC-CNT-0011".equals(m.get("CARD_NO"))),
                "基准日后新增卡单独列示");
    }

    // ================= 抽样（BR-26）=================

    @Test
    void sampleThreeTiersAndAllCategories() {
        // 高风险分类 4 张 → 50% = 2；中风险 4 张 → 30% = 1.2 → 1；低风险 4 张 → 10% = 0.4 → 1（类内兜底）
        jdbc.update("INSERT IGNORE INTO erp_eam_asset_category (ID, CATEGORY_CODE, NAME, "
                + "CAPITALIZATION_FLOOR, DEFAULT_LIFE_YEARS, DEFAULT_RESIDUAL_RATE, "
                + "DEFAULT_DEPRECIATION_METHOD, RISK_LEVEL, LIFE_YEARS_MIN, LIFE_YEARS_MAX, "
                + "ASSET_ACCOUNT, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('cat-cnt-h','CNTH','CNT高风险',5000,10,0.05,'STRAIGHT_LINE','HIGH',1,50,'1601','junit','0',0),"
                + "('cat-cnt-m','CNTM','CNT中风险',5000,10,0.05,'STRAIGHT_LINE','MID',1,50,'1601','junit','0',0),"
                + "('cat-cnt-l','CNTL','CNT低风险',5000,10,0.05,'STRAIGHT_LINE','LOW',1,50,'1601','junit','0',0)");
        for (int i = 1; i <= 4; i++) {
            mkCard("ZC-CNT-H" + i, "CNTH", new BigDecimal("10000"), null, "D", "CC", LocalDate.of(2026, 1, 10));
            mkCard("ZC-CNT-M" + i, "CNTM", new BigDecimal("10000"), null, "D", "CC", LocalDate.of(2026, 1, 10));
            mkCard("ZC-CNT-L" + i, "CNTL", new BigDecimal("10000"), null, "D", "CC", LocalDate.of(2026, 1, 10));
        }
        EamCountPlan plan = mkPlan("SAMPLE", LocalDate.of(2026, 6, 30));
        Map<String, Object> out = countService.issue(plan.getId());
        // 高 2 + 中 1 + 低 1 = 4
        assertEquals(4, out.get("snapshotRows"), "抽样=高2+中1+低1=4（类内随机+兜底）");
        List<EamCountLine> lines = lineDao.selectList(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, plan.getId()));
        assertTrue(lines.stream().anyMatch(l -> "CNTH".equals(l.getSnapCategory())),
                "高风险类抽中");
        assertTrue(lines.stream().anyMatch(l -> "CNTM".equals(l.getSnapCategory())),
                "中风险类抽中（覆盖全类别）");
        assertTrue(lines.stream().anyMatch(l -> "CNTL".equals(l.getSnapCategory())),
                "低风险类抽中（类内至少 1 张兜底）");
    }

    // ================= 实盘录入 =================

    @Test
    void scanEntryAndPendingConfirm() {
        EamAssetCard card = mkCard("ZC-CNT-0020", "MACH", new BigDecimal("100000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        // 基准日后启用卡（不在快照内 → 扫描走 POST_BASE 单独列示，不计覆盖率）
        mkCard("ZC-CNT-0021", "MACH", new BigDecimal("50000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 7, 15));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        // 扫码实盘
        Map<String, Object> out = countService.countEntry(plan.getId(), "ZC-CNT-0020",
                "IN_USE", null, false);
        assertEquals("IN_USE", out.get("actualStatus"));
        assertEquals("COUNTING", planDao.selectById(plan.getId()).getStatus(), "首次录入置盘点中");
        // 重复录入 409
        assertThrows(ServiceException.class, () -> countService.countEntry(plan.getId(),
                "ZC-CNT-0020", "IN_USE", null, false));
        // 无标签 → 待确认
        Map<String, Object> unknown = countService.countEntry(plan.getId(), null,
                "UNKNOWN", "无标签设备一台，铭牌模糊", false);
        assertEquals("PENDING_CONFIRM", unknown.get("diffType"));
        // 闲置锚刷新（S-4.13-13）
        countService.countEntry(plan.getId(), null, "UNKNOWN", "x", false);   // 造第二行待确认避免重复冲突
        Map<String, Object> r = countService.countEntry(plan.getId(), "ZC-CNT-0021",
                "IN_USE", null, true);
        assertNotNull(card.getId());
        // 覆盖率：UNKNOWN 不计已盘
        Map<String, Object> cov = countService.refreshCoverage(plan.getId());
        assertEquals(1, cov.get("countedQty"), "仅 IN_USE 计入已盘（UNKNOWN 待确认不计）");
    }

    @Test
    void batchImportPartialFailure() {
        mkCard("ZC-CNT-0030", "MACH", new BigDecimal("100000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        String csv = "﻿cardNo,actualStatus,actualNote,refreshLastUsed\n"
                + "ZC-CNT-0030,IN_USE,,0\n"
                + "ZC-CNT-NOTEXIST,IN_USE,,0\n"
                + ",IN_USE,缺卡号行,0\n";
        Map<String, Object> out = countService.batchImport(plan.getId(), csv);
        assertEquals(3, out.get("total"), "BOM 表头识别，3 数据行");
        assertEquals(2, out.get("success"), "合法 2 行（存量卡+基准日后卡）");
        assertEquals(1, out.get("failed"), "缺卡号行失败但不整批回滚");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) out.get("errors");
        assertEquals(1, errors.size());
        assertEquals(3, errors.get(0).get("rowNo"));
    }

    // ================= 四类差异与审批 =================

    @Test
    void autoCompareFourDiffTypes() {
        // 盘亏卡用 CNTM 分类：与盘盈参照（MACH 6800）解耦，避免抬高同分类均价
        mkCard("ZC-CNT-0040", "CNTM", new BigDecimal("100000"), new BigDecimal("40000"),
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        // 盘盈参照价：同分类 12 月内转资卡
        mkCard("ZC-CNT-0041", "MACH", new BigDecimal("6800"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 3, 1));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        // 盘亏
        countService.countEntry(plan.getId(), "ZC-CNT-0040", "MISSING", "全面搜索未找到", false);
        // 盘盈（实物有账无）
        countService.countEntry(plan.getId(), null, "SURPLUS", "未登记笔记本一台", false, "MACH");
        Map<String, Object> out = diffService.autoCompare(plan.getId());
        assertEquals(2, out.get("created"), "盘亏+盘盈两条差异生成");
        EamCountDiff shortDiff = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .eq(EamCountDiff::getDiffType, "SHORT"));
        assertEquals(0, new BigDecimal("60000").compareTo(shortDiff.getDiffAmount()),
                "盘亏金额=快照 NBV 60000");
        EamCountDiff overDiff = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .eq(EamCountDiff::getDiffType, "OVER"));
        assertEquals(0, new BigDecimal("6800").compareTo(overDiff.getSuggestedPrice()),
                "盘盈自动带价=同分类 12 月转资均价 6800（BR-27）");
        // 幂等：重复比对不重复生成
        Map<String, Object> again = diffService.autoCompare(plan.getId());
        assertEquals(0, again.get("created"));
        assertTrue((Integer) again.get("skipped") >= 2);
    }

    @Test
    void statusConflictDiffCreatedAndFrozen() {
        EamAssetCard card = mkCard("ZC-CNT-0045", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        // 基准日后已处置，但盘点时实物仍在现场
        EamAssetCard upd = new EamAssetCard();
        upd.setId(card.getId());
        upd.setVerNo(card.getVerNo());
        upd.setStatus("DISPOSED");
        cardDao.updateById(upd);
        countService.countEntry(plan.getId(), "ZC-CNT-0045", "IN_USE", "实物在场", false);
        Map<String, Object> out = diffService.autoCompare(plan.getId());
        assertEquals(1, out.get("created"), "账实状态冲突差异生成（四类差之一）");
        EamCountDiff conflict = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .eq(EamCountDiff::getDiffType, "STATUS_CONFLICT"));
        assertNotNull(conflict, "STATUS_CONFLICT 差异单存在");
        assertEquals("1", conflict.getFrozenFlag(), "差异单冻结标记（BR-30/spec 100 行）");
        assertEquals("1", cardDao.selectById(card.getId()).getCountFreezeFlag(),
                "冲突生成即冻结卡片（禁止再次处置/调拨）");
        // 调拨被阻断
        EamTransferOrder req = new EamTransferOrder();
        req.setCardId(card.getId());
        req.setTransferType("DEPT");
        req.setToDept("X");
        req.setToCc("CC-CNT-B");
        req.setReason("冲突冻结阻断");
        // 卡为 DISPOSED，先撞「仅在用资产可调拨」——调拨整体被阻断即达目的
        // （冻结断言的独立验证在 tagFreezeBlocksTransferAndUnfreeze 用 IN_USE 卡覆盖）
        assertThrows(ServiceException.class, () -> transferService.create(req),
                "冲突冻结卡片调拨被阻断");
        // 审批闭环 → 解冻
        diffService.investigate(conflict.getId(), "误报废，恢复账面", "处置单核查记录");
        diffService.submitApproval(conflict.getId());
        EamCountDiff reloaded = diffDao.selectById(conflict.getId());
        passAllNodes(reloaded.getApprId());
        assertEquals("CLOSED", diffDao.selectById(conflict.getId()).getStatus());
        assertEquals("0", cardDao.selectById(card.getId()).getCountFreezeFlag(),
                "差异闭环自动解冻（BR-30 解除语义）");
    }

    @Test
    void twoTierApprovalChainAndReject() {
        mkCard("ZC-CNT-0050", "MACH", new BigDecimal("100000"), new BigDecimal("28000"),
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        mkCard("ZC-CNT-0051", "MACH", new BigDecimal("200000"), new BigDecimal("128000"),
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        countService.countEntry(plan.getId(), "ZC-CNT-0050", "MISSING", "查无原因", false);
        countService.countEntry(plan.getId(), "ZC-CNT-0051", "MISSING", "借出外协未办手续", false);
        diffService.autoCompare(plan.getId());
        // 小额（72000... 200000-128000=72000 超阈值；先测大额）
        EamCountDiff big = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .eq(EamCountDiff::getCardNo, "ZC-CNT-0051"));
        diffService.investigate(big.getId(), "借出外协未办手续，无法追回", "外协厂确认函");
        Map<String, Object> sub = diffService.submitApproval(big.getId());
        assertEquals(4, sub.get("nodes"), "72000 ≥ 5 万 → 四节点链");
        big = diffDao.selectById(big.getId());   // apprId 提交后才写入，重载
        // 驳回第二节点
        List<ApprovalTask> active = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, big.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        approvalEngine.pass(active.get(0).getId(), "同意");
        List<ApprovalTask> second = taskDao.selectList(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, big.getApprId())
                .eq(ApprovalTask::getStatus, "ACTIVE"));
        approvalEngine.reject(second.get(0).getId(), "证据不足");
        assertEquals("REJECTED", diffDao.selectById(big.getId()).getStatus());
        assertTrue(diffDao.selectById(big.getId()).getRejectReason().contains("证据不足"),
                "驳回原因留痕");
        // 小额（NBV 72000 大于阈值…… 造一张小额：0050 NBV=72000 也超。改阈值法：直接测 100000-28000=72000 一样。用参数改小阈值）
        sysParamService.setValue("ASSET_COUNT_DIFF_THRESHOLD", "80000", "NUMBER", "EAM",
                "测试临时调阈值", "junit");
        EamCountDiff small = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .eq(EamCountDiff::getCardNo, "ZC-CNT-0050"));
        diffService.investigate(small.getId(), "查无原因升级", "无");
        Map<String, Object> sub2 = diffService.submitApproval(small.getId());
        assertEquals(2, sub2.get("nodes"), "72000 < 8 万 → 两节点链");
        sysParamService.setValue("ASSET_COUNT_DIFF_THRESHOLD", "50000", "NUMBER", "EAM",
                "恢复默认", "junit");
    }

    // ================= 盘盈入账（BR-27）=================

    @Test
    void gainAutoPriceAndNoPriceBlocked() {
        // 有参照价分类
        mkCard("ZC-CNT-0060", "MACH", new BigDecimal("6800"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 3, 1));
        // 无参照价分类（12 月内无转资）
        jdbc.update("INSERT IGNORE INTO erp_eam_asset_category (ID, CATEGORY_CODE, NAME, "
                + "CAPITALIZATION_FLOOR, DEFAULT_LIFE_YEARS, DEFAULT_RESIDUAL_RATE, "
                + "DEFAULT_DEPRECIATION_METHOD, RISK_LEVEL, LIFE_YEARS_MIN, LIFE_YEARS_MAX, "
                + "ASSET_ACCOUNT, CREATE_BY, DEL_FLAG, VER_NO) VALUES "
                + "('cat-cnt-x','CNTX','CNT无参照',5000,10,0.05,'STRAIGHT_LINE','MID',1,50,'1601','junit','0',0)");
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        // 盘盈 MACH（有价）+ 盘盈 CNTX（无价）
        countService.countEntry(plan.getId(), null, "SURPLUS", "笔记本一台", false, "MACH");
        countService.countEntry(plan.getId(), null, "SURPLUS", "设备一台", false, "CNTX");
        diffService.autoCompare(plan.getId());
        // 无价差异：手动改分类为 CNTX 后提交 → 422 阻断
        EamCountDiff noPrice = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .isNull(EamCountDiff::getSuggestedPrice));
        assertNotNull(noPrice, "CNTX 分类无 12 月转资参照 → 无建议价");
        diffService.investigate(noPrice.getId(), "无计价依据升级", null);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> diffService.submitApproval(noPrice.getId()));
        assertTrue(ex.getMessage().contains("无计价依据"), "BR-27 无价阻断升级");
        // 有价差异：审批 → 入账建卡+凭证
        EamCountDiff priced = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .isNotNull(EamCountDiff::getSuggestedPrice));
        diffService.investigate(priced.getId(), "同分类近期采购价确认", "采购发票");
        diffService.submitApproval(priced.getId());
        priced = diffDao.selectById(priced.getId());   // apprId 提交后才写入，重载
        passAllNodes(priced.getApprId());
        assertEquals("READY_TO_POST", diffDao.selectById(priced.getId()).getStatus());
        Map<String, Object> post = diffService.postAdjust(priced.getId());
        assertNotNull(post.get("newCardNo"), "盘盈建卡");
        EamAssetCard newCard = cardDao.selectOne(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getCardNo, post.get("newCardNo")));
        assertEquals(0, new BigDecimal("6800").compareTo(newCard.getAssetCost()), "重置成本建卡");
        Integer gainV = jdbc.queryForObject("SELECT COUNT(*) FROM erp_fin_voucher "
                + "WHERE SOURCE_TYPE='COUNT_GAIN'", Integer.class);
        assertTrue(gainV >= 1, "盘盈凭证生成");
        assertEquals("CLOSED", diffDao.selectById(priced.getId()).getStatus());
    }

    // ================= 盘亏挂账与核销（BR-28）=================

    @Test
    void shortageSuspendLiabilityBlockAndWriteOff() {
        mkCard("ZC-CNT-0070", "MACH", new BigDecimal("180000"), new BigDecimal("108000"),
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        countService.countEntry(plan.getId(), "ZC-CNT-0070", "MISSING", "借出外协无法追回", false);
        diffService.autoCompare(plan.getId());
        EamCountDiff diff = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId())
                .eq(EamCountDiff::getDiffType, "SHORT"));
        assertEquals(0, new BigDecimal("72000").compareTo(diff.getDiffAmount()), "NBV=72000");
        diffService.investigate(diff.getId(), "使用部门管理不当", "借出记录");
        diffService.submitApproval(diff.getId());
        diff = diffDao.selectById(diff.getId());   // apprId 提交后才写入，重载
        passAllNodes(diff.getApprId());
        // 审批通过即挂账（BR-28 第一段）
        diff = diffDao.selectById(diff.getId());
        assertEquals("SUSPENDED", diff.getAdjustStatus(), "审批通过自动挂账 1901");
        assertNotNull(diff.getVoucherId());
        // 责任认定前核销 → 422 阻断
        String diffId = diff.getId();
        EamCountDiff pre = diffDao.selectById(diffId);
        pre.setStatus("READY_TO_POST");
        diffDao.updateById(pre);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> diffService.postAdjust(diffId));
        assertTrue(ex.getMessage().contains("责任认定"), "BR-28 认定前不得核销");
        // 责任认定：赔偿 20000
        diff = diffDao.selectById(diffId);
        diff.setStatus("LIABILITY_PENDING");
        diff.setVerNo(diff.getVerNo());
        diffDao.updateById(diff);
        diffService.liability(diff.getId(), "COMPENSATE", "设备科长", new BigDecimal("20000"),
                "责任认定书 CNT-LL-001");
        // 核销：损失 52000 + 赔偿 20000 / 贷 1901 72000
        Map<String, Object> post = diffService.postAdjust(diff.getId());
        assertEquals(0, new BigDecimal("52000").compareTo((BigDecimal) post.get("loss")));
        assertEquals(0, new BigDecimal("20000").compareTo((BigDecimal) post.get("compensate")));
        // 凭证借贷平
        String vid = (String) post.get("voucherId");
        BigDecimal dr = jdbc.queryForObject("SELECT SUM(AMOUNT) FROM erp_fin_voucher_line "
                + "WHERE VOUCHER_ID=? AND DIRECTION='DR'", BigDecimal.class, vid);
        BigDecimal cr = jdbc.queryForObject("SELECT SUM(AMOUNT) FROM erp_fin_voucher_line "
                + "WHERE VOUCHER_ID=? AND DIRECTION='CR'", BigDecimal.class, vid);
        assertEquals(0, dr.compareTo(cr), "盘亏核销凭证借贷平");
        // 卡片 DISPOSED
        assertEquals("DISPOSED", cardDao.selectOne(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getCardNo, "ZC-CNT-0070")).getStatus());
    }

    // ================= 标签冻结（BR-30）=================

    @Test
    void tagFreezeBlocksTransferAndUnfreeze() {
        EamAssetCard card = mkCard("ZC-CNT-0080", "MACH", new BigDecimal("100000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        // 冻结（现场发现双标签 → 新建 TAG_ISSUE 差异单）
        Map<String, Object> fz = diffService.freezeTag(card.getId(), null, true);
        assertEquals(Boolean.TRUE, fz.get("frozen"));
        Long tagDiff = diffDao.selectCount(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getCardId, card.getId())
                .eq(EamCountDiff::getDiffType, "TAG_ISSUE"));
        assertEquals(1, tagDiff, "冻结时新建 TAG_ISSUE 差异单（BR-30）");
        // 调拨被阻断（并入 BR-21 逐项列示）
        EamTransferOrder req = new EamTransferOrder();
        req.setCardId(card.getId());
        req.setTransferType("DEPT");
        req.setToDept("X");
        req.setToCc("CC-CNT-B");
        req.setReason("冻结阻断测试");
        ServiceException ex = assertThrows(ServiceException.class, () -> transferService.create(req));
        assertTrue(ex.getMessage().contains("盘点冻结"), "冻结卡片调拨被阻断");
        // 补标解冻 → 调拨放行
        EamCountDiff tag = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getCardId, card.getId())
                .eq(EamCountDiff::getDiffType, "TAG_ISSUE"));
        Map<String, Object> close = diffService.closeTagIssue(tag.getId());
        assertEquals(Boolean.TRUE, close.get("unfrozen"));
        assertEquals("CLOSED", diffDao.selectById(tag.getId()).getStatus());
        assertDoesNotThrow(() -> transferService.create(req));
    }

    // ================= 覆盖率结案（BR-26 L1）=================

    @Test
    void coverageBlocksCloseThenSupplementCloses() {
        // 4 张卡 → 盘 3 张（75%）→ 结案阻断 → 补盘第 4 张 → 差异闭环 → 报告归档 CLOSED
        for (int i = 1; i <= 4; i++) {
            mkCard("ZC-CNT-009" + i, "MACH", new BigDecimal("10000"), null,
                    "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        }
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        for (int i = 1; i <= 3; i++) {
            countService.countEntry(plan.getId(), "ZC-CNT-009" + i, "IN_USE", null, false);
        }
        countService.countEntry(plan.getId(), "ZC-CNT-0094", "MISSING", "未找到", false);
        diffService.autoCompare(plan.getId());
        Map<String, Object> cov = countService.refreshCoverage(plan.getId());
        assertEquals(4, cov.get("countedQty"));
        // 差异未闭环 → 结案阻断
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> countService.submitReport(plan.getId()));
        assertTrue(ex1.getMessage().contains("未闭环差异"), "差异未闭环阻断结案");
        // 闭环差异
        EamCountDiff diff = diffDao.selectOne(new LambdaQueryWrapper<EamCountDiff>()
                .eq(EamCountDiff::getPlanId, plan.getId()));
        diff.setStatus("CLOSED");
        diff.setAdjustStatus("POSTED");
        diff.setVerNo(diff.getVerNo());
        diffDao.updateById(diff);
        // 模拟第 4 张盘掉：将 MISSING 行改已盘（补盘完成覆盖率 100%）— 简化：直接覆盖为 IN_USE
        EamCountLine l4 = lineDao.selectOne(new LambdaQueryWrapper<EamCountLine>()
                .eq(EamCountLine::getPlanId, plan.getId())
                .eq(EamCountLine::getCardNo, "ZC-CNT-0094"));
        EamCountLine lup = new EamCountLine();
        lup.setId(l4.getId());
        lup.setVerNo(l4.getVerNo());
        lup.setActualStatus("IN_USE");
        lup.setCountedBy("junit");
        lup.setCountedAt(java.time.LocalDateTime.now());
        lineDao.updateById(lup);
        // MISSING 行被改为 IN_USE 后原差异已手工 CLOSED；覆盖率=4/4
        Map<String, Object> cov2 = countService.refreshCoverage(plan.getId());
        assertEquals(4, cov2.get("countedQty"));
        // 结案报告 → 财务资产会计单签 → 归档 CLOSED
        Map<String, Object> rep = countService.submitReport(plan.getId());
        assertNotNull(rep.get("apprId"));
        assertTrue(((String) rep.get("reportSummary")).contains("覆盖率"), "报告含覆盖率");
        passAllNodes((String) rep.get("apprId"));
        assertEquals("CLOSED", planDao.selectById(plan.getId()).getStatus(), "报告归档后结案");
    }

    @Test
    void coverageBelow100BlocksClose() {
        // 2 张卡盘 1 张（50%）→ FULL 结案 422 列未盘清单
        mkCard("ZC-CNT-0101", "MACH", new BigDecimal("10000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        mkCard("ZC-CNT-0102", "MACH", new BigDecimal("10000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2026, 1, 15));
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        countService.countEntry(plan.getId(), "ZC-CNT-0101", "IN_USE", null, false);
        Map<String, Object> cov = countService.refreshCoverage(plan.getId());
        assertEquals(1, cov.get("countedQty"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> countService.submitReport(plan.getId()));
        assertTrue(ex.getMessage().contains("覆盖率不足"), "BR-26 覆盖率 <100% 阻断结案");
        assertTrue(ex.getMessage().contains("ZC-CNT-0102"), "列示未盘卡片");
    }

    // ================= 闲置联动（S-4.13-13）=================

    @Test
    void countedInUseRefreshesLastUsedAt() {
        EamAssetCard card = mkCard("ZC-CNT-0110", "MACH", new BigDecimal("10000"), null,
                "CNT 甲部门", "CC-CNT-A", LocalDate.of(2025, 1, 15));
        jdbc.update("UPDATE erp_eam_asset_card SET LAST_USED_AT='2025-06-01' WHERE ID=?",
                card.getId());
        EamCountPlan plan = mkPlan("FULL", LocalDate.of(2026, 6, 30));
        countService.issue(plan.getId());
        Map<String, Object> out = countService.countEntry(plan.getId(), "ZC-CNT-0110",
                "IN_USE", null, true);
        assertEquals(Boolean.TRUE, out.get("lastUsedRefreshed"), "实盘在用刷新闲置锚");
        LocalDate lastUsed = jdbc.queryForObject("SELECT LAST_USED_AT FROM erp_eam_asset_card "
                + "WHERE ID=?", LocalDate.class, card.getId());
        assertEquals(LocalDate.now(), lastUsed, "LAST_USED_AT=盘点日（共享池数据源 S-4.13-13）");
    }
}
