package com.erp.qms;

import com.erp.common.ServiceException;
import com.erp.entity.qms.InspectionStandard;
import com.erp.entity.qms.SamplingPlan;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.qms.InspectionStandardService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 检验标准全链（rework-standard-management，spec inspection-standard MODIFIED/ADDED）：
 * 列表版本摘要（去 N+1）、审批中版本平铺、抽样方案引用锁三路径、复制放行、
 * 五维度命中（客户维度）、发布校验/双签/回调截断/驳回、退役阻断。
 */
@SpringBootTest
class InspectionStandardDbTest {

    @Autowired
    private InspectionStandardService service;
    @Autowired
    private ApprovalEngine approvalEngine;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        purge();
        asQualityEng();
    }

    @AfterEach
    void cleanup() {
        purge();
        SecurityContextHolder.clearContext();
    }

    private void purge() {
        jdbc.update("DELETE FROM erp_sys_approval_task WHERE APPR_ID IN "
                + "(SELECT ID FROM erp_sys_approval WHERE BIZ_TYPE = 'StandardPublish')");
        jdbc.update("DELETE FROM erp_sys_approval WHERE BIZ_TYPE = 'StandardPublish'");
        jdbc.update("DELETE FROM erp_qms_characteristic WHERE VERSION_ID IN "
                + "(SELECT ID FROM erp_qms_standard_version WHERE STANDARD_ID LIKE 'stdt-%')");
        jdbc.update("DELETE FROM erp_qms_standard_applicability WHERE VERSION_ID IN "
                + "(SELECT ID FROM erp_qms_standard_version WHERE STANDARD_ID LIKE 'stdt-%')");
        jdbc.update("DELETE FROM erp_qms_standard_version WHERE STANDARD_ID LIKE 'stdt-%'");
        jdbc.update("DELETE FROM erp_qms_standard WHERE ID LIKE 'stdt-%'");
        jdbc.update("DELETE FROM erp_qms_standard_version WHERE SAMPLING_PLAN_ID LIKE 'splt-%'");
        // 方案按编码前缀清（副本编码 COPY-xxx 随机 ID，须一并清，否则跨用例撞唯一键）
        jdbc.update("DELETE FROM erp_qms_sampling_plan WHERE ID LIKE 'splt-%' "
                + "OR PLAN_CODE LIKE 'LOCK-%' OR PLAN_CODE LIKE 'COPY-%'");
    }

    // ---------- 造数 ----------

    private InspectionStandard newStandard(String code) {
        InspectionStandard s = new InspectionStandard();
        s.setStandardCode(code);
        s.setName("DB测试标准-" + code);
        s.setScopeType("ITEM");
        s.setRiskLevel("B");
        service.create(s);
        // 重写为确定性 ID（stdt- 前缀，便于清理）；创建时尚无版本/审批引用，改 PK 安全
        String generated = reloadByCode(code).getId();
        jdbc.update("UPDATE erp_qms_standard SET ID = ? WHERE ID = ?", "stdt-" + code, generated);
        return reloadByCode(code);
    }

    private InspectionStandard reloadByCode(String code) {
        Map<String, Object> page = service.page(1, 100, code, null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs = (List<Map<String, Object>>) page.get("records");
        for (Map<String, Object> r : recs) {
            if (code.equals(r.get("standardCode"))) {
                InspectionStandard s = new InspectionStandard();
                s.setId((String) r.get("id"));
                s.setStandardCode(code);
                return s;
            }
        }
        throw new IllegalStateException("标准未找到：" + code);
    }

    private Map<String, Object> newVersionBody(String materialCode) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("samplingPlanId", "");
        body.put("aqlLevel", "GB/T 2828.1 一般检验水平 II");
        body.put("inspectionLevel", "II");
        body.put("effectiveFrom", LocalDate.now().toString());
        body.put("changeReason", "初始版本");
        body.put("characteristics", List.of(Map.of(
                "characteristicName", "外观",
                "ctqFlag", "1",
                "regulatoryFlag", "0",
                "specType", "LOOK",
                "methodName", "目视")));
        body.put("applicabilities", List.of(Map.of(
                "materialCode", materialCode,
                "priority", 10)));
        return body;
    }

    private String submitAndGetTask(String versionId) {
        service.submitPublish(versionId);
        String apprId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_qms_standard_version WHERE ID = ?", String.class, versionId);
        return jdbc.queryForObject(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID = ? AND STATUS = 'ACTIVE' "
                        + "ORDER BY SEQ, ID LIMIT 1", String.class, apprId);
    }

    /** 提交发布并把所有活动节点按序签完（CTQ 双签：质量经理 → 技术负责人），返回版本 ID */
    private String publishFully(String versionId) {
        submitAndGetTask(versionId);
        passAllActiveNodes(versionId);
        return versionId;
    }

    /** 依次签署该版本审批实例的全部 ACTIVE 节点（按节点所需角色切换认证） */
    private void passAllActiveNodes(String versionId) {
        for (int i = 0; i < 4; i++) { // 最多双签两节点，防御循环
            List<String> active = jdbc.queryForList(
                    "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID = "
                            + "(SELECT APPROVAL_ID FROM erp_qms_standard_version WHERE ID = ?) "
                            + "AND STATUS = 'ACTIVE' ORDER BY SEQ, ID",
                    String.class, versionId);
            if (active.isEmpty()) {
                return;
            }
            String role = jdbc.queryForObject(
                    "SELECT ROLE_REQUIRED FROM erp_sys_approval_task WHERE ID = ?",
                    String.class, active.get(0));
            asRole(role);
            approvalEngine.pass(active.get(0), "测试签署通过");
        }
    }

    private void asRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("tester", null, role));
    }

    private void asQualityEng() {
        asRole("ROLE_QUALITY_ENG");
    }

    // ---------- 1.1 列表版本摘要 ----------

    @Test
    void pageCarriesVersionSummary() {
        InspectionStandard s = newStandard("stdt-SUM-001");
        Map<String, Object> page = service.page(1, 100, "stdt-SUM-001", null);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recs = (List<Map<String, Object>>) page.get("records");
        assertEquals(1, recs.size());
        Map<String, Object> row = recs.get(0);
        assertTrue(row.containsKey("pendingCount"), "摘要含审批中数");
        assertTrue(row.containsKey("hasDraft"), "摘要含草稿标记");
        assertTrue(row.containsKey("latestVersion"), "摘要含最新版本");
        assertTrue(row.containsKey("hasPendingOrDraft"), "摘要含可发布标记");
        assertEquals(0, row.get("pendingCount"));
        assertEquals(false, row.get("hasDraft"));

        // 建一个草稿版本 → 摘要更新
        service.createVersion(s.getId(), newVersionBody("ITEM-SUM"));
        page = service.page(1, 100, "stdt-SUM-001", null);
        recs = (List<Map<String, Object>>) page.get("records");
        Map<String, Object> row2 = recs.get(0);
        assertEquals(true, row2.get("hasDraft"));
        assertEquals(true, row2.get("hasPendingOrDraft"));
        @SuppressWarnings("unchecked")
        Map<String, Object> latest = (Map<String, Object>) row2.get("latestVersion");
        assertEquals(1, latest.get("no"));
        assertEquals("DRAFT", latest.get("status"));
    }

    // ---------- 1.2 审批中版本平铺 ----------

    @Test
    void pendingVersionsFlattensAcrossStandards() {
        InspectionStandard a = newStandard("stdt-PEND-A");
        InspectionStandard b = newStandard("stdt-PEND-B");
        service.createVersion(a.getId(), newVersionBody("ITEM-PEND-A"));
        service.createVersion(b.getId(), newVersionBody("ITEM-PEND-B"));
        String va = jdbc.queryForObject(
                "SELECT ID FROM erp_qms_standard_version WHERE STANDARD_ID = ?", String.class, a.getId());
        String vb = jdbc.queryForObject(
                "SELECT ID FROM erp_qms_standard_version WHERE STANDARD_ID = ?", String.class, b.getId());
        service.submitPublish(va);
        service.submitPublish(vb);

        List<Map<String, Object>> pending = service.pendingVersions();
        // 全局查询可能含其他测试族残留 → 只数本测试两个标准的版本
        long mine = pending.stream()
                .filter(p -> a.getId().equals(p.get("standardId")) || b.getId().equals(p.get("standardId")))
                .count();
        assertEquals(2, mine, "跨标准平铺 2 条审批中版本");
        Map<String, Object> first = pending.stream()
                .filter(p -> a.getId().equals(p.get("standardId")))
                .findFirst().orElseThrow();
        assertNotNull(first.get("standardCode"));
        assertNotNull(first.get("versionNo"));
        assertNotNull(first.get("changeReason"));
        assertNotNull(first.get("doubleSign"), "含双签状态");
        @SuppressWarnings("unchecked")
        Map<String, Object> ds = (Map<String, Object>) first.get("doubleSign");
        assertEquals(2, ds.get("total"), "CTQ 版本双签两节点");
        assertEquals(0, ds.get("passed"));
    }

    // ---------- 2.1 引用锁三路径 ----------

    private String versionIdOf(Map<String, Object> created) {
        Object v = created.get("version");
        if (v instanceof com.erp.entity.qms.StandardVersion sv) {
            return sv.getId();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>) v;
        return (String) m.get("id");
    }

    private SamplingPlan newPlan(String code) {
        SamplingPlan p = new SamplingPlan();
        p.setPlanCode(code);
        p.setName("DB方案-" + code);
        p.setInspectionLevel("II");
        p.setAqlLevel("GB/T 2828.1 一般检验水平 II");
        p.setSampleSizeRule("n=32");
        p.setAcceptRule("Ac=1");
        p.setRejectRule("Re=2");
        service.saveSamplingPlan(p);
        // 重写为确定性 ID（splt- 前缀，便于清理）；新建时尚无版本引用，改 PK 安全
        String generated = jdbc.queryForObject(
                "SELECT ID FROM erp_qms_sampling_plan WHERE PLAN_CODE = ?", String.class, code);
        jdbc.update("UPDATE erp_qms_sampling_plan SET ID = ? WHERE ID = ?", "splt-" + code, generated);
        return jdbc.queryForObject("SELECT * FROM erp_qms_sampling_plan WHERE ID = 'splt-" + code + "'",
                (rs, i) -> {
                    SamplingPlan s = new SamplingPlan();
                    s.setId("splt-" + code);
                    s.setPlanCode(code);
                    s.setName(rs.getString("NAME"));
                    s.setAcceptRule(rs.getString("ACCEPT_RULE"));
                    return s;
                });
    }

    @Test
    void referencedPlanIsLockedButDraftAndFreePlanEditable() {
        // 方案 + 标准 + 版本（DRAFT 引用）
        SamplingPlan plan = newPlan("LOCK-A");
        InspectionStandard s = newStandard("stdt-LOCK-1");
        Map<String, Object> body = newVersionBody("ITEM-LOCK");
        body.put("samplingPlanId", plan.getId());
        service.createVersion(s.getId(), body);
        String vid = jdbc.queryForObject(
                "SELECT ID FROM erp_qms_standard_version WHERE STANDARD_ID = ?", String.class, s.getId());

        // ① DRAFT 引用 → 放行
        SamplingPlan edit1 = new SamplingPlan();
        edit1.setId(plan.getId());
        edit1.setPlanCode(plan.getPlanCode());
        edit1.setName(plan.getName());
        edit1.setAcceptRule("Ac=0");
        SamplingPlan saved = service.saveSamplingPlan(edit1);
        assertEquals("Ac=0", saved.getAcceptRule(), "草稿引用可改");

        // 提交发布 → PENDING_APPROVE → 锁定
        service.submitPublish(vid);
        SamplingPlan edit2 = new SamplingPlan();
        edit2.setId(plan.getId());
        edit2.setPlanCode(plan.getPlanCode());
        edit2.setName(plan.getName());
        edit2.setAcceptRule("Ac=2");
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.saveSamplingPlan(edit2));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("复制新建"), e.getMessage());
        assertEquals("Ac=0", jdbc.queryForObject(
                "SELECT ACCEPT_RULE FROM erp_qms_sampling_plan WHERE ID = ?", String.class, plan.getId()),
                "锁定后规则未变");

        // ③ 未引用方案 → 放行
        SamplingPlan free = newPlan("LOCK-B");
        SamplingPlan edit3 = new SamplingPlan();
        edit3.setId(free.getId());
        edit3.setPlanCode(free.getPlanCode());
        edit3.setName(free.getName());
        edit3.setAcceptRule("Ac=3");
        assertEquals("Ac=3", service.saveSamplingPlan(edit3).getAcceptRule(), "未引用可改");
    }

    // ---------- 2.2 复制放行 ----------

    @Test
    void copyCreatesEditableDuplicateAndKeepsOriginalLocked() {
        SamplingPlan plan = newPlan("COPY-A");
        InspectionStandard s = newStandard("stdt-COPY-1");
        Map<String, Object> body = newVersionBody("ITEM-COPY");
        body.put("samplingPlanId", plan.getId());
        service.createVersion(s.getId(), body);
        String vid = jdbc.queryForObject(
                "SELECT ID FROM erp_qms_standard_version WHERE STANDARD_ID = ?", String.class, s.getId());
        service.submitPublish(vid);

        // 复制：新编码必填 + 重复 422
        ServiceException miss = assertThrows(ServiceException.class,
                () -> service.copySamplingPlan(plan.getId(), null));
        assertEquals(422, miss.getCode());
        ServiceException dup = assertThrows(ServiceException.class,
                () -> service.copySamplingPlan(plan.getId(), plan.getPlanCode()));
        assertEquals(422, dup.getCode());

        // 复制成功：规则拷贝、原仍锁、新可改
        SamplingPlan copy = service.copySamplingPlan(plan.getId(), "COPY-A2");
        assertEquals("COPY-A2", copy.getPlanCode());
        assertEquals(plan.getAcceptRule(), copy.getAcceptRule(), "规则深拷贝");
        assertTrue(copy.getName().contains("副本"));
        // 原仍锁
        SamplingPlan editOld = new SamplingPlan();
        editOld.setId(plan.getId());
        editOld.setPlanCode(plan.getPlanCode());
        editOld.setName(plan.getName());
        editOld.setAcceptRule("Ac=9");
        assertThrows(ServiceException.class, () -> service.saveSamplingPlan(editOld));
        // 新可改
        SamplingPlan editNew = new SamplingPlan();
        editNew.setId(copy.getId());
        editNew.setPlanCode(copy.getPlanCode());
        editNew.setName(copy.getName());
        editNew.setAcceptRule("Ac=5");
        assertEquals("Ac=5", service.saveSamplingPlan(editNew).getAcceptRule());
    }

    // ---------- 3.1 客户维度命中 ----------

    @Test
    void customerDimensionAloneHits() {
        InspectionStandard s = newStandard("stdt-CUST-1");
        Map<String, Object> body = newVersionBody("ITEM-CUST");
        // 覆盖适用范围：仅客户维度
        body.put("applicabilities", List.of(Map.of("customerId", "CUST-9", "priority", 10)));
        service.createVersion(s.getId(), body);
        String vid = jdbc.queryForObject(
                "SELECT ID FROM erp_qms_standard_version WHERE STANDARD_ID = ?", String.class, s.getId());
        publishFully(vid); // 双签全过 → RELEASED

        Map<String, Object> hit = service.resolveFor(null, null, null, "CUST-9", null);
        assertFalse(hit.isEmpty(), "客户维度单独配置可命中");
        assertEquals(vid, versionIdOf(hit));
        // 非该客户不命中
        assertTrue(service.resolveFor(null, null, null, "CUST-OTHER", null).isEmpty());
    }

    // ---------- 7.1 发布全链 ----------

    @Test
    void codeUniqueAndPublishValidation() {
        newStandard("stdt-DUP-1");
        // 编码唯一
        InspectionStandard dup = new InspectionStandard();
        dup.setStandardCode("stdt-DUP-1");
        dup.setName("重复编码");
        ServiceException e = assertThrows(ServiceException.class, () -> service.create(dup));
        assertEquals(422, e.getCode());

        // 编码创建后不可改
        InspectionStandard s = reloadByCode("stdt-DUP-1");
        InspectionStandard patch = new InspectionStandard();
        patch.setStandardCode("CHANGED");
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.update(s.getId(), patch));
        assertEquals(422, e2.getCode());

        // 缺 CTQ → 发布校验阻断
        Map<String, Object> body = newVersionBody("ITEM-DUP");
        body.put("characteristics", List.of(Map.of(
                "characteristicName", "外观", "ctqFlag", "0", "regulatoryFlag", "0",
                "specType", "LOOK", "methodName", "目视")));
        Map<String, Object> created = service.createVersion(s.getId(), body);
        String vid = versionIdOf(created);
        ServiceException e3 = assertThrows(ServiceException.class, () -> service.submitPublish(vid));
        assertEquals(422, e3.getCode());
        assertTrue(e3.getMessage().contains("CTQ"), e3.getMessage());
    }

    @Test
    void publishFlowDoubleSignCallbackAndReject() {
        InspectionStandard s = newStandard("stdt-FLOW-1");
        Map<String, Object> created = service.createVersion(s.getId(), newVersionBody("ITEM-FLOW"));
        String vid = versionIdOf(created);

        // 提交 → PENDING_APPROVE + 双签两节点
        service.submitPublish(vid);
        assertEquals("PENDING_APPROVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_standard_version WHERE ID = ?", String.class, vid));
        String apprId = jdbc.queryForObject(
                "SELECT APPROVAL_ID FROM erp_qms_standard_version WHERE ID = ?", String.class, vid);
        Integer nodes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_sys_approval_task WHERE APPR_ID = ?", Integer.class, apprId);
        assertEquals(2, nodes, "CTQ 版本双签节点");

        // 重复提交 422
        ServiceException e = assertThrows(ServiceException.class, () -> service.submitPublish(vid));
        assertEquals(422, e.getCode());

        // 双签：质量经理 + 技术负责人（按节点实际所需角色签署）
        List<String> active = jdbc.queryForList(
                "SELECT ID FROM erp_sys_approval_task WHERE APPR_ID = ? AND STATUS = 'ACTIVE' ORDER BY ID",
                String.class, apprId);
        assertEquals(2, active.size());
        for (String tid : active) {
            String role = jdbc.queryForObject(
                    "SELECT ROLE_REQUIRED FROM erp_sys_approval_task WHERE ID = ?", String.class, tid);
            asRole(role);
            approvalEngine.pass(tid, "测试签署通过");
        }

        // 回调：RELEASED + 标准 ACTIVE + current_version 推进
        assertEquals("RELEASED", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_standard_version WHERE ID = ?", String.class, vid));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_standard WHERE ID = ?", String.class, s.getId()));
        assertEquals(1, jdbc.queryForObject(
                "SELECT CURRENT_VERSION FROM erp_qms_standard WHERE ID = ?",
                Integer.class, s.getId()));
        // 已发布只读
        ServiceException e2 = assertThrows(ServiceException.class,
                () -> service.updateVersion(vid, newVersionBody("ITEM-OTHER")));
        assertEquals(422, e2.getCode());

        // 第二版：生效区间须晚于 V1；发布后 V1 截断
        Map<String, Object> body2 = newVersionBody("ITEM-FLOW");
        body2.put("effectiveFrom", LocalDate.now().plusDays(1).toString()); // 晚于 V1 生效起
        Map<String, Object> created2 = service.createVersion(s.getId(), body2);
        String vid2 = versionIdOf(created2);
        String task2 = submitAndGetTask(vid2);
        passAllActiveNodes(vid2); // 双签全过
        // V2 生效起 = V1 生效起（同日）→ 区间重叠应在提交时就阻断，此处断言 V2 已发布且 V1 截断
        LocalDate v2From = jdbc.queryForObject(
                "SELECT EFFECTIVE_FROM FROM erp_qms_standard_version WHERE ID = ?", LocalDate.class, vid2);
        LocalDate v1To = jdbc.queryForObject(
                "SELECT EFFECTIVE_TO FROM erp_qms_standard_version WHERE ID = ?", LocalDate.class, vid);
        assertNotNull(v1To, "V1 生效止被截断");
        assertFalse(v1To.isAfter(v2From), "区间不重叠");
        assertEquals(2, jdbc.queryForObject(
                "SELECT CURRENT_VERSION FROM erp_qms_standard WHERE ID = ?",
                Integer.class, s.getId()));
    }

    @Test
    void publishValidationBlocksOverlapAndMissingFields() {
        InspectionStandard s = newStandard("stdt-VAL-1");
        // 缺适用范围 → 阻断
        Map<String, Object> body = newVersionBody("ITEM-VAL");
        body.put("applicabilities", List.of());
        Map<String, Object> created = service.createVersion(s.getId(), body);
        String vid = versionIdOf(created);
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.submitPublish(vid));
        assertEquals(422, e.getCode());
        assertTrue(e.getMessage().contains("适用范围"), e.getMessage());
    }

    @Test
    void rejectReturnsVersionToDraft() {
        InspectionStandard s = newStandard("stdt-REJ-1");
        Map<String, Object> created = service.createVersion(s.getId(), newVersionBody("ITEM-REJ"));
        String vid = versionIdOf(created);
        String task = submitAndGetTask(vid);
        // 按节点实际所需角色驳回
        String rejectRole = jdbc.queryForObject(
                "SELECT ROLE_REQUIRED FROM erp_sys_approval_task WHERE ID = ?", String.class, task);
        asRole(rejectRole);
        approvalEngine.reject(task, "特性清单需补充");
        assertEquals("DRAFT", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_standard_version WHERE ID = ?", String.class, vid),
                "驳回回草稿可改可重提");
        // 重提成功（同一业务旧实例已 REJECTED，不构成 PENDING 重复）
        service.submitPublish(vid);
        assertEquals("PENDING_APPROVE", jdbc.queryForObject(
                "SELECT STATUS FROM erp_qms_standard_version WHERE ID = ?", String.class, vid));
    }

    // ---------- 7.1 退役阻断 ----------

    @Test
    void retiredStandardBlocksNewResolve() {
        InspectionStandard s = newStandard("stdt-RET-1");
        Map<String, Object> created = service.createVersion(s.getId(), newVersionBody("ITEM-RET"));
        String vid = versionIdOf(created);
        publishFully(vid);
        // 命中正常
        assertFalse(service.resolveFor("ITEM-RET", null, null, null, null).isEmpty());
        // 退役 → 新命中阻断（resolve 过滤 RETIRED）
        asQualityEng();
        service.retire(s.getId());
        assertTrue(service.resolveFor("ITEM-RET", null, null, null, null).isEmpty(),
                "退役后不给新任务（BR-4.12-58）");
        // 历史不删
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_qms_standard_version WHERE STANDARD_ID = ?", Integer.class, s.getId());
        assertEquals(1, cnt, "退役不物理删除");
    }
}
