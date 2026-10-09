package com.erp.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ExpiryEvalDao;
import com.erp.dao.system.ApprovalTaskDao;
import com.erp.entity.inv.ExpiryEval;
import com.erp.entity.system.ApprovalTask;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.inv.ExpiryEvalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 效期质量评估 DB 侧（spec expiry-management 需求④⑤，任务 4.1/4.2）：
 * 发起校验（非锁定 422/重复 422/角色 403/说明必填）、
 * 三分支判定（SCRAP 生成报废单+驳回作废、RELEASE 有效期校验+签署写豁免清锁、
 * FREEZE 调冻结链即刻 CLOSED）、同人签署拦截。
 */
@SpringBootTest
class ExpiryEvalDbTest {

    private static final String ITEM = "IT-EXPIRY-EVAL-TEST";
    private static final String WH = "WH-EVALE";

    @Autowired
    private ExpiryEvalService service;
    @Autowired
    private ExpiryEvalDao evalDao;
    @Autowired
    private ApprovalEngine engine;
    @Autowired
    private ApprovalTaskDao taskDao;
    @Autowired
    private JdbcTemplate jdbc;

    private final List<String> evalIds = new ArrayList<>();
    private final List<String> apprIds = new ArrayList<>();

    @BeforeEach
    void setup() {
        // 清场（评估/报废/冻结/审批/库存/批次）
        jdbc.update("DELETE t FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval a ON t.APPR_ID = a.ID WHERE a.BIZ_TYPE = 'ExpiryEval'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'ExpiryEval'");
        jdbc.update("DELETE FROM erp_inv_expiry_eval WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE l FROM erp_inv_scrap_order_line l "
                + "JOIN erp_inv_scrap_order o ON l.ORDER_ID = o.ID WHERE l.ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_scrap_order WHERE REMARK LIKE '%EVALE%' OR REMARK LIKE ?",
                "%" + ITEM + "%");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("INSERT INTO erp_mdm_item (ID, ITEM_CODE, ITEM_NAME, CATEGORY_CODE, BASE_UNIT, "
                + "MATERIAL_GROUP, PURCHASE_TYPE, STORAGE_CONDITION, BATCH_FLAG, SERIAL_FLAG, "
                + "STATUS, CREATE_BY) VALUES ('junit-ev-it', ?, '评估测试物料', '0001', 'PCS', "
                + "'G001', 'BUY', 'NORMAL', '1', '0', '1', 'junit')", ITEM);
        login("q-eng", "ROLE_QUALITY_ENG");
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE t FROM erp_sys_approval_task t "
                + "JOIN erp_sys_approval a ON t.APPR_ID = a.ID WHERE a.BIZ_TYPE = 'ExpiryEval'");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'ExpiryEval'");
        jdbc.update("DELETE FROM erp_inv_expiry_eval WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE l FROM erp_inv_scrap_order_line l "
                + "JOIN erp_inv_scrap_order o ON l.ORDER_ID = o.ID WHERE l.ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_scrap_order WHERE REMARK LIKE '%EVALE%' OR REMARK LIKE ?",
                "%" + ITEM + "%");
        jdbc.update("DELETE FROM erp_inv_freeze WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_stock WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_inv_batch WHERE ITEM_CODE = ?", ITEM);
        jdbc.update("DELETE FROM erp_mdm_item WHERE ITEM_CODE = ?", ITEM);
        evalIds.clear();
        apprIds.clear();
        SecurityContextHolder.clearContext();
    }

    private void login(String user, String... roles) {
        var authorities = java.util.Arrays.stream(roles).map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, "n/a", authorities));
    }

    private void insertLockedBatch(String batch, String lockFlag, String lockSource) {
        jdbc.update("INSERT INTO erp_inv_batch (ID, BATCH_NO, ITEM_CODE, ITEM_NAME, "
                        + "PRODUCTION_DATE, EXPIRY_DATE, EXPIRY_LOCK_FLAG, LOCK_SOURCE, "
                        + "STATUS, CREATE_BY, DEL_FLAG, VER_NO) VALUES (?, ?, ?, '评估测试物料', "
                        + "?, ?, ?, ?, '1', 'junit', '0', 0)",
                "junit-ev-" + batch, batch, ITEM,
                LocalDate.now().minusDays(60).toString(), LocalDate.now().plusDays(40).toString(),
                lockFlag, lockSource);
        jdbc.update("INSERT INTO erp_inv_stock (ID, WAREHOUSE_CODE, ITEM_CODE, ITEM_NAME, BATCH_NO, "
                        + "QTY, QC_QTY, FIN_QTY, AVAILABLE_QTY, INBOUND_DATE, CREATE_BY) VALUES "
                        + "(?, ?, ?, '评估测试物料', ?, 100, 0, 0, 100, '2026-10-01', 'junit')",
                "junit-ev-stk-" + batch, WH, ITEM, batch);
    }

    private ExpiryEval submit(String batch, String note) {
        ExpiryEval e = service.submit(ITEM, batch, note, null);
        evalIds.add(e.getId());
        return e;
    }

    private ApprovalTask activeTask(String apprId) {
        apprIds.add(apprId);
        return taskDao.selectOne(new LambdaQueryWrapper<ApprovalTask>()
                .eq(ApprovalTask::getApprId, apprId)
                .eq(ApprovalTask::getStatus, "ACTIVE"));
    }

    // ================= 4.1 发起校验 =================

    @Test
    void submitRequiresLockedBatch() {
        insertLockedBatch("B-EV-OPEN", "0", "AUTO");
        ServiceException e = assertThrows(ServiceException.class,
                () -> submit("B-EV-OPEN", "未锁定不可评估"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("仅锁定批次"));
    }

    @Test
    void submitRequiresNoteAndQualityRole() {
        insertLockedBatch("B-EV-N1", "1", "AUTO");
        ServiceException blank = assertThrows(ServiceException.class,
                () -> submit("B-EV-N1", " "));
        assertEquals(422, blank.getCode());

        login("wh-x", "ROLE_WAREHOUSE");
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> service.submit(ITEM, "B-EV-N1", "仓库发起", null));
        assertEquals(403, forbidden.getCode());
    }

    @Test
    void submitDuplicateOpenRejected() {
        insertLockedBatch("B-EV-DUP", "1", "AUTO");
        submit("B-EV-DUP", "第一次");
        ServiceException e = assertThrows(ServiceException.class,
                () -> submit("B-EV-DUP", "第二次"));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("未关闭"));
    }

    // ================= 4.2 三分支 =================

    @Test
    void scrapBranchCreatesScrapOrderAndRejectCancels() {
        insertLockedBatch("B-EV-SCRAP", "1", "AUTO");
        ExpiryEval e = submit("B-EV-SCRAP", "过期变质拟报废");
        Map<String, Object> out = service.submitConclusion(e.getId(), "SCRAP", null, null);
        assertNotNull(out.get("scrapNo"), "生成报废单");

        ExpiryEval after = evalDao.selectById(e.getId());
        assertEquals(ExpiryEval.ST_PENDING_APPR, after.getStatus());
        assertEquals(out.get("scrapNo"), after.getScrapDocNo());
        String scrapStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_scrap_order WHERE SCRAP_NO = ?",
                String.class, after.getScrapDocNo());
        assertEquals("DRAFT", scrapStatus);

        // 驳回 → 评估回 PENDING_EVAL + 报废单作废
        ApprovalTask t = activeTask(after.getApprId());
        login("q-mgr", "ROLE_QUALITY_MGR");
        engine.reject(t.getId(), "证据不足");
        ExpiryEval back = evalDao.selectById(e.getId());
        assertEquals(ExpiryEval.ST_PENDING_EVAL, back.getStatus(), "驳回回待判定");
        String cancelled = jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_scrap_order WHERE SCRAP_NO = ?",
                String.class, after.getScrapDocNo());
        assertEquals("CANCELLED", cancelled, "驳回作废关联报废单");
    }

    @Test
    void releaseBranchValidatesUntilAndWritesExemptionOnApprove() {
        insertLockedBatch("B-EV-REL", "1", "AUTO");
        ExpiryEval e = submit("B-EV-REL", "临期评估拟放行");

        // 有效期非未来 → 422
        ServiceException past = assertThrows(ServiceException.class,
                () -> service.submitConclusion(e.getId(), "RELEASE",
                        LocalDate.now().minusDays(1).toString(), null));
        assertEquals(422, past.getCode());
        assertTrue(past.getMessage().contains("晚于今日"));
        // 缺有效期 → 422
        ServiceException blank = assertThrows(ServiceException.class,
                () -> service.submitConclusion(e.getId(), "RELEASE", null, null));
        assertEquals(422, blank.getCode());

        // 合规放行 → 审批 → 签署通过写豁免清锁
        String until = LocalDate.now().plusDays(30).toString();
        service.submitConclusion(e.getId(), "RELEASE", until, null);
        ExpiryEval pending = evalDao.selectById(e.getId());
        assertEquals(ExpiryEval.ST_PENDING_APPR, pending.getStatus());
        ApprovalTask t = activeTask(pending.getApprId());
        login("q-mgr", "ROLE_QUALITY_MGR");
        engine.pass(t.getId(), "同意放行");

        ExpiryEval closed = evalDao.selectById(e.getId());
        assertEquals(ExpiryEval.ST_CLOSED, closed.getStatus());
        assertEquals(java.sql.Date.valueOf(until), jdbc.queryForObject(
                "SELECT EVAL_EXEMPT_UNTIL FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                java.sql.Date.class, ITEM, "B-EV-REL"), "豁免写入");
        assertEquals("0", jdbc.queryForObject(
                "SELECT EXPIRY_LOCK_FLAG FROM erp_inv_batch WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, "B-EV-REL"), "放行清锁");
        assertEquals("EVAL_RELEASE", jdbc.queryForObject(
                "SELECT SOURCE FROM erp_inv_expiry_lock_log WHERE ITEM_CODE = ? AND BATCH_NO = ?",
                String.class, ITEM, "B-EV-REL"), "变更历史 EVAL_RELEASE");
    }

    @Test
    void freezeBranchClosesImmediately() {
        insertLockedBatch("B-EV-FRZ", "1", "AUTO");
        ExpiryEval e = submit("B-EV-FRZ", "已变质转冻结深检");

        Map<String, Object> out = service.submitConclusion(e.getId(), "FREEZE", null, null);

        assertNotNull(out.get("freezeNo"), "生成冻结单");
        ExpiryEval closed = evalDao.selectById(e.getId());
        assertEquals(ExpiryEval.ST_CLOSED, closed.getStatus(), "即刻 CLOSED");
        assertEquals(out.get("freezeNo"), closed.getFreezeNo());
        String fzStatus = jdbc.queryForObject(
                "SELECT STATUS FROM erp_inv_freeze WHERE FREEZE_NO = ?",
                String.class, String.valueOf(out.get("freezeNo")));
        assertEquals("PENDING", fzStatus, "冻结审批照挂不省");
    }

    @Test
    void selfSignBlockedByApprovalBase() {
        insertLockedBatch("B-EV-SELF", "1", "AUTO");
        ExpiryEval e = submit("B-EV-SELF", "同人拦截测试");
        service.submitConclusion(e.getId(), "RELEASE", LocalDate.now().plusDays(10).toString(), null);
        ExpiryEval pending = evalDao.selectById(e.getId());
        ApprovalTask t = activeTask(pending.getApprId());

        // 发起人（q-eng 也是判定人）虽有 QUALITY_MGR 角色，自签须拦
        login("q-eng", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> engine.pass(t.getId(), "自己签自己"));
        assertEquals(422, ex.getCode());
        assertTrue(ex.getMessage().contains("同人闭环"));
    }

    @Test
    void conclusionRequiresPendingEvalState() {
        insertLockedBatch("B-EV-ST", "1", "AUTO");
        ExpiryEval e = submit("B-EV-ST", "状态校验");
        service.submitConclusion(e.getId(), "FREEZE", null, null);   // → CLOSED
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.submitConclusion(e.getId(), "SCRAP", null, null));
        assertEquals(422, ex.getCode());
    }
}
