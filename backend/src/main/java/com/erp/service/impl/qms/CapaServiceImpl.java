package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.qms.CapaActionDao;
import com.erp.dao.qms.CapaDao;
import com.erp.dao.qms.CapaStepDao;
import com.erp.dao.qms.NcrDao;
import com.erp.entity.qms.Capa;
import com.erp.entity.qms.CapaAction;
import com.erp.entity.qms.CapaStep;
import com.erp.entity.qms.Ncr;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.qms.CapaService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CAPA/8D 实现（spec capa-management，tasks 9.1~9.5）。
 * 触发：Critical NCR 或 90 天同料同供方 ≥3 次不合格 → 立项绑 NCR（BR-4.12-31）；
 * 复发：1 年内同料同供方已关闭 CAPA → 新建关联 + 严重度上调（BR-4.12-36）。
 * 8D：D1~D8 顺序推进；D2 5W2H ≥5 项；D4 根因+证据、浅层根因退回；
 * D6→D7 措施全 DONE；D7 INVALID/PARTIAL → REANALYZING 回 D4（NCR 关闭被 G6 校验阻断）。
 */
@Slf4j
@Service
public class CapaServiceImpl implements CapaService {

    private static final String[] STEPS = {"D1", "D2", "D3", "D4", "D5", "D6", "D7", "D8"};
    private static final String[] STEP_NAMES = {
            "D1 团队组建", "D2 问题描述(5W2H)", "D3 临时遏制措施", "D4 根本原因分析",
            "D5 纠正措施制定", "D6 措施执行", "D7 有效性验证", "D8 关闭与沉淀"};
    private static final String[] W2H_KEYS = {
            "What", "Who", "When", "Where", "Why", "How many", "How",
            "何事", "何人", "何时", "何地", "为何", "为什么", "如何", "多少"};
    private static final String[] SHALLOW_KEYS = {"疏忽", "不小心", "操作失误", "未注意", "大意", "失误"};

    private final CapaDao capaDao;
    private final CapaStepDao stepDao;
    private final CapaActionDao actionDao;
    private final NcrDao ncrDao;
    private final ApprovalEngine approvalEngine;
    /** 站内通知（D7 回炉待办 / 复发回炉待办，rework-capa-evidence） */
    private final com.erp.service.system.NoticeService noticeService;

    public CapaServiceImpl(CapaDao capaDao, CapaStepDao stepDao, CapaActionDao actionDao,
                           NcrDao ncrDao, ApprovalEngine approvalEngine,
                           com.erp.service.system.NoticeService noticeService) {
        this.capaDao = capaDao;
        this.stepDao = stepDao;
        this.actionDao = actionDao;
        this.ncrDao = ncrDao;
        this.approvalEngine = approvalEngine;
        this.noticeService = noticeService;
    }

    // ================= 9.1 触发立项 =================

    @Override
    @Transactional
    public Capa maybeCreateFromNcr(Ncr ncr) {
        if (ncr == null) {
            return null;
        }
        // 幂等：同 NCR 已有 CAPA
        Capa existed = capaDao.selectOne(new LambdaQueryWrapper<Capa>()
                .eq(Capa::getNcrId, ncr.getId())
                .last("LIMIT 1"));
        if (existed != null) {
            return existed;
        }

        // ---- 触发条件（BR-4.12-31）----
        String trigger = null;
        String sourceType = "MANUAL";
        if ("CRITICAL".equals(ncr.getSeverity())) {
            trigger = "重大质量事件（Critical NCR " + ncr.getNcrNo() + "）";
            sourceType = "EVENT";
        } else {
            LocalDateTime since = LocalDateTime.now().minusDays(90);
            long repeat = ncrDao.selectCount(new LambdaQueryWrapper<Ncr>()
                    .eq(hasText(ncr.getSupplierId()), Ncr::getSupplierId, ncr.getSupplierId())
                    .eq(hasText(ncr.getItemCode()), Ncr::getItemCode, ncr.getItemCode())
                    .ge(Ncr::getCreateDate, since)
                    .notIn(Ncr::getStatus, "CANCELLED"));
            if (repeat >= 3) {
                trigger = "3 个月内 " + repeat + " 次重复不合格（BR-4.12-31）";
                sourceType = "REPEAT";
            }
        }
        if (trigger == null) {
            return null; // 未命中不立项
        }

        // ---- 复发判定（BR-4.12-36）：1 年内同料同供方已关闭 CAPA → 关联 + 上调 ----
        Capa origin = findClosedOrigin(ncr);
        String severity = ncr.getSeverity() == null ? "MAJOR" : ncr.getSeverity();
        if (origin != null) {
            severity = uplift(severity);
            trigger += "；复发关联原 CAPA " + origin.getCapaNo() + "，严重度上调至 " + severity
                    + "（BR-4.12-36）";
        }
        return createCapa(ncr, trigger, sourceType, severity, origin == null ? null : origin.getId());
    }

    @Override
    @Transactional
    public Capa createManual(Map<String, Object> body) {
        requireRole("立项 CAPA", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        String title = str(body.get("title"));
        if (!hasText(title)) {
            throw new ServiceException(422, "CAPA 标题必填");
        }
        String ncrId = str(body.get("ncrId"));
        Ncr ncr = hasText(ncrId) ? ncrDao.selectById(ncrId) : null;
        String severity = str(body.get("severity"));
        if (!hasText(severity)) {
            severity = ncr != null && ncr.getSeverity() != null ? ncr.getSeverity() : "MAJOR";
        }
        return createCapa(ncr, str(body.get("trigger")) == null ? "手工立项"
                : str(body.get("trigger")), str(body.get("sourceType")) == null ? "MANUAL"
                : str(body.get("sourceType")), severity, null, title);
    }

    @Override
    @Transactional
    public Capa findByNcr(String ncrId) {
        if (!hasText(ncrId)) {
            return null;
        }
        return capaDao.selectOne(new LambdaQueryWrapper<Capa>()
                .eq(Capa::getNcrId, ncrId)
                .last("LIMIT 1"));
    }

    private Capa createCapa(Ncr ncr, String trigger, String sourceType, String severity, String originId) {
        return createCapa(ncr, trigger, sourceType, severity, originId, null);
    }

    /** titleOverride 非空时用作标题（手工立项用户输入），否则用触发描述 */
    private Capa createCapa(Ncr ncr, String trigger, String sourceType, String severity,
                            String originId, String titleOverride) {
        Capa c = new Capa();
        c.setCapaNo(nextCapaNo());
        if (ncr != null) {
            c.setNcrId(ncr.getId());
            c.setNcrNo(ncr.getNcrNo());
        }
        c.setSourceType(sourceType);
        c.setTitle(hasText(titleOverride) ? titleOverride
                : (hasText(trigger) ? trigger : "CAPA 立项"));
        c.setProblemDesc(ncr == null ? str(trigger) : ncr.getDefectDesc());
        c.setSeverity(severity);
        c.setCurrentStep("D1");
        c.setStatus("OPEN");
        // 立项时限：关联 NCR → 评审时间 + 5 个工作日（跳周末，FR-4.12-5-1 要点①）；
        // 独立来源（投诉/审核/手工）不计时限
        if (ncr != null && ncr.getReviewTime() != null) {
            c.setDueDate(addWorkDays(ncr.getReviewTime().toLocalDate(), 5));
        } else if (ncr != null) {
            c.setDueDate(addWorkDays(LocalDate.now(), 5));
        } else {
            c.setDueDate(null);
        }
        if ("CRITICAL".equals(severity)) {
            c.setContainDueTime(LocalDateTime.now().plusHours(24)); // BR-4.12-32 遏制倒计时
            c.setContainConfirmed("0");
            c.setContainEscalated("0");
        }
        c.setVerifyResult("PENDING");
        c.setOriginCapaId(originId);
        c.setCreateBy(SecurityUtils.getCurrentUserId());
        capaDao.insert(c);

        // 8 步骤（全 PENDING）
        for (int i = 0; i < STEPS.length; i++) {
            CapaStep st = new CapaStep();
            st.setCapaId(c.getId());
            st.setStepCode(STEPS[i]);
            st.setStepName(STEP_NAMES[i]);
            st.setStatus("PENDING");
            st.setCreateBy(SecurityUtils.getCurrentUserId());
            stepDao.insert(st);
        }
        // NCR 回绑（BR-4.12-31：未立项时 NCR 关闭校验不通过——由 G6 findCapa 覆盖）
        if (ncr != null) {
            ncr.setCapaId(c.getId());
            ncrDao.updateById(ncr);
        }
        log.info("CAPA {} created: trigger={}, severity={}, origin={}", c.getCapaNo(), trigger, severity, originId);
        return c;
    }

    /** 起始日 + N 个工作日（周六周日顺延；FR-4.12-5-1 要点①） */
    private LocalDate addWorkDays(LocalDate start, int n) {
        LocalDate d = start;
        int added = 0;
        while (added < n) {
            d = d.plusDays(1);
            if (d.getDayOfWeek().getValue() < 6) {
                added++;
            }
        }
        return d;
    }

    /** 1 年内同物料（或同供方）已关闭 CAPA（复发源） */
    private Capa findClosedOrigin(Ncr ncr) {
        if (ncr == null || !hasText(ncr.getItemCode())) {
            return null;
        }
        // 以 NCR 反查：同 itemCode 的已关闭 CAPA（其关联 NCR 物料相同），1 年内
        List<Capa> closed = capaDao.selectList(new LambdaQueryWrapper<Capa>()
                .eq(Capa::getStatus, "CLOSED")
                .isNotNull(Capa::getNcrId)
                .ge(Capa::getClosedDate, LocalDateTime.now().minusDays(365))
                .orderByDesc(Capa::getClosedDate)
                .last("LIMIT 50"));
        for (Capa c : closed) {
            Ncr originNcr = ncrDao.selectById(c.getNcrId());
            if (originNcr != null && ncr.getItemCode().equals(originNcr.getItemCode())
                    && (ncr.getSupplierId() == null || ncr.getSupplierId().equals(originNcr.getSupplierId()))) {
                return c;
            }
        }
        return null;
    }

    // ================= 9.2~9.5 8D 推进 =================

    @Override
    @Transactional
    public Map<String, Object> advanceStep(String capaId, Map<String, Object> body) {
        requireRole("推进 8D 步骤", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Capa c = require(capaId);
        String stepCode = str(body.get("stepCode"));
        if (!hasText(stepCode)) {
            throw new ServiceException(422, "步骤编号必填（D1~D8）");
        }
        stepCode = stepCode.toUpperCase();
        List<String> valid = List.of(STEPS);
        if (!valid.contains(stepCode)) {
            throw new ServiceException(422, "未知步骤：" + stepCode);
        }
        if ("CLOSED".equals(c.getStatus())) {
            throw new ServiceException(422, "CAPA 已关闭，不可推进");
        }

        // ---- 不可跳序（tasks 9.2）----
        if (!stepCode.equals(c.getCurrentStep())) {
            throw new ServiceException(422, "步骤跳序：当前在 " + c.getCurrentStep()
                    + "，不可直接推进 " + stepCode);
        }
        CapaStep step = requireStep(capaId, stepCode);
        if ("DONE".equals(step.getStatus())) {
            throw new ServiceException(422, "步骤 " + stepCode + " 已完成");
        }
        String output = str(body.get("output"));
        String ownerName = str(body.get("ownerName"));
        if (!hasText(ownerName)) {
            throw new ServiceException(422, "步骤负责人必填");
        }
        if (!hasText(output)) {
            throw new ServiceException(422, "步骤产出物必填");
        }

        // ---- D1 团队成员清单结构化（rework-capa-evidence 2.2，FR-4.12-5-6）----
        List<String> warnings = new ArrayList<>();
        if ("D1".equals(stepCode)) {
            String membersJson = str(body.get("teamMembers"));
            if (!hasText(membersJson) || "[]".equals(membersJson.trim())) {
                throw new ServiceException(422, "D1 团队成员清单必填：至少 1 名成员（姓名/领域/职责），"
                        + "并指定 Leader；不可仅填负责人姓名");
            }
            List<Map<String, Object>> members = parseJsonArray(membersJson);
            if (members.isEmpty()) {
                throw new ServiceException(422, "D1 团队成员清单必填：至少 1 名成员");
            }
            boolean hasLeader = false;
            boolean nonQuality = false;
            for (Map<String, Object> m : members) {
                if (!hasText(str(m.get("name"))) || !hasText(str(m.get("domain")))
                        || !hasText(str(m.get("duty")))) {
                    throw new ServiceException(422, "D1 成员须含姓名、领域与职责（缺项成员："
                            + str(m.get("name")) + "）");
                }
                if (Boolean.TRUE.equals(m.get("leader"))) {
                    hasLeader = true;
                }
                if (!"质量".equals(str(m.get("domain")))) {
                    nonQuality = true;
                }
            }
            if (!hasLeader) {
                throw new ServiceException(422, "D1 须指定 Leader（成员中标记 leader=true）");
            }
            if (!nonQuality || members.size() < 2) {
                warnings.add("团队应包含问题相关各领域代表（质量/技术/生产/采购），当前清单领域较单一（FR-4.12-5-6）");
            }
            c.setTeamMembers(membersJson);
        }

        // ---- D2：5W2H 必填（tasks 9.2）----
        if ("D2".equals(stepCode)) {
            List<String> miss = new ArrayList<>();
            for (String k : W2H_KEYS) {
                if (output.contains(k)) {
                    miss.add(k);
                }
            }
            // 中英任一命中即算（What/何事 成对统计简化：命中 key 种类 ≥5）
            if (miss.size() < 5) {
                throw new ServiceException(422, "D2 问题描述须完整覆盖 5W2H（当前仅命中 "
                        + miss.size() + " 项：" + String.join("、", miss) + "）");
            }
        }

        // ---- D4：根因 + 证据 + 浅层根因退回（tasks 9.3 / BR-4.12-33）----
        if ("D4".equals(stepCode)) {
            String rootCause = str(body.get("rootCause"));
            String evidence = str(body.get("rootCauseEvidence"));
            if (!hasText(rootCause)) {
                rootCause = c.getRootCause();
            }
            if (!hasText(evidence)) {
                evidence = c.getRootCauseEvidence();
            }
            List<String> miss = new ArrayList<>();
            if (!hasText(rootCause)) {
                miss.add("根本原因（5Why/鱼骨图分析过程）");
            }
            if (!hasText(evidence)) {
                miss.add("数据/实验验证证据");
            }
            if (!miss.isEmpty()) {
                throw new ServiceException(422, "根因分析不完整（保留已录入分析过程）：缺 "
                        + String.join("、", miss));
            }
            for (String k : SHALLOW_KEYS) {
                if (rootCause.contains(k) && !rootCause.contains("为什么") && !rootCause.contains("5Why")) {
                    throw new ServiceException(422, "浅层根因退回：「" + k
                            + "」属表象描述，请补 5Why 深挖过程（BR-4.12-33）");
                }
            }
            // 保留分析过程：旧版本追加到 remark
            if (hasText(c.getRootCause()) && !c.getRootCause().equals(rootCause)) {
                c.setRemark((hasText(c.getRemark()) ? c.getRemark() + "\n" : "")
                        + "[" + LocalDateTime.now() + " 分析过程留存] " + c.getRootCause());
            }
            c.setRootCause(rootCause);
            c.setRootCauseEvidence(evidence);
        }

        // ---- 措施校验（tasks 9.4）：完成 D6（措施执行）进入 D7 阶段前，措施须非空且全 DONE ----
        if ("D6".equals(stepCode) || "D7".equals(stepCode)) {
            List<CapaAction> acts = actions(capaId);
            if (acts.isEmpty()) {
                throw new ServiceException(422, "未录入任何纠正/预防措施（D5/D6），不可进入有效性验证");
            }
            List<String> undone = acts.stream()
                    .filter(a -> !"DONE".equals(a.getStatus()))
                    .map(CapaAction::getDescription).toList();
            if (!undone.isEmpty()) {
                throw new ServiceException(422, "措施未完成禁进 D7：" + String.join("、", undone));
            }
        }
        // ---- D7 需验证结论 + 三件套数据支撑（rework-capa-evidence 2.1，FR-4.12-5-4）----
        if ("D7".equals(stepCode)) {
            String verify = str(body.get("verifyResult"));
            if (!hasText(verify) || !List.of("VALID", "PARTIAL", "INVALID").contains(verify)) {
                throw new ServiceException(422, "D7 有效性验证结论必填（VALID / PARTIAL / INVALID）");
            }
            // 三件套必填：验证指标 / 验证目标 / 跟踪数据（措施前值、措施后值、采集周期）
            String indicator = str(body.get("verifyIndicator"));
            String target = str(body.get("verifyTarget"));
            String before = str(body.get("verifyBefore"));
            String after = str(body.get("verifyAfter"));
            String period = str(body.get("verifyPeriod"));
            List<String> miss = new ArrayList<>();
            if (!hasText(indicator)) {
                miss.add("验证指标（如「批次不合格率」）");
            }
            if (!hasText(target)) {
                miss.add("验证目标（如「≤0.5%」）");
            }
            if (!hasText(before) || !hasText(after) || !hasText(period)) {
                miss.add("跟踪数据（措施前值/措施后值/采集周期）");
            }
            if (!miss.isEmpty()) {
                throw new ServiceException(422, "D7 数据支撑不完整（不可仅凭主观判断，FR-4.12-5-4）：缺 "
                        + String.join("、", miss));
            }
            c.setVerifyResult(verify);
            c.setVerifyIndicator(indicator);
            c.setVerifyTarget(target);
            c.setVerifyData("措施前: " + before + " | 措施后: " + after + " | 周期: " + period);

            if (!"VALID".equals(verify)) {
                // ---- 无效/部分有效 → 回 D4 重新分析 + 阻断 NCR 关闭（BR-4.12-35）----
                c.setStatus("REANALYZING");
                c.setCurrentStep("D4");
                if (capaDao.updateById(c) == 0) {
                    throw new ServiceException(409, "CAPA 更新冲突");
                }
                resetStepsFrom(capaId, "D4");
                step.setStatus("PENDING"); // D7 保持待重做
                stepDao.updateById(step);
                log.warn("CAPA {} verify={} → 回 D4 重新分析，关联 NCR 关闭被阻断", c.getCapaNo(), verify);
                // 待办推送 CAPA 负责人与质量经理（BR-4.12-35，降级不回滚）
                try {
                    String conclusion = "PARTIAL".equals(verify) ? "部分有效" : "无效";
                    noticeService.push("ROLE_QUALITY_MGR", null,
                            "CAPA 验证未通过需重新分析：" + c.getCapaNo(),
                            "有效性验证结论「" + conclusion + "」，已回 D4 重新分析并阻断关联 NCR 关闭；"
                                    + "CAPA 负责人：" + (c.getOwnerName() == null ? "-" : c.getOwnerName())
                                    + "；验证指标：" + (c.getVerifyIndicator() == null ? "-" : c.getVerifyIndicator()),
                            "QMS_CAPA_REANALYZE", c.getId());
                } catch (Exception e) {
                    log.warn("CAPA reanalyze notice failed（降级不回滚，capa={}）：{}", c.getCapaNo(), e.getMessage());
                }
                return wrapResult(c, warnings, null);
            }
        }

        // ---- D8 经验沉淀结构化（rework-capa-evidence 2.3，FR-4.12-5-5「不可省略」）----
        if ("D8".equals(stepCode)) {
            String sinkJson = str(body.get("legacySink"));
            if (!hasText(sinkJson) || "[]".equals(sinkJson.trim())) {
                throw new ServiceException(422, "D8 经验沉淀不可省略：至少勾选一类沉淀物"
                        + "（FMEA 更新 / 检验标准修订 / 培训教材 / 防错清单）并填关联说明");
            }
            List<Map<String, Object>> sinks = parseJsonArray(sinkJson);
            if (sinks.isEmpty()) {
                throw new ServiceException(422, "D8 经验沉淀不可省略：至少勾选一类沉淀物");
            }
            boolean stdChange = false;
            for (Map<String, Object> s : sinks) {
                String type = str(s.get("type"));
                if (!List.of("FMEA", "STANDARD", "TRAINING", "POKA_YOKE").contains(type)) {
                    throw new ServiceException(422, "未知沉淀物类型：" + type);
                }
                if (!hasText(str(s.get("note")))) {
                    throw new ServiceException(422, "沉淀物「" + type + "」须填关联说明");
                }
                if ("STANDARD".equals(type)) {
                    stdChange = true;
                }
            }
            // 标准修订沉淀挂既有变更审批（同 BR-4.12-34 口径）：首次提交发起审批并挂起，
            // 审批通过后再次提交方可完成 D8
            if (stdChange) {
                String approvalId = str(body.get("sinkApprovalId"));
                if (!hasText(approvalId)) {
                    List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                            ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "标准变更审批")));
                    var inst = approvalEngine.submit("CapaStandardChange", c.getId(),
                            "经验沉淀-标准修订：" + c.getCapaNo(), "ROLE_QUALITY_DIRECTOR", chain);
                    approvalId = inst.getId();
                    c.setLegacySink(sinkJson);
                    c.setRemark((hasText(c.getRemark()) ? c.getRemark() + "\n" : "")
                            + "[" + LocalDateTime.now() + " 沉淀标准修订挂审批] " + approvalId);
                    capaDao.updateById(c);
                    Map<String, Object> pend = new LinkedHashMap<>();
                    pend.put("capa", c);
                    pend.put("pendingApproval", approvalId);
                    pend.put("message", "已发起标准变更审批，审批通过后再次提交完成 D8（BR-4.12-34）");
                    return pend;
                }
                var inst = approvalEngine.getInstance(approvalId);
                if (inst == null || !"APPROVED".equals(inst.getStatus())) {
                    throw new ServiceException(422, "标准修订沉淀审批未通过，D8 不可完成（BR-4.12-34）");
                }
            }
            c.setLegacySink(sinkJson);
        }

        // ---- 步骤完成与推进 ----
        step.setStatus("DONE");
        step.setOwnerId(SecurityUtils.getCurrentUserId());
        step.setOwnerName(ownerName);
        step.setOutputDesc(output);
        step.setDoneTime(LocalDateTime.now());
        if (stepDao.updateById(step) == 0) {
            throw new ServiceException(409, "步骤更新冲突");
        }
        int idx = valid.indexOf(stepCode);
        if ("D8".equals(stepCode)) {
            // D8 关闭沉淀（tasks 9.5）
            if (!"VALID".equals(c.getVerifyResult())) {
                throw new ServiceException(422, "有效性验证未通过，不可关闭（当前 "
                        + c.getVerifyResult() + "）");
            }
            c.setStatus("CLOSED");
            c.setClosedDate(LocalDateTime.now());
            c.setCurrentStep("D8");
            if (capaDao.updateById(c) == 0) {
                throw new ServiceException(409, "CAPA 更新冲突");
            }
            log.info("CAPA {} closed (8D complete)", c.getCapaNo());
            return wrapResult(c, warnings, null);
        }
        String next = STEPS[idx + 1];
        c.setCurrentStep(next);
        if ("D2".equals(next) || "D3".equals(next) || "D4".equals(next)) {
            c.setStatus("ANALYZING");
        } else if ("D5".equals(next) || "D6".equals(next) || "D7".equals(next)) {
            c.setStatus("VERIFYING");
        }
        if (capaDao.updateById(c) == 0) {
            throw new ServiceException(409, "CAPA 更新冲突");
        }
        log.info("CAPA {} step {} done → {}", c.getCapaNo(), stepCode, next);
        return wrapResult(c, warnings, null);
    }

    /** advanceStep 统一返回：{capa, warnings?, pendingApproval?} */
    private Map<String, Object> wrapResult(Capa c, List<String> warnings, String pending) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capa", c);
        if (warnings != null && !warnings.isEmpty()) {
            out.put("warnings", warnings);
        }
        if (pending != null) {
            out.put("pendingApproval", pending);
        }
        return out;
    }

    /** 解析 JSON 数组字符串为对象列表（字段缺失容忍，坏 JSON 422） */
    private List<Map<String, Object>> parseJsonArray(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            return om.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new ServiceException(422, "结构化清单格式错误（须为 JSON 数组）：" + e.getMessage());
        }
    }

    /** 自 fromStep 起（含）的步骤全部重置 PENDING（验证无效回炉） */
    private void resetStepsFrom(String capaId, String fromStep) {
        boolean hit = false;
        for (String s : STEPS) {
            if (s.equals(fromStep)) {
                hit = true;
            }
            if (!hit) {
                continue;
            }
            CapaStep st = stepDao.selectOne(new LambdaQueryWrapper<CapaStep>()
                    .eq(CapaStep::getCapaId, capaId)
                    .eq(CapaStep::getStepCode, s));
            if (st != null && "DONE".equals(st.getStatus())) {
                st.setStatus("PENDING");
                st.setDoneTime(null);
                st.setOutputDesc(null);
                stepDao.updateById(st);
            }
        }
    }

    // ================= 措施 =================

    @Override
    @Transactional
    public CapaAction addAction(String capaId, Map<String, Object> body) {
        requireRole("录入 CAPA 措施", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Capa c = require(capaId);
        if ("CLOSED".equals(c.getStatus())) {
            throw new ServiceException(422, "CAPA 已关闭");
        }
        String actionType = str(body.get("actionType"));
        if (!hasText(actionType) || !List.of("CORRECT", "PREVENT").contains(actionType)) {
            throw new ServiceException(422, "措施类型必填（CORRECT 纠正 / PREVENT 预防）");
        }
        String desc = str(body.get("description"));
        if (!hasText(desc)) {
            throw new ServiceException(422, "措施描述必填");
        }
        if (!hasText(str(body.get("ownerName")))) {
            throw new ServiceException(422, "措施负责人必填");
        }
        if (!hasText(str(body.get("dueDate")))) {
            throw new ServiceException(422, "完成时间必填");
        }
        if (!hasText(str(body.get("effectDesc")))) {
            throw new ServiceException(422, "预期效果必填");
        }
        CapaAction a = new CapaAction();
        a.setCapaId(capaId);
        a.setActionType(actionType);
        a.setDescription(desc);
        a.setOwnerId(SecurityUtils.getCurrentUserId());
        a.setOwnerName(str(body.get("ownerName")));
        try {
            a.setDueDate(LocalDate.parse(str(body.get("dueDate")).trim()));
        } catch (Exception e) {
            throw new ServiceException(422, "完成时间格式须为 yyyy-MM-dd");
        }
        a.setEffectDesc(str(body.get("effectDesc")));
        String stdChange = str(body.get("standardChangeFlag"));
        a.setStandardChangeFlag("1".equals(stdChange) ? "1" : "0");
        a.setStatus("PENDING");
        a.setCreateBy(SecurityUtils.getCurrentUserId());
        actionDao.insert(a);

        // 标准/SOP 变更 → 走变更审批（BR-4.12-34）
        if ("1".equals(a.getStandardChangeFlag())) {
            List<List<ApprovalNodeSpec>> chain = List.of(List.of(
                    ApprovalNodeSpec.sign("ROLE_QUALITY_MGR", "标准变更审批")));
            var inst = approvalEngine.submit("CapaStandardChange", a.getId(),
                    "标准变更通知单：" + c.getCapaNo() + " / " + desc, "ROLE_QUALITY_DIRECTOR", chain);
            a.setChangeApprovalId(inst.getId());
            actionDao.updateById(a);
        }
        return a;
    }

    @Override
    @Transactional
    public CapaAction completeAction(String actionId, String effectDesc) {
        requireRole("完成 CAPA 措施", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        CapaAction a = actionDao.selectById(actionId);
        if (a == null) {
            throw new ServiceException(404, "措施不存在");
        }
        if ("DONE".equals(a.getStatus())) {
            throw new ServiceException(422, "措施已完成");
        }
        if ("1".equals(a.getStandardChangeFlag())) {
            var inst = hasText(a.getChangeApprovalId())
                    ? approvalEngine.getInstance(a.getChangeApprovalId()) : null;
            if (inst == null || !"APPROVED".equals(inst.getStatus())) {
                throw new ServiceException(422, "标准/SOP 变更审批未通过，措施不可完成（BR-4.12-34）");
            }
        }
        a.setStatus("DONE");
        a.setDoneTime(LocalDateTime.now());
        if (hasText(effectDesc)) {
            a.setEffectDesc(effectDesc);
        }
        if (actionDao.updateById(a) == 0) {
            throw new ServiceException(409, "措施更新冲突");
        }
        return a;
    }

    // ================= 9.1 Critical 遏制倒计时 =================

    @Override
    public int sweepContainment() {
        List<Capa> due = capaDao.selectList(new LambdaQueryWrapper<Capa>()
                .eq(Capa::getContainEscalated, "0")
                .eq(Capa::getContainConfirmed, "0")
                .isNotNull(Capa::getContainDueTime)
                .le(Capa::getContainDueTime, LocalDateTime.now())
                .last("LIMIT 200"));
        for (Capa c : due) {
            c.setContainEscalated("1");
            capaDao.updateById(c);
            log.warn("CAPA {} Critical 遏制 24h 超时 → 升级质量总监（BR-4.12-32），未达标纳入质量月报",
                    c.getCapaNo());
        }
        return due.size();
    }

    // ================= 查询 =================

    // ================= 验证期复发判无效（rework-capa-evidence 3.1） =================

    @Override
    @Transactional
    public int checkVerificationRecurrence(Ncr ncr) {
        if (ncr == null) {
            return 0;
        }
        List<Capa> verifying = capaDao.selectList(new LambdaQueryWrapper<Capa>()
                .eq(Capa::getStatus, "VERIFYING"));
        int fired = 0;
        for (Capa c : verifying) {
            // 维度经关联 NCR 反查（CAPA 表无物料/供应商列，design D2）
            Ncr bound = hasText(c.getNcrId()) ? ncrDao.selectById(c.getNcrId()) : null;
            if (bound == null) {
                continue;
            }
            boolean sameItem = hasText(ncr.getItemCode()) && ncr.getItemCode().equals(bound.getItemCode());
            boolean sameSupplier = hasText(ncr.getSupplierId()) && ncr.getSupplierId().equals(bound.getSupplierId());
            if (!sameItem && !sameSupplier) {
                continue;
            }
            // ---- 命中：自动判无效回 D4（BR-4.12-35 后半段）----
            c.setVerifyResult("INVALID");
            c.setStatus("REANALYZING");
            c.setCurrentStep("D4");
            c.setRemark((hasText(c.getRemark()) ? c.getRemark() + "\n" : "")
                    + "[" + LocalDateTime.now() + " 验证期复发] 新 NCR " + ncr.getNcrNo()
                    + "（物料 " + ncr.getItemCode() + "）同维度再次不合格，验证自动判定无效（FR-4.12-5-4）");
            if (capaDao.updateById(c) == 0) {
                log.warn("CAPA {} recurrence update conflict（跳过）", c.getCapaNo());
                continue;
            }
            resetStepsFrom(c.getId(), "D4");
            CapaStep d7 = stepDao.selectOne(new LambdaQueryWrapper<CapaStep>()
                    .eq(CapaStep::getCapaId, c.getId())
                    .eq(CapaStep::getStepCode, "D7"));
            if (d7 != null && "DONE".equals(d7.getStatus())) {
                d7.setStatus("PENDING");
                d7.setOutputDesc(null);
                stepDao.updateById(d7);
            }
            fired++;
            log.warn("CAPA {} recurrence → INVALID 回 D4（新 NCR {}）", c.getCapaNo(), ncr.getNcrNo());
            try {
                noticeService.push("ROLE_QUALITY_MGR", null,
                        "CAPA 验证期复发已自动回炉：" + c.getCapaNo(),
                        "跟踪期内同维度再次不合格（新 NCR " + ncr.getNcrNo()
                                + "，物料 " + ncr.getItemCode() + "），验证结论自动置无效并回 D4；"
                                + "CAPA 负责人：" + (c.getOwnerName() == null ? "-" : c.getOwnerName()),
                        "QMS_CAPA_RECUR", c.getId());
            } catch (Exception e) {
                log.warn("CAPA recurrence notice failed（降级不回滚，capa={}）：{}", c.getCapaNo(), e.getMessage());
            }
        }
        return fired;
    }

    public Page<Map<String, Object>> page(long current, long size, String keyword, String status, String view) {
        LambdaQueryWrapper<Capa> qw = new LambdaQueryWrapper<Capa>()
                .eq(hasText(status), Capa::getStatus, status)
                .and(hasText(keyword), w -> w.like(Capa::getCapaNo, keyword)
                        .or().like(Capa::getTitle, keyword)
                        .or().like(Capa::getNcrNo, keyword))
                .orderByDesc(Capa::getCreateDate);
        // ---- 三叶视图过滤（rework-capa-evidence 4.1）----
        boolean viewActions = "actions".equals(view);
        boolean viewVerify = "verify".equals(view);
        if (viewActions) {
            // 仅含存在未完成措施的在途 CAPA
            qw.in(Capa::getStatus, List.of("OPEN", "ANALYZING", "VERIFYING"));
            qw.inSql(Capa::getId,
                    "SELECT DISTINCT CAPA_ID FROM erp_qms_capa_action WHERE STATUS <> 'DONE'");
        } else if (viewVerify) {
            // 效果验证叶：验证中 + 回炉重分析
            qw.in(Capa::getStatus, List.of("VERIFYING", "REANALYZING"));
        }
        Page<Capa> raw = capaDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        // 措施聚合：一次 IN 批查防 N+1
        Map<String, int[]> actAgg = new LinkedHashMap<>();
        Map<String, Boolean> actOverdue = new LinkedHashMap<>();
        if (viewActions && !raw.getRecords().isEmpty()) {
            List<String> ids = raw.getRecords().stream().map(Capa::getId).toList();
            List<CapaAction> acts = actionDao.selectList(new LambdaQueryWrapper<CapaAction>()
                    .in(CapaAction::getCapaId, ids));
            for (CapaAction a : acts) {
                int[] cnt = actAgg.computeIfAbsent(a.getCapaId(), k -> new int[2]);
                cnt[1]++;
                if ("DONE".equals(a.getStatus())) {
                    cnt[0]++;
                } else if (a.getDueDate() != null && a.getDueDate().isBefore(LocalDate.now())) {
                    actOverdue.put(a.getCapaId(), true);
                }
            }
        }
        for (Capa c : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("capaNo", c.getCapaNo());
            m.put("title", c.getTitle());
            m.put("ncrNo", c.getNcrNo());
            m.put("severity", c.getSeverity());
            m.put("status", c.getStatus());
            m.put("currentStep", c.getCurrentStep());
            m.put("verifyResult", c.getVerifyResult());
            m.put("verifyIndicator", c.getVerifyIndicator());
            m.put("dueDate", c.getDueDate());
            m.put("overdue", c.getDueDate() != null && c.getDueDate().isBefore(LocalDate.now())
                    && !"CLOSED".equals(c.getStatus()));
            m.put("containEscalated", c.getContainEscalated());
            m.put("containDueTime", c.getContainDueTime());
            m.put("containOverdue", c.getContainDueTime() != null
                    && "0".equals(c.getContainConfirmed()) && c.getContainDueTime().isBefore(now));
            m.put("originCapaId", c.getOriginCapaId());
            m.put("createDate", c.getCreateDate());
            if (viewActions) {
                int[] cnt = actAgg.getOrDefault(c.getId(), new int[]{0, 0});
                m.put("actionsDone", cnt[0]);
                m.put("actionsTotal", cnt[1]);
                m.put("actionOverdue", actOverdue.getOrDefault(c.getId(), false));
            }
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        Capa c = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("capa", c);
        out.put("steps", stepDao.selectList(new LambdaQueryWrapper<CapaStep>()
                .eq(CapaStep::getCapaId, id)));
        out.put("actions", actions(id));
        if (hasText(c.getNcrId())) {
            out.put("ncr", ncrDao.selectById(c.getNcrId()));
        }
        return out;
    }

    @Override
    public List<CapaAction> actions(String capaId) {
        return actionDao.selectList(new LambdaQueryWrapper<CapaAction>()
                .eq(CapaAction::getCapaId, capaId)
                .orderByAsc(CapaAction::getCreateDate));
    }

    // ================= 内部 =================

    private Capa require(String id) {
        Capa c = capaDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "CAPA 不存在：" + id);
        }
        return c;
    }

    private CapaStep requireStep(String capaId, String stepCode) {
        CapaStep st = stepDao.selectOne(new LambdaQueryWrapper<CapaStep>()
                .eq(CapaStep::getCapaId, capaId)
                .eq(CapaStep::getStepCode, stepCode));
        if (st == null) {
            throw new ServiceException(404, "步骤不存在：" + stepCode);
        }
        return st;
    }

    private String uplift(String severity) {
        return switch (severity) {
            case "MINOR" -> "MAJOR";
            case "MAJOR" -> "CRITICAL";
            default -> "CRITICAL";
        };
    }

    private String nextCapaNo() {
        String prefix = "CA" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        Integer max = capaDao.selectMaxSeq(prefix);
        return prefix + String.format("%06d", (max == null ? 0 : max) + 1);
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
