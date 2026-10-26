package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.pm.PmMilestoneDao;
import com.erp.dao.pm.PmMsReviewDao;
import com.erp.dao.pm.PmProjectDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.dao.system.HrSysAttachmentDao;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.pm.PmMilestone;
import com.erp.entity.pm.PmMsReview;
import com.erp.entity.pm.PmProject;
import com.erp.entity.pm.PmWbs;
import com.erp.entity.system.HrSysAttachment;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.pm.PmMilestoneService;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 里程碑管理实现（add-milestone-management；design D2 状态机单点 + D3 审批接线 + D4 扫描 + D5 看板）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmMilestoneServiceImpl implements PmMilestoneService {

    /** 审批 BIZ_TYPE（PmMilestoneApprovalCallback.supportedBizTypes 对齐） */
    public static final String BIZ_MILESTONE = "PmMilestone";

    /** 依赖链遍历上限（design D2：项目内里程碑少，O(n) 可接受，50 防御） */
    private static final int DEP_CHAIN_LIMIT = 50;

    private final PmMilestoneDao msDao;
    private final PmMsReviewDao reviewDao;
    private final PmProjectDao projectDao;
    private final PmWbsDao wbsDao;
    private final HrSysAttachmentDao attachmentDao;
    private final HrRemindDao remindDao;
    private final ApprovalEngine approvalEngine;
    private final NoticeService noticeService;

    // ================================================================
    // 定义（FR-4.8-3-1）
    // ================================================================

    @Override
    public List<Map<String, Object>> list(String projectId) {
        LambdaQueryWrapper<PmMilestone> qw = new LambdaQueryWrapper<PmMilestone>()
                .eq(PmMilestone::getDelFlag, "0")
                .orderByAsc(PmMilestone::getPlanEndDate);
        if (hasText(projectId)) {
            qw.eq(PmMilestone::getProjectId, projectId);
        }
        if (!hasAnyRole("PMO", "ADMIN", "FINANCE", "FIN_MGR")) {
            // PM 数据范围：只见自己作为项目经理的项目下的里程碑
            qw.in(PmMilestone::getProjectId, myProjectIds());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmMilestone m : msDao.selectList(qw)) {
            out.add(toRow(m));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String projectId = str(payload.get("projectId"));
        PmProject project = requireProject(projectId);
        if (!PmProject.ST_ACTIVE.equals(project.getStatus())) {
            throw new ServiceException(422, "仅 ACTIVE 项目可定义里程碑（FR-4.8-3-1）");
        }
        PmMilestone m = new PmMilestone();
        m.setId(uuid());
        m.setProjectId(projectId);
        String wbsId = str(payload.get("wbsId"));
        requirePhaseNode(wbsId, projectId); // level=1 校验
        m.setWbsId(wbsId);
        m.setName(str(payload.get("name")));
        if (!hasText(m.getName())) {
            throw new ServiceException(422, "里程碑名称必填");
        }
        String planEnd = str(payload.get("planEndDate"));
        if (!hasText(planEnd)) {
            throw new ServiceException(422, "计划完成日期必填");
        }
        m.setPlanEndDate(LocalDate.parse(planEnd));
        m.setReviewCriteria(str(payload.get("reviewCriteria")));
        m.setApproverUserId(str(payload.get("approverUserId")));
        m.setApproverName(str(payload.get("approverName")));
        String prevId = str(payload.get("prevMilestoneId"));
        if (hasText(prevId)) {
            requireSameProject(prevId, projectId);
            m.setPrevMilestoneId(prevId);
        }
        m.setStatus(PmMilestone.ST_PLANNED);
        m.setReworkCount(0);
        m.setDelayNotified("0");
        m.setEscalatedNotified("0");
        m.setOverdueReminded("0");
        m.setCreateBy(SecurityUtils.getCurrentUserId());
        m.setCreateDate(LocalDateTime.now());
        msDao.insert(m);
        return toRow(m);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String id, Map<String, Object> payload) {
        PmMilestone m = require(id);
        if (!PmMilestone.ST_PLANNED.equals(m.getStatus())
                && !PmMilestone.ST_REWORK.equals(m.getStatus())) {
            throw new ServiceException(422, "仅 PLANNED/REWORK 状态可修改里程碑");
        }
        if (payload.containsKey("name")) {
            String name = str(payload.get("name"));
            if (!hasText(name)) {
                throw new ServiceException(422, "里程碑名称必填");
            }
            m.setName(name);
        }
        if (payload.containsKey("planEndDate")) {
            String planEnd = str(payload.get("planEndDate"));
            if (!hasText(planEnd)) {
                throw new ServiceException(422, "计划完成日期必填");
            }
            m.setPlanEndDate(LocalDate.parse(planEnd));
        }
        if (payload.containsKey("reviewCriteria")) {
            m.setReviewCriteria(str(payload.get("reviewCriteria")));
        }
        if (payload.containsKey("approverUserId")) {
            m.setApproverUserId(str(payload.get("approverUserId")));
            m.setApproverName(str(payload.get("approverName")));
        }
        if (payload.containsKey("prevMilestoneId")) {
            String prevId = str(payload.get("prevMilestoneId"));
            if (hasText(prevId)) {
                if (prevId.equals(m.getId())) {
                    throw new ServiceException(422, "前序依赖不可指向自身");
                }
                requireSameProject(prevId, m.getProjectId());
                checkNoCycle(m.getId(), prevId);
                m.setPrevMilestoneId(prevId);
            } else {
                m.setPrevMilestoneId(null);
            }
        }
        m.setUpdateBy(SecurityUtils.getCurrentUserId());
        m.setUpdateDate(LocalDateTime.now());
        msDao.updateById(m);
        return toRow(m);
    }

    @Override
    @Transactional
    public void delete(String id) {
        PmMilestone m = require(id);
        if (!PmMilestone.ST_PLANNED.equals(m.getStatus())) {
            throw new ServiceException(422, "仅 PLANNED 里程碑可删除");
        }
        if (countAttachments(id) > 0) {
            throw new ServiceException(422, "已提交交付物的里程碑不可删除");
        }
        if (reviewDao.selectCount(new LambdaQueryWrapper<PmMsReview>()
                .eq(PmMsReview::getMilestoneId, id)
                .eq(PmMsReview::getDelFlag, "0")) > 0) {
            throw new ServiceException(422, "已有评审记录的里程碑不可删除");
        }
        // 被依赖校验：其他里程碑以本里程碑为前序时不可删除
        Long dependents = msDao.selectCount(new LambdaQueryWrapper<PmMilestone>()
                .eq(PmMilestone::getPrevMilestoneId, id)
                .eq(PmMilestone::getDelFlag, "0"));
        if (dependents != null && dependents > 0) {
            throw new ServiceException(422, "存在以其为前序的里程碑，不可删除");
        }
        msDao.deleteById(m.getId());
    }

    // ================================================================
    // 状态机单点（design D2）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> submit(String id) {
        PmMilestone m = require(id);
        // 两道 L1 硬阻断（BR-4.8-16 / FR-4.8-3-2）
        if (countAttachments(id) < 1) {
            throw new ServiceException(422, "无交付物不可发起评审，请先提交交付物（FR-4.8-3-2）");
        }
        if (hasText(m.getPrevMilestoneId())) {
            PmMilestone prev = require(m.getPrevMilestoneId());
            if (!PmMilestone.ST_DONE.equals(prev.getStatus())) {
                throw new ServiceException(422,
                        "前序里程碑未完成：" + prev.getName() + "（BR-4.8-16 L1 硬阻断）");
            }
        }
        transition(m, PmMilestone.ST_SUBMITTING);   // PLANNED/REWORK → SUBMITTING
        transition(m, PmMilestone.ST_REVIEWING);    // SUBMITTING → REVIEWING
        m.setUpdateBy(SecurityUtils.getCurrentUserId());
        m.setUpdateDate(LocalDateTime.now());
        msDao.updateById(m);
        return toRow(m);
    }

    @Override
    @Transactional
    public Map<String, Object> review(String id, Map<String, Object> payload) {
        PmMilestone m = require(id);
        if (!PmMilestone.ST_REVIEWING.equals(m.getStatus())) {
            throw new ServiceException(422, "仅 REVIEWING 状态可评审（当前 " + m.getStatus() + "）");
        }
        String result = str(payload.get("result"));
        String opinion = str(payload.get("opinion"));
        if (!hasText(result)) {
            throw new ServiceException(422, "评审结果必填");
        }
        PmMsReview review = new PmMsReview();
        review.setId(uuid());
        review.setMilestoneId(id);
        review.setResult(result);
        review.setOpinion(opinion);
        switch (result) {
            case PmMsReview.R_PASS -> {
                // PASS：置 APPROVED + 评审通过时间 + 自动发起 L2 审批（BR-4.8-14 不可跳过）
                transition(m, PmMilestone.ST_APPROVED);
                m.setReviewPassAt(LocalDateTime.now());
                m.setOverdueReminded("0"); // 新一轮审批，催办标记复位
                var inst = approvalEngine.submit(
                        BIZ_MILESTONE,
                        m.getId(),
                        "里程碑审批：" + m.getName(),
                        null,
                        List.of(List.of(ApprovalNodeSpec.sign("ROLE_PMO", "PMO 审批"))));
                m.setApprId(inst.getId());
            }
            case PmMsReview.R_CONDITIONAL -> {
                // CONDITIONAL：整改项+期限必填；保持 REVIEWING（整改完成后可重提）
                String item = str(payload.get("rectifyItem"));
                String due = str(payload.get("rectifyDue"));
                if (!hasText(item) || !hasText(due)) {
                    throw new ServiceException(422, "有条件通过必须填写整改项与整改期限");
                }
                review.setRectifyItem(item);
                review.setRectifyDue(LocalDate.parse(due));
            }
            case PmMsReview.R_FAIL -> {
                // FAIL：原因+返工计划必填；REWORK + 计数+1；计数==2 升级 GM
                String plan = str(payload.get("reworkPlan"));
                if (!hasText(plan)) {
                    throw new ServiceException(422, "评审不通过必须填写返工计划");
                }
                review.setReworkPlan(plan);
                transition(m, PmMilestone.ST_REWORK);
                m.setReworkCount((m.getReworkCount() == null ? 0 : m.getReworkCount()) + 1);
                if (m.getReworkCount() == 2) {
                    noticeService.push("ROLE_GM", null,
                            "里程碑返工升级：第 2 次评审不通过",
                            "里程碑「" + m.getName() + "」第 2 次评审不通过，返工计划：" + plan,
                            BIZ_MILESTONE, m.getId());
                    log.info("milestone rework escalated to GM: {} {}", m.getId(), m.getName());
                }
            }
            default -> throw new ServiceException(422, "评审结果取值非法：" + result);
        }
        review.setReviewBy(SecurityUtils.getCurrentUserId());
        review.setReviewDate(LocalDateTime.now());
        review.setCreateBy(SecurityUtils.getCurrentUserId());
        review.setCreateDate(LocalDateTime.now());
        reviewDao.insert(review); // 只增不改，永久保留
        m.setUpdateBy(SecurityUtils.getCurrentUserId());
        m.setUpdateDate(LocalDateTime.now());
        msDao.updateById(m);
        Map<String, Object> out = toRow(m);
        out.put("reviewId", review.getId());
        return out;
    }

    // ================================================================
    // 交付物（版本派生：按上传时间排序，版本=序号；复用附件底座）
    // ================================================================

    @Override
    public List<Map<String, Object>> deliverables(String milestoneId) {
        require(milestoneId);
        List<Map<String, Object>> out = new ArrayList<>();
        int v = 1;
        for (HrSysAttachment a : attachmentDao.selectList(new LambdaQueryWrapper<HrSysAttachment>()
                .eq(HrSysAttachment::getBizType, HrSysAttachment.BIZ_MILESTONE_DELIV)
                .eq(HrSysAttachment::getBizId, milestoneId)
                .orderByAsc(HrSysAttachment::getCreateDate))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", a.getId());
            row.put("version", v++);
            row.put("fileName", a.getFileName());
            row.put("fileSize", a.getFileSize());
            row.put("createBy", a.getCreateBy());
            row.put("createDate", a.getCreateDate());
            out.add(row);
        }
        return out;
    }

    // ================================================================
    // 手工催办
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> remind(String id) {
        PmMilestone m = require(id);
        noticeService.push("ROLE_PMO", null,
                "里程碑催办：" + m.getName(),
                "计划完成日 " + m.getPlanEndDate() + "，当前状态 " + m.getStatus() + "，请跟进。",
                BIZ_MILESTONE + "_REMIND", m.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.getId());
        out.put("reminded", true);
        return out;
    }

    // ================================================================
    // 进度看板（D5：完成率 + 逐里程碑偏差工作日；PM 裁剪/财务只读）
    // ================================================================

    @Override
    public Map<String, Object> progress(String projectId) {
        LambdaQueryWrapper<PmMilestone> qw = new LambdaQueryWrapper<PmMilestone>()
                .eq(PmMilestone::getDelFlag, "0")
                .orderByAsc(PmMilestone::getPlanEndDate);
        if (hasText(projectId)) {
            qw.eq(PmMilestone::getProjectId, projectId);
        }
        if (!hasAnyRole("PMO", "ADMIN", "FINANCE", "FIN_MGR")) {
            qw.in(PmMilestone::getProjectId, myProjectIds());
        }
        List<PmMilestone> all = msDao.selectList(qw);
        int total = all.size();
        int done = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (PmMilestone m : all) {
            if (PmMilestone.ST_DONE.equals(m.getStatus())) {
                done++;
            }
            Map<String, Object> row = toRow(m);
            // 偏差工作日：DONE 用实际完成日，未完成用 today（正数=滞后）
            LocalDate anchor = PmMilestone.ST_DONE.equals(m.getStatus())
                    && m.getActualEndDate() != null ? m.getActualEndDate() : today;
            int deviation = workdaysBetween(m.getPlanEndDate(), anchor);
            row.put("deviationWorkdays", deviation);
            // 超期=偏差为正（DONE 晚完成同样记超期，供结项复盘；扫描只看未完成由 dailyScan 把关）
            row.put("overdue", deviation > 0);
            row.put("l4Flag", !PmPmDone(m) && deviation > 5);
            row.put("escalatedFlag", !PmPmDone(m) && deviation > 15);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("done", done);
        out.put("rate", total == 0 ? BigDecimal100(0) : BigDecimal100(done * 100 / total));
        out.put("rows", rows);
        return out;
    }

    // ================================================================
    // 每日扫描（D4：审批超时 3 工作日 / L4 滞后 >5 / 升级 >15；标记列幂等）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> dailyScan() {
        LocalDate today = LocalDate.now();
        int approveOverdue = 0;
        int delayL4 = 0;
        int escalated = 0;
        for (PmMilestone m : msDao.selectList(new LambdaQueryWrapper<PmMilestone>()
                .eq(PmMilestone::getDelFlag, "0"))) {
            // 1. 审批超时：APPROVED 且评审通过超 3 工作日未审
            if (PmMilestone.ST_APPROVED.equals(m.getStatus())
                    && m.getReviewPassAt() != null
                    && !"1".equals(m.getOverdueReminded())
                    && workdaysBetween(m.getReviewPassAt().toLocalDate(), today) > 3) {
                insertRemind(HrRemind.TYPE_MS_APPROVE_OVERDUE, "ROLE_PMO", m.getId(),
                        "里程碑「" + m.getName() + "」评审通过后超 3 工作日未审批");
                m.setOverdueReminded("1");
                approveOverdue++;
            }
            // 滞后分支：未 DONE 且已过计划完成日
            if (!PmMilestone.ST_DONE.equals(m.getStatus())) {
                int lag = workdaysBetween(m.getPlanEndDate(), today);
                if (lag > 15 && !"1".equals(m.getEscalatedNotified())) {
                    noticeService.push("ROLE_PMO", null,
                            "里程碑滞后升级：" + m.getName(),
                            "已滞后 " + lag + " 个工作日（>15），请升级处理。",
                            BIZ_MILESTONE + "_ESC", m.getId());
                    insertRemind(HrRemind.TYPE_MS_DELAY, "ROLE_PMO", m.getId(),
                            "里程碑「" + m.getName() + "」滞后 " + lag + " 工作日，升级催办");
                    m.setEscalatedNotified("1");
                    m.setDelayNotified("1"); // 升级包含 L4，标记一并置位防重复
                    escalated++;
                } else if (lag > 5 && !"1".equals(m.getDelayNotified())) {
                    noticeService.push("ROLE_PM", null,
                            "里程碑 L4 滞后：" + m.getName(),
                            "已滞后 " + lag + " 个工作日（>5），请协调进度。",
                            BIZ_MILESTONE + "_L4", m.getId());
                    noticeService.push("ROLE_PMO", null,
                            "里程碑 L4 滞后：" + m.getName(),
                            "已滞后 " + lag + " 个工作日（>5），项目经理已通知。",
                            BIZ_MILESTONE + "_L4", m.getId());
                    m.setDelayNotified("1");
                    delayL4++;
                }
            }
            msDao.updateById(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("approveOverdue", approveOverdue);
        out.put("delayL4", delayL4);
        out.put("escalated", escalated);
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 状态转移单点：非法转移 422（D2） */
    private void transition(PmMilestone m, String to) {
        String from = m.getStatus();
        boolean legal =
                (PmMilestone.ST_PLANNED.equals(from) && PmMilestone.ST_SUBMITTING.equals(to))
                || (PmMilestone.ST_REWORK.equals(from) && PmMilestone.ST_SUBMITTING.equals(to))
                || (PmMilestone.ST_SUBMITTING.equals(from) && PmMilestone.ST_REVIEWING.equals(to))
                || (PmMilestone.ST_REVIEWING.equals(from) && PmMilestone.ST_APPROVED.equals(to))
                || (PmMilestone.ST_REVIEWING.equals(from) && PmMilestone.ST_REWORK.equals(to))
                || (PmMilestone.ST_APPROVED.equals(from) && PmMilestone.ST_DONE.equals(to))
                || (PmMilestone.ST_APPROVED.equals(from) && PmMilestone.ST_REWORK.equals(to));
        if (!legal) {
            throw new ServiceException(422, "非法状态转移：" + from + " → " + to);
        }
        m.setStatus(to);
    }

    /** 审批回调专用：APPROVED → DONE（回调内调用，绕过可见性直接转移） */
    public static void markDone(PmMilestone m) {
        if (!PmMilestone.ST_APPROVED.equals(m.getStatus())) {
            throw new ServiceException(422, "仅 APPROVED 里程碑可置完成（当前 " + m.getStatus() + "）");
        }
        m.setStatus(PmMilestone.ST_DONE);
        m.setActualEndDate(LocalDate.now());
    }

    /** 依赖成环校验：沿 prev 链向上遍历（上限 50） */
    private void checkNoCycle(String selfId, String newPrevId) {
        String cursor = newPrevId;
        int steps = 0;
        while (hasText(cursor)) {
            if (cursor.equals(selfId)) {
                throw new ServiceException(422, "前序依赖构成环，禁止保存");
            }
            if (++steps > DEP_CHAIN_LIMIT) {
                throw new ServiceException(422, "依赖链超过 " + DEP_CHAIN_LIMIT + " 层，禁止保存");
            }
            PmMilestone node = msDao.selectById(cursor);
            cursor = node == null ? null : node.getPrevMilestoneId();
        }
    }

    /** WBS 节点必须存在且 level=1（阶段级） */
    private void requirePhaseNode(String wbsId, String projectId) {
        if (!hasText(wbsId)) {
            throw new ServiceException(422, "里程碑须挂载 WBS 阶段节点");
        }
        PmWbs node = wbsDao.selectById(wbsId);
        if (node == null || "1".equals(node.getDelFlag()) || !projectId.equals(node.getProjectId())) {
            throw new ServiceException(422, "WBS 节点不存在或不属于该项目");
        }
        if (node.getLevel() == null || node.getLevel() != PmWbs.LV_PHASE) {
            throw new ServiceException(422, "里程碑只能挂载到阶段级（level=1）节点");
        }
    }

    private void requireSameProject(String milestoneId, String projectId) {
        PmMilestone prev = require(milestoneId);
        if (!projectId.equals(prev.getProjectId())) {
            throw new ServiceException(422, "前序里程碑不属于同一项目");
        }
    }

    private int countAttachments(String milestoneId) {
        Long n = attachmentDao.selectCount(new LambdaQueryWrapper<HrSysAttachment>()
                .eq(HrSysAttachment::getBizType, HrSysAttachment.BIZ_MILESTONE_DELIV)
                .eq(HrSysAttachment::getBizId, milestoneId));
        return n == null ? 0 : n.intValue();
    }

    private void insertRemind(String type, String role, String empIdOrRoleTarget, String detail) {
        HrRemind r = new HrRemind();
        r.setId(uuid());
        r.setRemindType(type);
        r.setEmpId(empIdOrRoleTarget);
        r.setDetail(detail);
        r.setRemindDate(LocalDate.now());
        r.setCreateBy("system");
        r.setCreateDate(LocalDateTime.now());
        remindDao.insert(r);
    }

    /** PM 项目集合（数据范围裁剪） */
    private List<String> myProjectIds() {
        List<String> ids = new ArrayList<>();
        for (PmProject p : projectDao.selectList(new LambdaQueryWrapper<PmProject>()
                .eq(PmProject::getDelFlag, "0")
                .eq(PmProject::getPmUserId, SecurityUtils.getCurrentUserId()))) {
            ids.add(p.getId());
        }
        return ids.isEmpty() ? List.of("__none__") : ids;
    }

    private PmProject requireProject(String projectId) {
        if (!hasText(projectId)) {
            throw new ServiceException(422, "项目 ID 必填");
        }
        PmProject p = projectDao.selectById(projectId);
        if (p == null || "1".equals(p.getDelFlag())) {
            throw new ServiceException(422, "项目不存在");
        }
        return p;
    }

    private PmMilestone require(String id) {
        if (!hasText(id)) {
            throw new ServiceException(422, "里程碑 ID 必填");
        }
        PmMilestone m = msDao.selectById(id);
        if (m == null || "1".equals(m.getDelFlag())) {
            throw new ServiceException(422, "里程碑不存在");
        }
        return m;
    }

    private Map<String, Object> toRow(PmMilestone m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", m.getId());
        row.put("projectId", m.getProjectId());
        row.put("wbsId", m.getWbsId());
        String phaseName = null;
        PmWbs phase = wbsDao.selectById(m.getWbsId());
        if (phase != null) {
            phaseName = phase.getName();
        }
        row.put("phaseName", phaseName);
        row.put("name", m.getName());
        row.put("planEndDate", m.getPlanEndDate() == null ? null : m.getPlanEndDate().toString());
        row.put("reviewCriteria", m.getReviewCriteria());
        row.put("approverUserId", m.getApproverUserId());
        row.put("approverName", m.getApproverName());
        row.put("prevMilestoneId", m.getPrevMilestoneId());
        String prevName = null;
        if (hasText(m.getPrevMilestoneId())) {
            PmMilestone prev = msDao.selectById(m.getPrevMilestoneId());
            if (prev != null) {
                prevName = prev.getName();
            }
        }
        row.put("prevMilestoneName", prevName);
        row.put("status", m.getStatus());
        row.put("reworkCount", m.getReworkCount());
        row.put("reviewPassAt", m.getReviewPassAt() == null ? null : m.getReviewPassAt().toString());
        row.put("actualEndDate", m.getActualEndDate() == null ? null : m.getActualEndDate().toString());
        row.put("delayNotified", m.getDelayNotified());
        row.put("escalatedNotified", m.getEscalatedNotified());
        row.put("overdueReminded", m.getOverdueReminded());
        row.put("apprId", m.getApprId());
        row.put("rejectOpinion", m.getRejectOpinion());
        row.put("deliverableCount", countAttachments(m.getId()));
        return row;
    }

    private static boolean PmPmDone(PmMilestone m) {
        return PmMilestone.ST_DONE.equals(m.getStatus());
    }

    private static java.math.BigDecimal BigDecimal100(int v) {
        return new java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /** 工作日差：from→to 之间的工作日数（跳周六日；不含起点含终点方向无关，取绝对差语义为正数=天数） */
    static int workdaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.equals(to)) {
            return 0;
        }
        boolean neg = to.isBefore(from);
        LocalDate start = neg ? to : from;
        LocalDate end = neg ? from : to;
        int count = 0;
        for (LocalDate d = start.plusDays(1); !d.isAfter(end); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() < 6) {
                count++;
            }
        }
        return neg ? -count : count;
    }

    private boolean hasAnyRole(String... wanted) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            return false;
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        for (String w : wanted) {
            for (String r : roles) {
                if (("ROLE_" + w).equalsIgnoreCase(r)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
