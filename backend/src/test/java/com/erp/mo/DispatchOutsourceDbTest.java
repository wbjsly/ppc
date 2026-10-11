package com.erp.mo;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.entity.mdm.MdmItem;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.entity.mrp.MrpMo;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.entity.proc.ProcPrLine;
import com.erp.entity.proc.ProcRequisition;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import com.erp.service.proc.ProcRequisitionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外协 PR 漏斗库测（task 6.2 + 6.3，spec outsourcing-dispatch「PR 漏斗下单与回联」，
 * design D5 接缝结论）：审批通过自动生 PR（SOURCE=OUTSOURCE、sourceDocNo=DISPATCH_NO、
 * 价格 0、状态 PENDING_CONFIRM）+ 重复审批不重复生 PR；回联 ORDERED→RECEIVED 推进与
 * OUTSOURCE_PROGRESS 通知、幂等、非外协静默跳过；createForOutsource 参数与状态机校验。
 */
@SpringBootTest
class DispatchOutsourceDbTest {

    @Autowired
    private MoService moService;
    @Autowired
    private MdmItemService itemService;
    @Autowired
    private BomService bomService;
    @Autowired
    private RoutingService routingService;
    @Autowired
    private OperationService operationService;
    @Autowired
    private WorkCenterService workCenterService;
    @Autowired
    private OpWcStandardService standardService;
    @Autowired
    private DispatchService dispatchService;
    @Autowired
    private ProcRequisitionService requisitionService;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private MrpDispatchDao dispatchDao;
    @Autowired
    private MrpMoOpDao moOpDao;
    @Autowired
    private JdbcTemplate jdbc;

    private String categoryCode;

    private static final LocalDate START = LocalDate.of(2028, 6, 28);
    private static final LocalDate END = LocalDate.of(2028, 7, 28);

    @BeforeEach
    void seed() {
        MoSeed.cleanup(jdbc);
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE l FROM erp_proc_pr_line l JOIN erp_proc_requisition p ON l.PR_ID=p.ID "
                + "WHERE p.SOURCE_TYPE='OUTSOURCE'");
        jdbc.update("DELETE FROM erp_proc_requisition WHERE SOURCE_TYPE='OUTSOURCE'");
        // PR 事件幂等键按单号生成，删 PR 后同号重发布会撞 C-0-06 → 清事件行
        jdbc.update("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'PR-%'");
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a "
                + "ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE='OutsourceDispatch'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE='OutsourceDispatch'");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN "
                + "('OUTSOURCE_PROGRESS','DISPATCH_APPROVAL_RESULT')");
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE='MOOP-OSCAND'");
        jdbc.update("DELETE FROM erp_proc_framework_agreement_line WHERE AGREEMENT_ID='agr-os-cand'");
        jdbc.update("DELETE FROM erp_proc_framework_agreement WHERE ID='agr-os-cand'");
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("os-base", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        jdbc.update("DELETE l FROM erp_proc_pr_line l JOIN erp_proc_requisition p ON l.PR_ID=p.ID "
                + "WHERE p.SOURCE_TYPE='OUTSOURCE'");
        jdbc.update("DELETE FROM erp_proc_requisition WHERE SOURCE_TYPE='OUTSOURCE'");
        jdbc.update("DELETE FROM erp_ops_outbox WHERE IDEMPOTENCY_KEY LIKE 'PR-%'");
        MoSeed.cleanup(jdbc);
        jdbc.update("DELETE FROM erp_mrp_op_wc_standard WHERE OP_CODE='MOOP-OSCAND'");
        jdbc.update("DELETE FROM erp_proc_framework_agreement_line WHERE AGREEMENT_ID='agr-os-cand'");
        jdbc.update("DELETE FROM erp_proc_framework_agreement WHERE ID='agr-os-cand'");
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
    }

    // ---------- helpers ----------

    /** 齐套 Released 工单（1 工序） */
    private MrpMo soundMo(String suffix) {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "外协漏斗产品" + suffix, "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "外协漏斗子件" + suffix, "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, product, 1);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        return MoSeed.releaseMo(moService, product, "10", START, END);
    }

    private Integer firstOpSeq(String moId) {
        return moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId).orderByAsc(MrpMoOp::getOpSeq)).get(0).getOpSeq();
    }

    /** 建外协 WC（合格供应商 SUPFINTGT） */
    private void mkOutsourceWc(String wcCode) {
        MrpWorkCenter os = new MrpWorkCenter();
        os.setWcCode(wcCode);
        os.setWcName("外协漏斗中心" + wcCode);
        os.setWcType("OUTSOURCED");
        os.setSupplierCode("SUPFINTGT");
        workCenterService.create(os);
    }

    /** 走完「生成 → 外协申请 → 计划主管通过」，返回改派后派工单 */
    private MrpDispatch approveOutsourceFlow(MrpMo mo, String wcCode) {
        MrpDispatch d = dispatchService.generate(
                List.of(new GenReq(mo.getId(), firstOpSeq(mo.getId()), null))).get(0);
        mkOutsourceWc(wcCode);
        MoTestAuth.logout();
        MoTestAuth.login("os-planner", "ROLE_PLANNER");
        Map<String, Object> applied = dispatchService.applyOutsource(d.getId(), wcCode);
        MoTestAuth.logout();
        MoTestAuth.login("os-mgr", "ROLE_PLAN_MGR");
        String taskId = approvalEngine.todo().stream()
                .filter(t -> applied.get("apprNo").equals(t.get("apprNo")))
                .map(t -> (String) t.get("taskId"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("待办缺任务"));
        approvalEngine.pass(taskId, "同意外协并下单");
        return dispatchDao.selectById(d.getId());
    }

    private long progressNoticeCount() {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='OUTSOURCE_PROGRESS'",
                Long.class);
        return n == null ? 0 : n;
    }

    // ---------- 6.2 审批通过自动生 PR ----------

    @Test
    void approveGeneratesOutsourcePrWithRecallAnchor() {
        MrpMo mo = soundMo("A");
        MrpDispatch d = approveOutsourceFlow(mo, "MOWC-OSFNA");

        // 派工单回写 PR 单号
        assertNotNull(d.getPrNo(), "审批通过自动生成 PR 并回写 prNo");
        assertTrue(d.getPrNo().startsWith("PR"), "PR 单号形态：" + d.getPrNo());

        // PR 头：SOURCE=OUTSOURCE、状态 PENDING_CONFIRM（走既有确认→审批链）
        Map<String, Object> head = jdbc.queryForMap(
                "SELECT SOURCE_TYPE, STATUS, REQ_REASON FROM erp_proc_requisition WHERE PR_NO=?",
                d.getPrNo());
        assertEquals("OUTSOURCE", head.get("SOURCE_TYPE"), "来源 OUTSOURCE");
        assertEquals("PENDING_CONFIRM", head.get("STATUS"), "待确认（既有漏斗入口）");
        assertTrue(String.valueOf(head.get("REQ_REASON")).contains(d.getDispatchNo()),
                "需求理由含派工单号：" + head.get("REQ_REASON"));

        // PR 行：回联锚点 + 价格 0 + 物料/数量/日期口径（design D5）
        Map<String, Object> line = jdbc.queryForMap(
                "SELECT ITEM_CODE, QTY, REQ_DATE, EST_UNIT_PRICE, SOURCE_ENUM, SOURCE_DOC_NO, "
                        + "MRP_SUGGESTED_QTY FROM erp_proc_pr_line l "
                        + "JOIN erp_proc_requisition p ON l.PR_ID=p.ID WHERE p.PR_NO=?", d.getPrNo());
        assertEquals("OUTSOURCE", line.get("SOURCE_ENUM"), "行来源标记");
        assertEquals(d.getDispatchNo(), line.get("SOURCE_DOC_NO"), "行 sourceDocNo=派工单号（回联锚点）");
        assertEquals(0, new BigDecimal("0").compareTo(
                new BigDecimal(String.valueOf(line.get("EST_UNIT_PRICE")))), "价格 0（采购环节价控）");
        assertEquals(mo.getProductCode(), line.get("ITEM_CODE"), "行物料=工单产成品");
        assertEquals(0, mo.getQty().compareTo(new BigDecimal(String.valueOf(line.get("QTY")))),
                "行数量=MO.QTY");
        assertEquals(d.getPlanStart().toString(), String.valueOf(line.get("REQ_DATE")),
                "需求日期=派工计划开工日");
        assertEquals(null, line.get("MRP_SUGGESTED_QTY"), "无 MRP 基线 → 80% 卡控跳过");

        // 重复审批通过（幂等）→ 不重复生 PR
        String prNoBefore = d.getPrNo();
        Long prCountBefore = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_proc_requisition WHERE SOURCE_TYPE='OUTSOURCE'",
                Long.class);
        dispatchService.approveOutsource(d.getId(), "MOWC-OSFNA", "os-planner", "APPR-DUP");
        assertEquals(prNoBefore, dispatchDao.selectById(d.getId()).getPrNo(), "幂等：prNo 不变");
        assertEquals(prCountBefore, jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_proc_requisition WHERE SOURCE_TYPE='OUTSOURCE'",
                Long.class), "幂等：PR 计数不变");

        // 确认入口可用（既有 confirm()：状态机通过、无 MRP 基线不触发 80% 卡控）
        MoTestAuth.logout();
        MoTestAuth.login("os-buyer", "ROLE_USER");
        String prId = jdbc.queryForObject(
                "SELECT ID FROM erp_proc_requisition WHERE PR_NO=?", String.class, d.getPrNo());
        Map<String, Object> detail = requisitionService.confirm(prId);
        assertNotNull(detail, "既有确认链路可走通（进入 CONFIRMED 待审批）");
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_proc_requisition WHERE PR_NO=?", String.class, d.getPrNo()),
                "PR 已确认（漏斗第二级）");
    }

    @Test
    void createForOutsourceValidations() {
        // 参数校验（design D5 契约）
        ServiceException e1 = assertThrows(ServiceException.class,
                () -> requisitionService.createForOutsource(List.of(), "DPX", "外协理由"));
        assertEquals(422, e1.getCode(), "空行 422");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> requisitionService.createForOutsource(
                        List.of(Map.of("itemCode", "X", "qty", 1, "reqDate", "2028-06-01")),
                        " ", "外协理由"));
        assertEquals(422, e2.getCode(), "缺派工单号 422");
        ServiceException e3 = assertThrows(ServiceException.class,
                () -> requisitionService.createForOutsource(
                        List.of(Map.of("itemCode", "X", "qty", 1, "reqDate", "2028-06-01")),
                        "DPX", "短"));
        assertEquals(422, e3.getCode(), "理由<2字 422");
        ServiceException e4 = assertThrows(ServiceException.class,
                () -> requisitionService.createForOutsource(
                        List.of(Map.of("itemCode", "NOT-EXIST-XYZ", "qty", 1,
                                "reqDate", "2028-06-01")),
                        "DPX", "外协理由足够"));
        assertEquals(422, e4.getCode(), "物料不存在 422：" + e4.getMessage());
    }

    // ---------- 6.3 回联 ORDERED → RECEIVED ----------

    @Test
    void recallOrderedThenReceivedWithProgressNotice() {
        MrpMo mo = soundMo("B");
        MrpDispatch d = approveOutsourceFlow(mo, "MOWC-OSFNB");
        assertNotNull(d.getPrNo(), "前置：PR 已生");

        // ORDERED：PO 下达回写 + 通知
        assertTrue(dispatchService.recallOrdered(d.getPrNo(), "PO-RSVP-0001"), "首次回联 true");
        MrpDispatch afterOrdered = dispatchDao.selectById(d.getId());
        assertEquals("PO-RSVP-0001", afterOrdered.getPoNo(), "PO 号回写");
        assertEquals(1, progressNoticeCount(), "ORDERED 推送申请人 1 条");
        Long orderNotices = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE='OUTSOURCE_PROGRESS' "
                        + "AND TITLE='外协已下单'", Long.class);
        assertEquals(1L, orderNotices == null ? 0 : orderNotices, "「外协已下单」标题");

        // 幂等：重复 ORDERED 不重复回写/通知
        assertEquals(false, dispatchService.recallOrdered(d.getPrNo(), "PO-RSVP-0001"),
                "幂等二次 false");
        assertEquals(1, progressNoticeCount(), "幂等：通知不重复");

        // RECEIVED：GR 过账回写 + 通知
        assertTrue(dispatchService.recallReceived("PO-RSVP-0001"), "首次收货回联 true");
        MrpDispatch afterRc = dispatchDao.selectById(d.getId());
        assertEquals("1", afterRc.getReceiptFlag(), "收货标记置位");
        assertEquals(2, progressNoticeCount(), "RECEIVED 追加 1 条");
        assertEquals(false, dispatchService.recallReceived("PO-RSVP-0001"), "收货幂等二次 false");
        assertEquals(2, progressNoticeCount(), "收货幂等：通知不重复");

        // 非外协/不存在 → 静默 false（采购主流程零影响）
        assertEquals(false, dispatchService.recallOrdered("PR-NOT-EXIST", "PO-X"), "未知 PR 静默");
        assertEquals(false, dispatchService.recallReceived("PO-NOT-EXIST"), "未知 PO 静默");
        assertEquals(false, dispatchService.recallOrdered(null, "PO-X"), "空参静默");
        assertEquals(false, dispatchService.recallReceived(null), "空参静默");
    }

    // ---------- 6.4 外协候选推荐 ----------

    @Test
    void outsourceCandidatesFilterAndAgreementPrice() {
        // 定额工序（jdbc 直插——服务层 C-4.5-05 会挡「无供应商外协」的保存，场景须绕开造数）
        String op = "MOOP-OSCAND";
        String product = "FG-OSCAND-01";
        // A：启用 + 供应商1（有协议价）；B：启用 + 供应商2 SUPLOCK001（无任何协议价行）；
        // C：启用 + 无供应商（→ 过滤）；D：停用 + 供应商（→ 过滤）；E：INTERNAL（→ 类型过滤）
        // 注：协议价按「供应商×物料」查（spec 原文），A/B 必须不同供应商才能测出两种文案
        String[][] wcs = {
                {"MOWC-OSA", "OUTSOURCED", "SUPFINTGT", "1"},
                {"MOWC-OSB", "OUTSOURCED", "SUPLOCK001", "1"},
                {"MOWC-OSC", "OUTSOURCED", null, "1"},
                {"MOWC-OSD", "OUTSOURCED", "SUPFINTGT", "3"},
                {"MOWC-OSINT", "INTERNAL", null, "1"},
        };
        for (String[] w : wcs) {
            jdbc.update("INSERT INTO erp_mrp_work_center (ID, WC_CODE, WC_NAME, WC_TYPE, "
                            + "SUPPLIER_CODE, STATUS) VALUES (?,?,?,?,?,?)",
                    "wc-" + w[0].toLowerCase(), w[0], "候选测试-" + w[0], w[1], w[2], w[3]);
            jdbc.update("INSERT INTO erp_mrp_op_wc_standard (ID, OP_CODE, WC_CODE, RUN_HOURS) "
                    + "VALUES (?,?,?,1)", "std-" + w[0].toLowerCase(), op, w[0]);
        }
        // 协议价：生效中（STATUS=1）+ 今日有效期内，对 SUPFINTGT（sup-fin-tgt）× product 报价 88
        jdbc.update("INSERT INTO erp_proc_framework_agreement (ID, AGREEMENT_NO, TITLE, "
                        + "EFFECTIVE_DATE, EXPIRE_DATE, STATUS, TOTAL_SHARE) "
                        + "VALUES ('agr-os-cand','AGR-OS-CAND','外协候选协议价',?,?, '1', 100)",
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(365));
        jdbc.update("INSERT INTO erp_proc_framework_agreement_line (ID, AGREEMENT_ID, LINE_NO, "
                        + "ITEM_CODE, AWARD_SUPPLIER_ID, UNIT_PRICE, SHARE_PCT) "
                        + "VALUES ('agrl-os-cand','agr-os-cand',1,?,?,?,100)",
                product, "sup-fin-tgt", new BigDecimal("88"));

        // PLANNER 可查；候选 = A/B（类型/停用/供应商缺失/非QUALIFIED 四重过滤）
        MoTestAuth.logout();
        MoTestAuth.login("os-cand", "ROLE_PLANNER");
        List<Map<String, Object>> cands = dispatchService.outsourceCandidates(op, product, null);
        assertEquals(2, cands.size(), "候选=A+B（C 无供应商、D 停用、E 内部均被过滤）");
        Map<String, Object> a = cands.stream()
                .filter(c -> "MOWC-OSA".equals(c.get("wcCode"))).findFirst().orElse(null);
        Map<String, Object> b = cands.stream()
                .filter(c -> "MOWC-OSB".equals(c.get("wcCode"))).findFirst().orElse(null);
        assertNotNull(a, "A 在候选");
        assertNotNull(b, "B 在候选");
        assertEquals("SUPFINTGT", a.get("supplierCode"), "供应商带出");
        assertNotNull(a.get("loadRate"), "今日负荷率字段在（5.6.2 同口径）");
        assertEquals(0, new BigDecimal("88").compareTo(
                new BigDecimal(String.valueOf(a.get("agreementPrice")))), "A 协议价 ¥88");
        assertEquals("协议价 ¥88", a.get("priceText"), "D8 文案：有协议价");
        assertEquals(null, b.get("agreementPrice"), "B 无协议价 → null");
        assertEquals("无协议价", b.get("priceText"), "D8 文案：无协议价不报错");

        // 负荷升序排列（同 0% 时按 WC 编码 → OS A 在前）
        assertEquals("MOWC-OSA", cands.get(0).get("wcCode"), "排序稳定（编码升序）");

        // 无定额工序 → 空列表（非报错）
        assertEquals(0, dispatchService.outsourceCandidates("MOOP-NOT-EXIST", product, null).size(),
                "无定额 → 空候选");
        // 空工序编码 422
        ServiceException e = assertThrows(ServiceException.class,
                () -> dispatchService.outsourceCandidates(" ", product, null));
        assertEquals(422, e.getCode(), "工序必填 422");

        // 权限：非授权角色 403
        MoTestAuth.logout();
        MoTestAuth.login("os-noperm", "ROLE_USER");
        ServiceException pe = assertThrows(ServiceException.class,
                () -> dispatchService.outsourceCandidates(op, product, null));
        assertEquals(403, pe.getCode(), "USER 查候选 403");
    }
}
