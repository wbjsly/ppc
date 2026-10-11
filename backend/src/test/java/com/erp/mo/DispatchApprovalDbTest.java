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
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.mdm.MdmItemService;
import com.erp.service.mrp.BomService;
import com.erp.service.mrp.DispatchService;
import com.erp.service.mrp.DispatchService.GenReq;
import com.erp.service.mrp.KittingService;
import com.erp.service.mrp.MoService;
import com.erp.service.mrp.OpWcStandardService;
import com.erp.service.mrp.OperationService;
import com.erp.service.mrp.RoutingService;
import com.erp.service.mrp.WorkCenterService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审批链库测（task 5.1 + 5.2 + 5.3，spec dispatch-management「缺料派工 L2 审批」/
 * outsourcing-dispatch「外协改派与 L2 审批」/ kitting-check MODIFIED「状态翻转通知时机」）：
 * 缺料申请→计划主管签→生成缺料先行单+通知申请人、驳回不生成+通知、拒重 422、
 * 外协改派通过改派+二次校验、驳回保持原状、5.5 通知接收方含车间主管。
 */
@SpringBootTest
class DispatchApprovalDbTest {

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
    private KittingService kittingService;
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
        jdbc.update("DELETE t FROM erp_sys_approval_task t JOIN erp_sys_approval a "
                + "ON t.APPR_ID=a.ID WHERE a.BIZ_TYPE IN ('ShortageDispatch','OutsourceDispatch')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE IN "
                + "('ShortageDispatch','OutsourceDispatch')");
        jdbc.update("DELETE FROM erp_sys_notice WHERE BIZ_TYPE IN "
                + "('DISPATCH_APPROVAL_RESULT','MO_SHORTAGE','MO_KITTING_RESTORED')");
        categoryCode = MoSeed.mkCategory(jdbc);
        MoTestAuth.login("appr-base", "ROLE_PLANNER", "ROLE_PROCESS_ENG", "ROLE_ADMIN");
    }

    @AfterEach
    void tearDown() {
        MoTestAuth.logout();
        jdbc.update("DELETE FROM erp_mrp_dispatch");
        MoSeed.cleanup(jdbc);
        jdbc.update("DELETE FROM erp_mrp_work_center WHERE WC_CODE LIKE 'MOWC-%'");
        jdbc.update("DELETE FROM erp_mrp_operation WHERE OP_CODE LIKE 'MOOP-%'");
        jdbc.update("DELETE FROM erp_mrp_routing WHERE ITEM_CODE IN "
                + "(SELECT ITEM_CODE FROM erp_mdm_item WHERE ITEM_NAME LIKE '工单测试%')");
    }

    // ---------- helpers ----------

    /** 缺料 Released 工单（有路线无库存） */
    private MrpMo shortageMo(String suffix, int opCount) {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "审批缺料产品" + suffix, "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "审批缺料子件" + suffix, "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, product, opCount);
        return MoSeed.releaseMo(moService, product, "10", START, END);
    }

    /** 齐套 Released 工单（有库存） */
    private MrpMo soundMo(String suffix, int opCount) {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "审批齐套产品" + suffix, "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "审批齐套子件" + suffix, "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.publishRoutingMulti(routingService, operationService,
                workCenterService, standardService, jdbc, product, opCount);
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        return MoSeed.releaseMo(moService, product, "10", START, END);
    }

    private Integer firstOpSeq(String moId) {
        return moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId).orderByAsc(MrpMoOp::getOpSeq)).get(0).getOpSeq();
    }

    /** 以计划主管身份从待办中找到该审批的可签任务 ID */
    private String taskIdAsPlanMgr(String apprNo) {
        MoTestAuth.logout();
        MoTestAuth.login("appr-mgr", "ROLE_PLAN_MGR");
        return approvalEngine.todo().stream()
                .filter(t -> apprNo.equals(t.get("apprNo")))
                .map(t -> (String) t.get("taskId"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("待办缺任务：" + apprNo));
    }

    private long noticeCount(String bizType, String targetUser, String targetRole) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_notice WHERE BIZ_TYPE=? "
                        + "AND (TARGET_USER=? OR TARGET_ROLE=?)",
                Long.class, bizType, targetUser, targetRole);
        return n == null ? 0 : n;
    }

    // ---------- 5.1 缺料派工审批 ----------

    @Test
    void shortageApprovalApproveGeneratesAndNotifies() {
        MrpMo mo = shortageMo("A", 1);
        Integer opSeq = firstOpSeq(mo.getId());
        assertEquals("1", mo.getShortageFlag(), "缺料前提");

        MoTestAuth.logout();
        MoTestAuth.login("appr-planner", "ROLE_PLANNER");
        Map<String, Object> applied = dispatchService.applyShortage(mo.getId(), opSeq);
        assertEquals("PENDING", applied.get("status"), "提交即 PENDING");
        String apprNo = (String) applied.get("apprNo");

        // 同一工序 PENDING 拒重（底座）
        ServiceException dup = assertThrows(ServiceException.class,
                () -> dispatchService.applyShortage(mo.getId(), opSeq));
        assertEquals(422, dup.getCode(), "重复申请 → 422");

        // 通过（计划主管签）
        String taskId = taskIdAsPlanMgr(apprNo);
        approvalEngine.pass(taskId, "同意缺料先行开工");

        // 生成缺料先行派工单 + 通知申请人
        List<MrpDispatch> list = dispatchDao.selectList(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getMoId, mo.getId()));
        assertEquals(1, list.size(), "审批通过生成 1 张派工单");
        assertEquals("1", list.get(0).getShortageFirstFlag(), "缺料先行标记");
        assertEquals(MrpDispatch.ST_PENDING_CONFIRM, list.get(0).getStatus(), "待确认态");
        assertEquals(1, noticeCount("DISPATCH_APPROVAL_RESULT", "appr-planner", null),
                "通过结果推送申请人");
        assertEquals("DISPATCHED", moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId())).get(0).getOpStatus(), "opStatus 推进");

        // 缺料先行单可确认（双点门禁放行）——SHOP_FLOOR
        MoTestAuth.logout();
        MoTestAuth.login("appr-floor", "ROLE_SHOP_FLOOR");
        MrpDispatch confirmed = dispatchService.confirm(list.get(0).getId());
        assertEquals(MrpDispatch.ST_RELEASED, confirmed.getStatus(), "缺料先行单确认通过");
    }

    @Test
    void shortageApprovalRejectNotifiesWithoutDispatch() {
        MrpMo mo = shortageMo("B", 1);
        Integer opSeq = firstOpSeq(mo.getId());

        MoTestAuth.logout();
        MoTestAuth.login("appr-planner2", "ROLE_PLANNER");
        Map<String, Object> applied = dispatchService.applyShortage(mo.getId(), opSeq);
        String taskId = taskIdAsPlanMgr((String) applied.get("apprNo"));
        approvalEngine.reject(taskId, "不同意，待料优先");

        // 不生成派工单 + 驳回通知 + 实例 REJECTED
        assertEquals(0, dispatchDao.selectCount(new LambdaQueryWrapper<MrpDispatch>()
                .eq(MrpDispatch::getMoId, mo.getId())), "驳回不生成派工单");
        assertEquals(1, noticeCount("DISPATCH_APPROVAL_RESULT", "appr-planner2", null),
                "驳回结果推送申请人");
        assertEquals("REJECTED", approvalEngine.findByBiz("ShortageDispatch",
                mo.getId() + "#" + opSeq).getStatus(), "实例 REJECTED 留痕");
        // 工序仍待派（可再次申请）
        assertEquals("PENDING", moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId())).get(0).getOpStatus(), "工序保持 PENDING");
    }

    // ---------- 5.2 外协改派审批 ----------

    @Test
    void outsourceApprovalApproveChangesWcRejectKeeps() {
        MrpMo mo = soundMo("C", 2);
        List<Integer> ops = moOpDao.selectList(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, mo.getId()).orderByAsc(MrpMoOp::getOpSeq))
                .stream().map(MrpMoOp::getOpSeq).toList();
        MrpDispatch d1 = dispatchService.generate(List.of(new GenReq(mo.getId(), ops.get(0), null)))
                .get(0);
        MrpDispatch d2 = dispatchService.generate(List.of(new GenReq(mo.getId(), ops.get(1), null)))
                .get(0);
        String internalWc = d1.getWcCode();

        // 目标外协 WC（合格供应商 SUPFINTGT）
        MrpWorkCenter os = new MrpWorkCenter();
        os.setWcCode("MOWC-OSAPP");
        os.setWcName("外协审批测试中心");
        os.setWcType("OUTSOURCED");
        os.setSupplierCode("SUPFINTGT");
        workCenterService.create(os);

        // 提交权限：SHOP_FLOOR 不可申请（outsourcing 权限矩阵）
        MoTestAuth.logout();
        MoTestAuth.login("appr-floor2", "ROLE_SHOP_FLOOR");
        ServiceException fe = assertThrows(ServiceException.class,
                () -> dispatchService.applyOutsource(d1.getId(), "MOWC-OSAPP"));
        assertEquals(403, fe.getCode(), "SHOP_FLOOR 申请外协 → 403");

        // PLANNER 申请：目标非外协类型 422
        MoTestAuth.logout();
        MoTestAuth.login("appr-planner3", "ROLE_PLANNER");
        ServiceException ie = assertThrows(ServiceException.class,
                () -> dispatchService.applyOutsource(d1.getId(), internalWc));
        assertEquals(422, ie.getCode(), "目标须外协类型 → 422");

        Map<String, Object> applied = dispatchService.applyOutsource(d1.getId(), "MOWC-OSAPP");
        assertEquals("PENDING", applied.get("status"));

        // d1 通过 → 改派
        String t1 = taskIdAsPlanMgr((String) applied.get("apprNo"));
        approvalEngine.pass(t1, "同意外协");
        MrpDispatch changed = dispatchDao.selectById(d1.getId());
        assertEquals("MOWC-OSAPP", changed.getWcCode(), "改派外协 WC");
        assertEquals("OUTSOURCED", changed.getWcType(), "WC 类型外协");
        assertEquals("1", changed.getOutsourceFlag(), "外协标记");
        assertEquals(1, noticeCount("DISPATCH_APPROVAL_RESULT", "appr-planner3", null),
                "通过结果推送申请人");

        // d2 申请后驳回 → 保持原状
        MoTestAuth.logout();
        MoTestAuth.login("appr-planner3", "ROLE_PLANNER");
        Map<String, Object> a2 = dispatchService.applyOutsource(d2.getId(), "MOWC-OSAPP");
        String t2 = taskIdAsPlanMgr((String) a2.get("apprNo"));
        approvalEngine.reject(t2, "本期不外协");
        MrpDispatch unchanged = dispatchDao.selectById(d2.getId());
        assertEquals(internalWc, unchanged.getWcCode(), "驳回保持原派工 WC");
        assertEquals("0", unchanged.getOutsourceFlag(), "驳回无外协标记");
        assertEquals(2, noticeCount("DISPATCH_APPROVAL_RESULT", "appr-planner3", null),
                "两次结果均推送申请人");
    }

    // ---------- 5.3 5.5 通知回补车间主管 ----------

    @Test
    void kittingFlipNoticeReachesShopFloor() {
        MdmItem product = MoSeed.mkItem(itemService, categoryCode, "回补通知产品", "MAKE");
        MdmItem child = MoSeed.mkItem(itemService, categoryCode, "回补通知子件", "BUY");
        MoSeed.publishBom(bomService, jdbc, product, child, "1", "0");
        MoSeed.mkStock(jdbc, child.getItemCode(), "9999");
        MrpMo mo = MoSeed.releaseMo(moService, product, "10", START, END);
        assertEquals("0", mo.getShortageFlag(), "初始齐套");

        // 库存被领走 → 调度器源重算（notifyOnFlip=true）→ 翻转推送四角色
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE=?", child.getItemCode());
        kittingService.check(mo.getId(), true);

        assertEquals(1, noticeCount("MO_SHORTAGE", null, "ROLE_SHOP_FLOOR"),
                "车间主管收到缺料翻转通知（5.5 偏差 D1 回补）");
        assertEquals(1, noticeCount("MO_SHORTAGE", null, "ROLE_PLANNER"),
                "计划员仍收到（既有三角色不回归）");
        assertEquals(1, noticeCount("MO_SHORTAGE", null, "ROLE_WAREHOUSE"),
                "仓库仍收到");
    }
}
