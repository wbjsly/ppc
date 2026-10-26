package com.erp.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamDisposalLineDao;
import com.erp.dao.eam.EamDisposalOrderDao;
import com.erp.dao.eam.EamDisposalRecycleDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamDisposalLine;
import com.erp.entity.eam.EamDisposalOrder;
import com.erp.entity.eam.EamDisposalRecycle;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.eam.EamDepreciationService;
import com.erp.service.eam.EamDisposalService;
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
 * add-asset-disposal：资产报废处置 DbTest（spec asset-disposal）。
 * 批量前置阻断 / C-05 残值预警 / BR-31 出售报告 / 三档分级链 /
 * 两段式凭证（段1 转清理 C-03）/ 段2应收+段3损益+卡片 DISPOSED /
 * BR-32 提足 / BR-33 科目缺失 / BR-34 当月折旧豁免 /
 * BR-35 应收关卡+收款 / BR-36 附件阻断 / 回收入库 GR 回写 / 冲回 / 结案归档。
 */
@SpringBootTest
class AssetDisposalDbTest {

    @Autowired
    private EamDisposalService disposalService;
    @Autowired
    private EamDepreciationService depreciationService;
    @Autowired
    private EamDisposalOrderDao orderDao;
    @Autowired
    private EamDisposalLineDao lineDao;
    @Autowired
    private EamDisposalRecycleDao recycleDao;
    @Autowired
    private EamAssetCardDao cardDao;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private ApprovalEngine approvalEngine;
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

    private void purge() {
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE IN "
                + "('DISPOSAL_TRANSFER','DISPOSAL_PROCEEDS','DISPOSAL_RECYCLE','DISPOSAL_GAINLOSS')) t1)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE IN "
                + "('DISPOSAL_TRANSFER','DISPOSAL_PROCEEDS','DISPOSAL_RECYCLE','DISPOSAL_GAINLOSS')");
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION') t2)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='DEPRECIATION'");
        jdbc.update("DELETE FROM erp_eam_depreciation_detail WHERE CARD_NO LIKE 'ZC-DSP-%'");
        jdbc.update("DELETE FROM erp_eam_depreciation_run WHERE RUN_NO LIKE 'JZ%'");
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('AssetDisposal','AssetDisposalReport')) t3)");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('AssetDisposal','AssetDisposalReport')");
        jdbc.update("DELETE FROM erp_eam_disposal_recycle WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_eam_disposal_order) t4)");
        jdbc.update("DELETE FROM erp_eam_disposal_line WHERE ORDER_ID IN "
                + "(SELECT ID FROM (SELECT ID FROM erp_eam_disposal_order) t5)");
        jdbc.update("DELETE FROM erp_eam_disposal_order");
        // 回收入库载体（FREE + 虚拟回收商）
        jdbc.update("DELETE FROM erp_fin_voucher_line WHERE VOUCHER_ID IN (SELECT ID FROM "
                + "(SELECT ID FROM erp_fin_voucher WHERE SOURCE_TYPE='GR') t6)");
        jdbc.update("DELETE FROM erp_fin_voucher WHERE SOURCE_TYPE='GR'");
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE='SPK-DSP-001'");
        jdbc.update("DELETE FROM erp_proc_gr_line WHERE ITEM_CODE='SPK-DSP-001'");
        jdbc.update("DELETE FROM erp_proc_gr WHERE SUPPLIER_ID='sup-eam-recycle'");
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE='SPK-DSP-001'");
        jdbc.update("DELETE FROM erp_eam_asset_card WHERE CARD_NO LIKE 'ZC-DSP-%'");
        jdbc.update("UPDATE erp_eam_asset_category SET ENV_REVIEW_FLAG='0', DATA_DESTROY_FLAG='0' "
                + "WHERE ENV_REVIEW_FLAG='1' OR DATA_DESTROY_FLAG='1'");
        jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE IN "
                + "('6115','1606','1122') AND DEL_FLAG='1'");
        // 仓位环境重建（历史 STORAGE 类型残留会被 IGNORE 跳过）
        jdbc.update("DELETE FROM erp_inv_bin WHERE ID='bin-dsp-01'");
        jdbc.update("DELETE FROM erp_inv_zone WHERE ID='zone-dsp'");
    }

    private void ensureParams() {
        jdbc.update("INSERT IGNORE INTO erp_sys_param (ID,PARAM_KEY,PARAM_VALUE,VALUE_TYPE,"
                + "PARAM_GROUP,REMARK,CREATE_BY) VALUES ('param-dsp-th-a','ASSET_DISPOSAL_THRESHOLD_A',"
                + "'500000','NUMBER','EAM','junit 复位','junit'),"
                + "('param-dsp-th-b','ASSET_DISPOSAL_THRESHOLD_B','5000000','NUMBER','EAM','junit 复位','junit')");
        jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE='500000' WHERE PARAM_KEY='ASSET_DISPOSAL_THRESHOLD_A'");
        jdbc.update("UPDATE erp_sys_param SET PARAM_VALUE='5000000' WHERE PARAM_KEY='ASSET_DISPOSAL_THRESHOLD_B'");
    }

    private void login(String user) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(user, null, "ROLE_ADMIN"));
    }

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

    private EamAssetCard mkCard(String cardNo, String category, BigDecimal cost, BigDecimal accum,
                                String dept, String cc, LocalDate effective) {
        EamAssetCard c = new EamAssetCard();
        c.setCardNo(cardNo);
        c.setAssetName("DSP 测试资产-" + cardNo);
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

    private void mkRecycleItem() {
        jdbc.update("INSERT IGNORE INTO erp_mdm_item (ID,ITEM_CODE,ITEM_NAME,CATEGORY_CODE,"
                + "BASE_UNIT,MATERIAL_GROUP,PURCHASE_TYPE,STORAGE_CONDITION,BATCH_FLAG,SERIAL_FLAG,"
                + "STATUS,STANDARD_COST,CREATE_BY,DEL_FLAG,VER_NO) VALUES "
                + "('it-spk-dsp','SPK-DSP-001','DSP 废铜料','SMKI','PC','STRUCT','BUY','NORMAL',"
                + "'1','0','1',500,'junit','0',0)");
        // 仓位环境：STORAGE 区 + 仓位（posting 强前置 putaway CONFIRMED 的前提）
        jdbc.update("INSERT IGNORE INTO erp_inv_zone (ID,WH_CODE,ZONE_CODE,ZONE_NAME,SORT_ORDER,"
                + "DEF_BIN_TYPE,STATUS,CREATE_BY,DEL_FLAG,VER_NO) VALUES ('zone-dsp','WH-MAIN','ST',"
                + "'DSP 存储区',10,'STORAGE','1','junit','0',0)");
        jdbc.update("INSERT IGNORE INTO erp_inv_bin (ID,WH_CODE,ZONE_CODE,BIN_CODE,BIN_TYPE,STATUS,"
                + "CREATE_BY,DEL_FLAG,VER_NO) VALUES ('bin-dsp-01','WH-MAIN','ST','DSP-BIN-01',"
                + "'STORE','1','junit','0',0)");
    }

    @SuppressWarnings("unchecked")
    private EamDisposalOrder created(Map<String, Object> out) {
        return (EamDisposalOrder) out.get("order");
    }

    private EamDisposalOrder mkOrder(String type, BigDecimal estResidual, String reportNo,
                                     EamAssetCard... cards) {
        EamDisposalOrder o = new EamDisposalOrder();
        o.setDisposalType(type);
        o.setReasonCode("TECH");
        o.setReasonNote("DSP 测试处置");
        o.setEstResidual(estResidual);
        o.setEvalReportNo(reportNo);
        List<EamDisposalLine> lines = new java.util.ArrayList<>();
        for (EamAssetCard c : cards) {
            EamDisposalLine l = new EamDisposalLine();
            l.setCardId(c.getId());
            l.setLineProceeds(type.equals("SALE") ? c.getAssetCost() : null);
            lines.add(l);
        }
        return created(disposalService.create(o, lines, List.of()));
    }

    // ================= 1. 批量与前置阻断 =================

    @Test
    void batchCreateAndBlockers() {
        EamAssetCard a = mkCard("ZC-DSP-0010", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamAssetCard frozen = mkCard("ZC-DSP-0011", "MACH", new BigDecimal("50000"),
                BigDecimal.ZERO, "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamAssetCard cupd = new EamAssetCard();
        cupd.setId(frozen.getId());
        cupd.setVerNo(frozen.getVerNo());
        cupd.setCountFreezeFlag("1");
        cardDao.updateById(cupd);
        // 批量两卡 + 冻结卡 → 422 逐项列示冻结
        EamDisposalOrder o = new EamDisposalOrder();
        o.setDisposalType("SCRAP");
        o.setReasonCode("DAMAGE");
        EamDisposalLine l1 = new EamDisposalLine();
        l1.setCardId(a.getId());
        EamDisposalLine l2 = new EamDisposalLine();
        l2.setCardId(frozen.getId());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> disposalService.create(o, List.of(l1, l2), List.of()));
        assertTrue(ex.getMessage().contains("盘点冻结"), "冻结卡阻断列示");
        // 正常批量两卡创建
        EamDisposalOrder ok = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
        assertEquals("DRAFT", ok.getAppStatus());
        assertEquals(1, lineDao.selectCount(new LambdaQueryWrapper<EamDisposalLine>()
                .eq(EamDisposalLine::getOrderId, ok.getId())));
        // 快照 NBV = 100000 - 40000 = 60000
        assertEquals(0, new BigDecimal("60000").compareTo(ok.getTotalNbv()), "快照 NBV");
        // 同卡重复发起阻断
        assertThrows(ServiceException.class, () -> mkOrder("SCRAP", BigDecimal.ZERO, null, a));
    }

    // ================= 2. C-05 残值预警须复核 =================

    @Test
    void residualAlertRequiresReview() {
        EamAssetCard a = mkCard("ZC-DSP-0020", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        // NBV 60000 × 10% = 6000；评估残值 20000 触发 C-4.13-05
        EamDisposalOrder o = mkOrder("SCRAP", new BigDecimal("20000"), null, a);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> disposalService.submit(o.getId()));
        assertTrue(ex.getMessage().contains("复核"), "残值偏高须复核说明");
        // 补复核说明后可提交
        EamDisposalOrder upd = new EamDisposalOrder();
        upd.setId(o.getId());
        upd.setReviewNote("二手设备市场询价复核，成色较差但残值偏高属正常");
        disposalService.update(upd, List.of(lineOf(o.getId())), List.of());
        Map<String, Object> sub = disposalService.submit(o.getId());
        assertEquals(2, sub.get("nodes"), "小额两节点链");
    }

    private EamDisposalLine lineOf(String orderId) {
        return lineDao.selectList(new LambdaQueryWrapper<EamDisposalLine>()
                .eq(EamDisposalLine::getOrderId, orderId)).get(0);
    }

    // ================= 3. BR-31 出售评估价低于 NBV =================

    @Test
    void saleBelowNbvRequiresReport() {
        EamAssetCard a = mkCard("ZC-DSP-0030", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = new EamDisposalOrder();
        o.setDisposalType("SALE");
        o.setReasonCode("IDLE");
        o.setEstResidual(BigDecimal.ZERO);
        EamDisposalLine l = new EamDisposalLine();
        l.setCardId(a.getId());
        l.setLineProceeds(new BigDecimal("50000"));   // < NBV 60000 → 损失 10000
        EamDisposalOrder saved = created(disposalService.create(o, List.of(l), List.of()));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> disposalService.submit(saved.getId()));
        assertTrue(ex.getMessage().contains("评估报告"), "BR-31 出售评估价<NBV 须报告");
        // 补报告号后可提交
        EamDisposalOrder upd = new EamDisposalOrder();
        upd.setId(saved.getId());
        upd.setEvalReportNo("EVAL-DSP-001");
        disposalService.update(upd, List.of(lineOf(saved.getId())), List.of());
        assertDoesNotThrow(() -> disposalService.submit(saved.getId()));
    }

    // ================= 4. 三档分级链 =================

    @Test
    void tieredApprovalChains() {
        EamAssetCard c30 = mkCard("ZC-DSP-0040", "MACH", new BigDecimal("300000"),
                BigDecimal.ZERO, "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamAssetCard c60 = mkCard("ZC-DSP-0041", "MACH", new BigDecimal("600000"),
                BigDecimal.ZERO, "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamAssetCard c600 = mkCard("ZC-DSP-0042", "MACH", new BigDecimal("6000000"),
                BigDecimal.ZERO, "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o1 = mkOrder("SCRAP", BigDecimal.ZERO, null, c30);
        assertEquals(2, disposalService.submit(o1.getId()).get("nodes"), "30万 < A → 两节点");
        EamDisposalOrder o2 = mkOrder("SCRAP", BigDecimal.ZERO, null, c60);
        assertEquals(3, disposalService.submit(o2.getId()).get("nodes"), "60万 ≥ A → 三节点");
        EamDisposalOrder o3 = mkOrder("SCRAP", BigDecimal.ZERO, null, c600);
        Map<String, Object> sub3 = disposalService.submit(o3.getId());
        assertEquals(4, sub3.get("nodes"), "600万 ≥ B → 四节点");
        assertEquals("TIER_B", sub3.get("tier"));
    }

    // ================= 5. 段1 转清理凭证（C-03 审批通过自动生成） =================

    @Test
    void approvalPassGeneratesTransferVoucher() {
        EamAssetCard a = mkCard("ZC-DSP-0050", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
        disposalService.submit(o.getId());
        o = orderDao.selectById(o.getId());   // apprId 提交后才写入，重载
        passAllNodes(o.getApprId());
        assertEquals("APPROVED", orderDao.selectById(o.getId()).getAppStatus(), "审批通过推进 APPROVED");
        // 段1 借贷：清理 60000 + 累计 40000 / 固定资产 100000
        Map<String, Object> dr = jdbc.queryForMap("SELECT COALESCE(SUM(l.AMOUNT),0) AS amt "
                + "FROM erp_fin_voucher_line l JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='DISPOSAL_TRANSFER' AND v.SOURCE_DOC_NO=? AND l.DIRECTION='DR'",
                o.getId());
        assertEquals(0, new BigDecimal("100000.00").compareTo(new BigDecimal(String.valueOf(dr.get("amt")))),
                "段1 借方合计 = NBV 60000 + 累计折旧 40000");
        Long cr = jdbc.queryForObject("SELECT COUNT(*) FROM erp_fin_voucher_line l "
                + "JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='DISPOSAL_TRANSFER' AND v.SOURCE_DOC_NO=? AND l.DIRECTION='CR'",
                Long.class, o.getId());
        assertEquals(1L, cr, "段1 贷方固定资产一行");
    }

    // ================= 6. 执行确认：段2/3 凭证 + 卡片 DISPOSED =================

    @Test
    void execGainLossAndCardDisposed() {
        EamAssetCard a = mkCard("ZC-DSP-0060", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = new EamDisposalOrder();
        o.setDisposalType("SALE");
        o.setReasonCode("TECH");
        o.setEstResidual(BigDecimal.ZERO);
        EamDisposalLine l = new EamDisposalLine();
        l.setCardId(a.getId());
        l.setLineProceeds(new BigDecimal("120000"));
        EamDisposalOrder saved = created(disposalService.create(o, List.of(l), List.of()));
        disposalService.submit(saved.getId());
        saved = orderDao.selectById(saved.getId());
        passAllNodes(saved.getApprId());
        Map<String, Object> out = disposalService.exec(saved.getId(), "合同 CT-DSP-001", null, null);
        // 损益 = 120000 - 60000 = +60000
        assertEquals(0, new BigDecimal("60000.00").compareTo((BigDecimal) out.get("gainLoss")), "处置损益 +60000");
        // 段2 挂应收 + 段3 收益（贷 6115）
        Long gainCr = jdbc.queryForObject("SELECT COUNT(*) FROM erp_fin_voucher_line l "
                + "JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='DISPOSAL_GAINLOSS' AND v.SOURCE_DOC_NO=? "
                + "AND l.ACCOUNT_CODE='6115' AND l.DIRECTION='CR'", Long.class, saved.getId());
        assertEquals(1L, gainCr, "段3 收益贷 6115");
        // 卡片 DISPOSED + DISPOSED_AT
        EamAssetCard after = cardDao.selectById(a.getId());
        assertEquals("DISPOSED", after.getStatus(), "执行确认置已处置");
        assertNotNull(after.getDisposedAt(), "DISPOSED_AT 锚（BR-34）");
        assertEquals("RECEIVABLE", orderDao.selectById(saved.getId()).getAppStatus(), "出售挂应收");
        assertEquals(0, new BigDecimal("120000.00")
                .compareTo(orderDao.selectById(saved.getId()).getReceivableBal()), "应收=处置价");
    }

    // ================= 7. BR-32 已提足折旧校验 =================

    @Test
    void fullyDepreciatedValidation() {
        EamAssetCard a = mkCard("ZC-DSP-0070", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        // 篡改累计折旧 → 提足标记失效 → BR-32 阻断
        EamDisposalOrder o = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
        EamAssetCard cupd = new EamAssetCard();
        cupd.setId(a.getId());
        cupd.setVerNo(a.getVerNo());
        cupd.setAccumDepr(new BigDecimal("30000"));   // ≠ 100000-5000 = 95000
        cardDao.updateById(cupd);
        EamDisposalOrder upd = new EamDisposalOrder();
        upd.setId(o.getId());
        disposalService.update(upd, List.of(lineOf(o.getId())), List.of());
        // 重新快照后 FULLY_DEPR_FLAG=0（累计 30000 ≠ 提足）→ submit 不触发 BR-32 分支，
        // 但把 flag 手工置 1 模拟脏数据 → 阻断
        jdbc.update("UPDATE erp_eam_disposal_line SET FULLY_DEPR_FLAG='1' WHERE ORDER_ID=?", o.getId());
        ServiceException ex = assertThrows(ServiceException.class,
                () -> disposalService.submit(o.getId()));
        assertTrue(ex.getMessage().contains("BR-4.13-32"), "提足校验阻断");
    }

    // ================= 8. BR-33 科目缺失阻断 =================

    @Test
    void missingAccountBlocks() {
        EamAssetCard a = mkCard("ZC-DSP-0080", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
        disposalService.submit(o.getId());
        o = orderDao.selectById(o.getId());
        jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='1' WHERE ACCOUNT_CODE='6115'");
        try {
            passAllNodes(o.getApprId());
            fail("科目缺失时审批签署应被阻断");
        } catch (ServiceException expected) {
            assertTrue(expected.getMessage().contains("6115"), "阻断列示缺失科目 6115");
        } finally {
            jdbc.update("UPDATE erp_fin_account SET DEL_FLAG='0' WHERE ACCOUNT_CODE='6115'");
        }
    }

    // ================= 9. 回收入库 + GR 回写 =================

    @Test
    void recycleInboundGrWriteback() {
        mkRecycleItem();
        EamAssetCard a = mkCard("ZC-DSP-0090", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
        EamDisposalRecycle r = new EamDisposalRecycle();
        r.setItemName("废铜料");
        r.setItemCode("SPK-DSP-001");
        r.setQty(new BigDecimal("2"));
        r.setEstPrice(new BigDecimal("500"));
        // 重新建单带回收明细
        jdbc.update("DELETE FROM erp_eam_disposal_line WHERE ORDER_ID=?", o.getId());
        jdbc.update("DELETE FROM erp_eam_disposal_order WHERE ID=?", o.getId());
        EamDisposalOrder o2 = created(disposalService.create(orderWith(o), List.of(lineOf2(a)), List.of(r)));
        disposalService.submit(o2.getId());
        o2 = orderDao.selectById(o2.getId());
        passAllNodes(o2.getApprId());
        Map<String, Object> out = disposalService.exec(o2.getId(), "报废证明 BP-001", null, null);
        // 回收 500×2=1000 冲减损失：损益 = 0 + 1000 - 60000 = -59000
        assertEquals(0, new BigDecimal("-59000.00").compareTo((BigDecimal) out.get("gainLoss")), "回收冲减损益");
        EamDisposalRecycle rr = recycleDao.selectList(new LambdaQueryWrapper<EamDisposalRecycle>()
                .eq(EamDisposalRecycle::getOrderId, o2.getId())).get(0);
        assertEquals("POSTED", rr.getGrStatus(), "回收入库完成");
        assertNotNull(rr.getGrId(), "GR_ID 回写");
        Integer stock = jdbc.queryForObject("SELECT COALESCE(SUM(QTY),0) FROM erp_inv_stock "
                + "WHERE ITEM_CODE='SPK-DSP-001'", Integer.class);
        assertTrue(stock >= 2, "库存入库数量（" + stock + "）");
    }

    private EamDisposalOrder orderWith(EamDisposalOrder proto) {
        EamDisposalOrder o = new EamDisposalOrder();
        o.setDisposalType(proto.getDisposalType());
        o.setReasonCode(proto.getReasonCode());
        o.setReasonNote(proto.getReasonNote());
        o.setEstResidual(proto.getEstResidual());
        return o;
    }

    private EamDisposalLine lineOf2(EamAssetCard c) {
        EamDisposalLine l = new EamDisposalLine();
        l.setCardId(c.getId());
        return l;
    }

    // ================= 10. BR-35 应收未结不得关闭 + 收款 =================

    @Test
    void receivableBlocksCloseThenReceive() {
        EamAssetCard a = mkCard("ZC-DSP-0100", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = new EamDisposalOrder();
        o.setDisposalType("SALE");
        o.setReasonCode("IDLE");
        o.setEstResidual(BigDecimal.ZERO);
        EamDisposalLine l = new EamDisposalLine();
        l.setCardId(a.getId());
        l.setLineProceeds(new BigDecimal("90000"));
        EamDisposalOrder saved = created(disposalService.create(o, List.of(l), List.of()));
        disposalService.submit(saved.getId());
        saved = orderDao.selectById(saved.getId());
        passAllNodes(saved.getApprId());
        disposalService.exec(saved.getId(), "合同 CT-002", null, null);
        // 未收款结案 → 422
        String sid1 = saved.getId();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> disposalService.submitReport(sid1));
        assertTrue(ex.getMessage().contains("应收处置款未结清"), "BR-35 未收不得关闭");
        // 收款 90000 → 余额清零
        Map<String, Object> rcv = disposalService.receive(saved.getId(), new BigDecimal("90000"), "转账");
        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) rcv.get("receivableBal")), "应收清零");
        Map<String, Object> rep = disposalService.submitReport(saved.getId());
        assertNotNull(rep.get("apprId"), "结案报告提交");
        passAllNodes((String) rep.get("apprId"));
        assertEquals("CLOSED", orderDao.selectById(saved.getId()).getAppStatus(), "归档 CLOSED");
    }

    // ================= 11. BR-36 分类双标记附件阻断 =================

    @Test
    void envDataFlagBlocksExec() {
        jdbc.update("UPDATE erp_eam_asset_category SET DATA_DESTROY_FLAG='1' "
                + "WHERE CATEGORY_CODE='MACH'");
        try {
            EamAssetCard a = mkCard("ZC-DSP-0110", "MACH", new BigDecimal("100000"),
                    new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
            EamDisposalOrder o = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
            disposalService.submit(o.getId());
            o = orderDao.selectById(o.getId());
            passAllNodes(o.getApprId());
            String oId = o.getId();
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> disposalService.exec(oId, "报废证明 BP-002", null, null));
            assertTrue(ex.getMessage().contains("数据销毁"), "BR-36 缺数据销毁证明阻断");
            // 补传后通过
            assertDoesNotThrow(() -> disposalService.exec(oId, "报废证明 BP-002", null, "DD-001"));
        } finally {
            jdbc.update("UPDATE erp_eam_asset_category SET DATA_DESTROY_FLAG='0' "
                    + "WHERE CATEGORY_CODE='MACH'");
        }
    }

    // ================= 12. 整单冲回 =================

    @Test
    void reverseRestoresCards() {
        EamAssetCard a = mkCard("ZC-DSP-0120", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
        disposalService.submit(o.getId());
        o = orderDao.selectById(o.getId());
        passAllNodes(o.getApprId());
        disposalService.exec(o.getId(), "报废证明 BP-003", null, null);
        assertEquals("DISPOSED", cardDao.selectById(a.getId()).getStatus());
        Map<String, Object> out = disposalService.reverse(o.getId());
        assertTrue((Integer) out.get("reversedVouchers") >= 2, "段1+段3 凭证已冲回");
        EamAssetCard after = cardDao.selectById(a.getId());
        assertEquals("IN_USE", after.getStatus(), "卡片回 IN_USE");
        assertNull(after.getDisposedAt(), "DISPOSED_AT 清空");
        assertEquals("APPROVED", orderDao.selectById(o.getId()).getAppStatus(), "状态回 APPROVED 可重执行");
    }

    // ================= 13. BR-34 处置当月折旧豁免 =================

    @Test
    void disposalMonthDepreciationExemption() {
        EamAssetCard a = mkCard("ZC-DSP-0130", "MACH", new BigDecimal("120000"),
                BigDecimal.ZERO, "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        // 常驻在用卡：保证次月批次非空（可断言明细不含处置卡）
        mkCard("ZC-DSP-0131", "MACH", new BigDecimal("60000"),
                BigDecimal.ZERO, "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = mkOrder("SCRAP", BigDecimal.ZERO, null, a);
        disposalService.submit(o.getId());
        o = orderDao.selectById(o.getId());
        passAllNodes(o.getApprId());
        disposalService.exec(o.getId(), "报废证明 BP-004", null, null);
        // 当月计提：DISPOSED 且 DISPOSED_AT 本月 → 仍应计提（BR-34 当月豁免）
        String cur = LocalDate.now().toString().substring(0, 7).replace("-", "");
        depreciationService.runDepreciation(cur);
        Integer inCur = jdbc.queryForObject("SELECT COUNT(*) FROM erp_eam_depreciation_detail "
                + "WHERE CARD_NO='ZC-DSP-0130'", Integer.class);
        assertEquals(1, inCur, "处置当月仍整月计提");
        // 次月计提：该卡不再出现
        int m = Integer.parseInt(cur.substring(4));
        String next = m == 12 ? (Integer.parseInt(cur.substring(0, 4)) + 1) + "01"
                : cur.substring(0, 4) + String.format("%02d", m + 1);
        depreciationService.runDepreciation(next);
        Integer inNext = jdbc.queryForObject("SELECT COUNT(*) FROM erp_eam_depreciation_detail d "
                + "JOIN erp_eam_depreciation_run r ON r.ID=d.RUN_ID "
                + "WHERE d.CARD_NO='ZC-DSP-0130' AND r.PERIOD_TAG=?", Integer.class, next);
        assertEquals(0, inNext, "次月起停提");
    }

    // ================= 14. 冲回后重执行 + 收款凭证 =================

    @Test
    void receiveGeneratesBankVoucherAndRerunAfterReverse() {
        EamAssetCard a = mkCard("ZC-DSP-0140", "MACH", new BigDecimal("100000"),
                new BigDecimal("40000"), "DSP 甲部门", null, LocalDate.of(2026, 1, 15));
        EamDisposalOrder o = new EamDisposalOrder();
        o.setDisposalType("SALE");
        o.setReasonCode("TECH");
        o.setEstResidual(BigDecimal.ZERO);
        EamDisposalLine l = new EamDisposalLine();
        l.setCardId(a.getId());
        l.setLineProceeds(new BigDecimal("80000"));
        EamDisposalOrder saved = created(disposalService.create(o, List.of(l), List.of()));
        disposalService.submit(saved.getId());
        saved = orderDao.selectById(saved.getId());
        passAllNodes(saved.getApprId());
        disposalService.exec(saved.getId(), "合同 CT-003", null, null);
        disposalService.receive(saved.getId(), new BigDecimal("50000"), "首款");
        // 收款凭证：借 1002 / 贷 1122
        Long bank = jdbc.queryForObject("SELECT COUNT(*) FROM erp_fin_voucher_line l "
                + "JOIN erp_fin_voucher v ON v.ID=l.VOUCHER_ID "
                + "WHERE v.SOURCE_TYPE='DISPOSAL_PROCEEDS' AND v.SOURCE_DOC_NO=? "
                + "AND l.ACCOUNT_CODE='1002' AND l.DIRECTION='DR'", Long.class, saved.getId());
        assertEquals(1L, bank, "收款凭证借银行");
        // 冲回后可重新执行（状态回 APPROVED）
        disposalService.reverse(saved.getId());
        assertEquals("APPROVED", orderDao.selectById(saved.getId()).getAppStatus());
        String sid2 = saved.getId();
        assertDoesNotThrow(() -> disposalService.exec(sid2, "合同 CT-003", null, null));
    }
}
