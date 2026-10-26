package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrChangeLogDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.dao.hr.HrTransferDao;
import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPosition;
import com.erp.entity.hr.HrTransfer;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.hr.HrAccountLinkService;
import com.erp.service.hr.HrTransferService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * 人事变动实现（转正/调岗双人复核 C-4.7-03）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrTransferServiceImpl implements HrTransferService {

    private final HrTransferDao trfDao;
    private final HrEmployeeDao empDao;
    private final HrPositionDao posDao;
    private final com.erp.dao.hr.HrGradeDao gradeDao;
    private final com.erp.dao.hr.HrDeptDao deptDao;
    private final HrChangeLogDao logDao;
    private final ApprovalEngine approvalEngine;
    private final HrAccountLinkService accountLinkService;
    private final com.erp.service.hr.HrSocialChangeService socialChangeService;
    private final com.erp.dao.hr.HrRemindDao remindDao;
    private final com.erp.dao.hr.HrSocialPolicyDao socialPolicyDao;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String BIZ_PROBATION = "HrProbation";
    public static final String BIZ_POST = "HrPostChange";

    /** 双人复核五要素键 */
    private static final List<String> REVIEW_KEYS = List.of("position", "salary", "reportLine", "costCenter", "effectiveDate");

    // ================================================================
    // 3.1 转正
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer submitProbation(Map<String, Object> payload) {
        HrEmployee e = requireEmployee(str(payload.get("empId")));
        if (!HrEmployee.ST_PROBATION.equals(e.getEmpStatus())) {
            throw new ServiceException(422, "非试用期员工不可发起转正（当前：" + e.getEmpStatus() + "）");
        }
        String proposal = str(payload.get("proposal"));
        if (!List.of("REGULAR", "EXTEND", "TERMINATE").contains(proposal)) {
            throw new ServiceException(422, "转正建议非法（REGULAR/EXTEND/TERMINATE）");
        }
        Integer ability = intOrNull(payload.get("abilityScore"));
        Integer attitude = intOrNull(payload.get("attitudeScore"));
        if (ability == null || ability < 1 || ability > 5 || attitude == null || attitude < 1 || attitude > 5) {
            throw new ServiceException(422, "能力/态度评分为 1-5 整数");
        }
        if ("EXTEND".equals(proposal)) {
            Integer months = intOrNull(payload.get("extendMonths"));
            if (months == null || months <= 0) {
                throw new ServiceException(422, "延期须填写延期月数");
            }
            // 法定上限校验（FR-4.7-4-3）：合同期 ≥3 年 → 总试用 ≤6 月；<3 年 → ≤2 月
            validateProbationLimit(e, months);
        }
        HrTransfer t = new HrTransfer();
        t.setId(uuid());
        t.setTrfNo(nextNo("TRF", trfDao::selectNosByPrefix));
        t.setType(HrTransfer.TYPE_PROBATION);
        t.setEmpId(e.getId());
        t.setEmpName(e.getName());
        t.setAbilityScore(ability);
        t.setAttitudeScore(attitude);
        t.setAttitudeNote(str(payload.get("attitudeNote")));
        t.setProposal(proposal);
        t.setExtendMonths(intOrNull(payload.get("extendMonths")));
        // 调薪后月薪（可选，审批过写回主档 + change_log，FR-4.7-4-4）
        java.math.BigDecimal newSalary = dec(payload.get("newSalary"));
        if (newSalary != null && newSalary.signum() <= 0) {
            throw new ServiceException(422, "调薪后月薪须为正数");
        }
        t.setNewSalary(newSalary);
        t.setReason(str(payload.get("reason")));
        t.setStatus(HrTransfer.ST_DRAFT);
        t.setLinkPending("0");
        t.setCreateBy(currentUser());
        trfDao.insert(t);
        log.info("probation submit: {} {} {}", t.getTrfNo(), e.getName(), proposal);
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer hrReviewProbation(String trfId, String opinion) {
        HrTransfer t = requireTransfer(trfId);
        if (!HrTransfer.TYPE_PROBATION.equals(t.getType())) {
            throw new ServiceException(422, "非转正单");
        }
        if (!HrTransfer.ST_DRAFT.equals(t.getStatus())) {
            throw new ServiceException(422, "仅草稿态可 HR 审核（当前：" + t.getStatus() + "）");
        }
        HrEmployee e = requireEmployee(t.getEmpId());
        if ("EXTEND".equals(t.getProposal())) {
            validateProbationLimit(e, t.getExtendMonths());
        }
        // 提交审批：HR 专员 → HR 经理
        var inst = approvalEngine.submit(BIZ_PROBATION, t.getId(),
                "试用期转正审批：" + t.getEmpName() + "（" + t.getProposal() + "）", "ROLE_HR_MGR",
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_HR", "HR 专员初审")),
                        List.of(ApprovalNodeSpec.sign("ROLE_HR_MGR", "HR 经理终审"))));
        t.setApprId(inst.getId());
        t.setStatus(HrTransfer.ST_APPROVING);
        t.setUpdateBy(currentUser());
        t.setUpdateDate(LocalDateTime.now());
        trfDao.updateById(t);
        return t;
    }

    // ================================================================
    // 3.2 调岗
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer submitPostChange(Map<String, Object> payload) {
        HrEmployee e = requireEmployee(str(payload.get("empId")));
        if (HrEmployee.ST_RESIGNED.equals(e.getEmpStatus())) {
            throw new ServiceException(422, "已离职员工不可调岗");
        }
        String toPosId = str(payload.get("toPosId"));
        require(toPosId, "调入岗位");
        HrPosition toPos = posDao.selectById(toPosId);
        if (toPos == null || !"1".equals(toPos.getStatus())) {
            throw new ServiceException(422, "调入岗位不存在或已停用");
        }
        // 满编 L1（HEADCOUNT 校验）
        if (toPos.getHeadcount() != null) {
            long incumbents = empDao.selectCount(new LambdaQueryWrapper<HrEmployee>()
                    .eq(HrEmployee::getDelFlag, "0")
                    .eq(HrEmployee::getPosId, toPosId)
                    .ne(HrEmployee::getEmpStatus, HrEmployee.ST_RESIGNED));
            if (incumbents >= toPos.getHeadcount()) {
                throw new ServiceException(422, "调入岗位已满编（编制 " + toPos.getHeadcount()
                        + "，在职 " + incumbents + "）");
            }
        }
        LocalDate effective = date(payload.get("effectiveDate"));
        if (effective == null) {
            throw new ServiceException(422, "拟生效日期必填");
        }
        HrTransfer t = new HrTransfer();
        t.setId(uuid());
        t.setTrfNo(nextNo("TRF", trfDao::selectNosByPrefix));
        t.setType(HrTransfer.TYPE_POST);
        t.setEmpId(e.getId());
        t.setEmpName(e.getName());
        t.setFromDeptId(e.getDeptId());
        t.setFromPosId(e.getPosId());
        t.setToDeptId(str(payload.get("toDeptId")) != null ? str(payload.get("toDeptId")) : toPos.getDeptId());
        t.setToPosId(toPosId);
        t.setSalaryAdvice(str(payload.get("salaryAdvice")));
        // 缝合 add-org-management D4：带宽校验链（岗位→职级→带宽），超限标记 L2
        applyBandCheck(t, toPos);
        t.setEffectiveDate(effective);
        t.setReason(str(payload.get("reason")));
        t.setStatus(HrTransfer.ST_DRAFT);
        t.setLinkPending("0");
        t.setCreateBy(currentUser());
        trfDao.insert(t);
        log.info("post change submit: {} {} -> {}", t.getTrfNo(), e.getName(), toPos.getName());
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer confirmDeptOut(String trfId, String opinion) {
        HrTransfer t = requirePost(trfId, HrTransfer.ST_DRAFT);
        require(opinion, "调出确认意见");
        requireDeptManager(t.getFromDeptId(), "调出");
        t.setStatus(HrTransfer.ST_DEPT_OUT_CONFIRMED);
        touch(t);
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer confirmDeptIn(String trfId, String opinion) {
        HrTransfer t = requirePost(trfId, HrTransfer.ST_DEPT_OUT_CONFIRMED);
        require(opinion, "调入确认意见");
        requireDeptManager(t.getToDeptId(), "调入");
        t.setStatus(HrTransfer.ST_DEPT_IN_CONFIRMED);
        touch(t);
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer hrReviewPost(String trfId, String opinion) {
        HrTransfer t = requirePost(trfId, HrTransfer.ST_DEPT_IN_CONFIRMED);
        t.setStatus(HrTransfer.ST_HR_REVIEWED);
        touch(t);
        // 进入双人复核
        t.setStatus(HrTransfer.ST_REVIEWING);
        trfDao.updateById(t);
        log.info("post change into double review: {}", t.getTrfNo());
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer signReview(String trfId, Map<String, Object> payload) {
        HrTransfer t = requirePost(trfId, HrTransfer.ST_REVIEWING);
        String user = currentUser();
        // 五要素逐项确认 JSON
        Map<String, Object> elements = new LinkedHashMap<>();
        for (String key : REVIEW_KEYS) {
            elements.put(key, payload.get(key));
        }
        if (t.getReview1By() == null) {
            t.setReview1By(user);
            t.setReview1At(LocalDateTime.now());
            t.setReview1Json(json(elements));
        } else if (t.getReview2By() == null) {
            if (user.equals(t.getReview1By())) {
                throw new ServiceException(422, "C-4.7-03 双人复核须两人独立确认，同一人不可双签");
            }
            t.setReview2By(user);
            t.setReview2At(LocalDateTime.now());
            t.setReview2Json(json(elements));
        } else {
            throw new ServiceException(422, "双人复核已完成");
        }
        // 两人齐 → 比对
        if (t.getReview1By() != null && t.getReview2By() != null) {
            Map<String, Object> r1 = parse(t.getReview1Json());
            Map<String, Object> r2 = parse(t.getReview2Json());
            List<String> diffs = new ArrayList<>();
            for (String key : REVIEW_KEYS) {
                String v1 = String.valueOf(r1.getOrDefault(key, ""));
                String v2 = String.valueOf(r2.getOrDefault(key, ""));
                if (!v1.equals(v2)) {
                    diffs.add(key);
                }
            }
            if (!diffs.isEmpty()) {
                t.setStatus(HrTransfer.ST_REVIEW_DIFF);
                t.setReviewDiffJson(json(Map.of("diffs", diffs,
                        "review1", t.getReview1By(), "review2", t.getReview2By())));
                touch(t);
                log.warn("double review DIFF on {}: {}", t.getTrfNo(), diffs);
                return t;
            }
            // 一致 → 提交审批（HR 经理；超带宽追加 L2 复核节点 FR-4.7-4-5）
            var inst = approvalEngine.submit(BIZ_POST, t.getId(),
                    "调岗审批：" + t.getEmpName() + "（双人复核一致）", "ROLE_HR_MGR", postApprovalGroups(t));
            t.setApprId(inst.getId());
            t.setStatus(HrTransfer.ST_APPROVING);
        }
        touch(t);
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer adjudicateDiff(String trfId, String decision, String note) {
        HrTransfer t = requirePost(trfId, HrTransfer.ST_REVIEW_DIFF);
        if (!"APPROVE".equals(decision)) {
            t.setStatus(HrTransfer.ST_REJECTED);
            touch(t);
            return t;
        }
        require(note, "裁决说明");
        // 裁决通过 → 重置复核位允许重新比对流转（以裁决意见为准直接提交审批；超带宽同样追加 L2 节点）
        var inst = approvalEngine.submit(BIZ_POST, t.getId(),
                "调岗审批：" + t.getEmpName() + "（复核差异已裁决）", "ROLE_HR_MGR", postApprovalGroups(t));
        t.setApprId(inst.getId());
        t.setStatus(HrTransfer.ST_APPROVING);
        t.setReviewDiffJson(t.getReviewDiffJson() + "|ADJUDICATED:" + note);
        touch(t);
        return t;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer effect(String trfId) {
        HrTransfer t = trfDao.selectById(trfId);
        if (t == null) {
            throw new ServiceException(404, "变动单不存在：" + trfId);
        }
        if (HrTransfer.ST_EFFECTED.equals(t.getStatus())) {
            return t; // 幂等
        }
        HrEmployee e = empDao.selectForUpdate(t.getEmpId());
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在：" + t.getEmpId());
        }
        if (HrTransfer.TYPE_POST.equals(t.getType())) {
            // 更新主档归属（历史留痕：变更日志）
            logChange(e, "所属部门", e.getDeptId(), t.getToDeptId(), "调岗生效 " + t.getTrfNo());
            logChange(e, "岗位", e.getPosId(), t.getToPosId(), "调岗生效 " + t.getTrfNo());
            String oldPos = e.getPosId();
            e.setDeptId(t.getToDeptId());
            e.setPosId(t.getToPosId());
            e.setUpdateBy("transfer");
            e.setUpdateDate(LocalDateTime.now());
            empDao.updateById(e);
            // 账号角色换绑（失败降级不回滚，D3 触点②）
            try {
                accountLinkService.swapRoles(e.getId(), oldPos, t.getToPosId());
            } catch (Exception ex) {
                log.warn("role swap failed for {}: {}", e.getEmpNo(), ex.getMessage());
                t.setLinkPending("1");
                t.setLinkNote("角色换绑失败：" + ex.getMessage());
            }
            // social-insurance-ops 触点③：勾选变更缴纳地 → 更新 SOCIAL_PLACE + 自动开转移单 + 转移提醒
            if ("1".equals(t.getPlaceChange()) && t.getNewPlace() != null && !t.getNewPlace().isBlank()) {
                logChange(e, "社保缴纳地", e.getSocialPlace(), t.getNewPlace(), "调岗生效 " + t.getTrfNo());
                e.setSocialPlace(t.getNewPlace());
                empDao.updateById(e);
                try {
                    socialChangeService.autoOpen(e.getId(), com.erp.entity.hr.HrSocialChange.TYPE_TRANSFER,
                            com.erp.entity.hr.HrSocialChange.SRC_TRANSFER, t.getTrfNo(),
                            t.getNewPlace(), t.getEffectiveDate());
                    com.erp.entity.hr.HrRemind r = new com.erp.entity.hr.HrRemind();
                    r.setId(uuid());
                    r.setRemindType(com.erp.entity.hr.HrRemind.TYPE_SOCIAL_TRANSFER);
                    r.setEmpId(e.getId());
                    r.setEmpName(e.getName());
                    r.setRemindDate(LocalDate.now());
                    r.setDetail("调岗 " + t.getTrfNo() + " 生效，缴纳地变更为 " + t.getNewPlace()
                            + "，请办理社保转移");
                    r.setCreateBy("system");
                    remindDao.insert(r);
                } catch (Exception ex) {
                    log.warn("social TRANSFER open/remind failed for {}: {}", e.getEmpNo(), ex.getMessage());
                    t.setLinkPending("1");
                    t.setLinkNote((t.getLinkNote() == null ? "" : t.getLinkNote() + "；")
                            + "社保转移单开立失败：" + ex.getMessage());
                }
            }
        } else if (HrTransfer.TYPE_PROBATION.equals(t.getType())) {
            applyProbationResult(e, t);
        }
        t.setStatus(HrTransfer.ST_EFFECTED);
        touch(t);
        log.info("transfer effected: {} {}", t.getTrfNo(), t.getType());
        return t;
    }

    /** 转正审批通过执行（回调调用） */
    @Transactional(rollbackFor = Exception.class)
    public void effectProbation(String trfId) {
        HrTransfer t = trfDao.selectById(trfId);
        if (t == null || HrTransfer.ST_EFFECTED.equals(t.getStatus())) {
            return;
        }
        HrEmployee e = empDao.selectForUpdate(t.getEmpId());
        if (e == null) {
            return;
        }
        applyProbationResult(e, t);
        t.setStatus(HrTransfer.ST_EFFECTED);
        touch(t);
        log.info("probation effected: {} {} -> {}", t.getTrfNo(), e.getName(), e.getEmpStatus());
    }

    /** 调岗审批通过执行（回调调用） */
    @Transactional(rollbackFor = Exception.class)
    public void effectPost(String trfId) {
        effect(trfId);
    }

    private void applyProbationResult(HrEmployee e, HrTransfer t) {
        switch (t.getProposal() == null ? "REGULAR" : t.getProposal()) {
            case "REGULAR" -> {
                logChange(e, "员工状态", e.getEmpStatus(), HrEmployee.ST_REGULAR, "按期转正 " + t.getTrfNo());
                e.setEmpStatus(HrEmployee.ST_REGULAR);
                // 转正调薪联动（FR-4.7-4-4）：调薪后月薪已填 → 写回主档 + 变更日志
                if (t.getNewSalary() != null) {
                    logChange(e, "月薪",
                            e.getBaseSalary() == null ? null : e.getBaseSalary().stripTrailingZeros().toPlainString(),
                            t.getNewSalary().stripTrailingZeros().toPlainString(),
                            "转正调薪 " + t.getTrfNo());
                    e.setBaseSalary(t.getNewSalary());
                }
            }
            case "EXTEND" -> {
                var newEnd = e.getProbationEnd() == null ? LocalDate.now().plusMonths(t.getExtendMonths() == null ? 3 : t.getExtendMonths())
                        : e.getProbationEnd().plusMonths(t.getExtendMonths() == null ? 0 : t.getExtendMonths());
                logChange(e, "试用期截止", String.valueOf(e.getProbationEnd()), String.valueOf(newEnd),
                        "试用期延期 " + t.getTrfNo());
                e.setProbationEnd(newEnd);
            }
            case "TERMINATE" -> {
                logChange(e, "员工状态", e.getEmpStatus(), HrEmployee.ST_RESIGNED, "试用期终止 " + t.getTrfNo());
                e.setEmpStatus(HrEmployee.ST_RESIGNED);
                e.setResignedDate(LocalDate.now());
            }
            default -> throw new ServiceException(422, "非法转正建议");
        }
        e.setUpdateBy("transfer");
        e.setUpdateDate(LocalDateTime.now());
        empDao.updateById(e);
    }

    /** 超期未评价自动按期转正（每日扫描调用，留痕「系统默认转正」） */
    @Transactional(rollbackFor = Exception.class)
    public void autoRegularize(HrEmployee e) {
        HrTransfer t = new HrTransfer();
        t.setId(uuid());
        t.setTrfNo(nextNo("TRF", trfDao::selectNosByPrefix));
        t.setType(HrTransfer.TYPE_PROBATION);
        t.setEmpId(e.getId());
        t.setEmpName(e.getName());
        t.setProposal("REGULAR");
        t.setReason("系统默认转正（超期未评价）");
        t.setStatus(HrTransfer.ST_AUTO_REGULAR);
        t.setLinkPending("0");
        t.setCreateBy("system");
        trfDao.insert(t);
        applyProbationResult(e, t);
        t.setStatus(HrTransfer.ST_EFFECTED);
        touch(t);
        log.info("auto regularize: {} {}", e.getEmpNo(), e.getName());
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public List<HrTransfer> list(String type, String status) {
        LambdaQueryWrapper<HrTransfer> qw = new LambdaQueryWrapper<HrTransfer>()
                .eq(HrTransfer::getDelFlag, "0").orderByDesc(HrTransfer::getCreateDate);
        if (type != null && !type.isBlank()) {
            qw.eq(HrTransfer::getType, type);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrTransfer::getStatus, status);
        }
        return trfDao.selectList(qw);
    }

    @Override
    public Map<String, Object> detail(String trfId) {
        HrTransfer t = requireTransfer(trfId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transfer", t);
        out.put("employee", empDao.selectById(t.getEmpId()));
        return out;
    }

    // ================================================================
    // 内部
    // ================================================================

    private void validateProbationLimit(HrEmployee e, Integer extendMonths) {
        if (e.getContractStart() == null || e.getContractEnd() == null) {
            return; // 无合同信息不校验
        }
        long contractMonths = java.time.temporal.ChronoUnit.MONTHS.between(e.getContractStart(), e.getContractEnd());
        long originalProbation = 0;
        if (e.getHireDate() != null && e.getProbationEnd() != null) {
            originalProbation = java.time.temporal.ChronoUnit.MONTHS.between(e.getHireDate(), e.getProbationEnd());
        }
        long total = originalProbation + (extendMonths == null ? 0 : extendMonths);
        int legalMax = contractMonths >= 36 ? 6 : 2;
        if (total > legalMax) {
            throw new ServiceException(422, "试用期超法定上限：合同期 " + contractMonths + " 个月，总试用 "
                    + total + " 个月 > 上限 " + legalMax + " 个月（FR-4.7-4-3 L1）");
        }
    }

    /** 缝合 add-org-management D4：带宽校验链（岗位→职级→带宽）；超限标记 L2，未关联职级跳过标注。 */
    /**
     * 调岗审批节点组：常规 HR 经理节点；带宽超限（BAND_EXCEEDED=1 超上限 / =0 低于下限）
     * 自动追加第二个 HR 经理节点作 L2 复核（FR-4.7-4-5「薪酬超出带宽 → L2 审批」）。
     */
    private List<List<ApprovalNodeSpec>> postApprovalGroups(HrTransfer t) {
        List<List<ApprovalNodeSpec>> groups = new ArrayList<>();
        groups.add(List.of(ApprovalNodeSpec.sign("ROLE_HR_MGR", "HR 经理审批调岗")));
        if (t.getBandExceeded() != null) {
            groups.add(List.of(ApprovalNodeSpec.sign("ROLE_HR_MGR",
                    "带宽超限 L2 复核（" + (t.getBandNote() == null ? "超带宽" : t.getBandNote()) + "）")));
        }
        return groups;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrTransfer updatePlaceChange(String trfId, String placeChange, String newPlace) {
        HrTransfer t = requirePost(trfId, null);
        if (HrTransfer.ST_EFFECTED.equals(t.getStatus()) || HrTransfer.ST_APPROVING.equals(t.getStatus())) {
            throw new ServiceException(422, "审批中/已生效不可变更缴纳地设置（当前：" + t.getStatus() + "）");
        }
        if ("1".equals(placeChange)) {
            if (newPlace == null || newPlace.isBlank()) {
                throw new ServiceException(422, "勾选变更缴纳地须选择新缴纳地");
            }
            // 值域校验：新缴纳地须已维护社保政策（数据源=政策表城市）
            Long cityOk = socialPolicyDao.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                    .LambdaQueryWrapper<com.erp.entity.hr.HrSocialPolicy>()
                    .eq(com.erp.entity.hr.HrSocialPolicy::getCity, newPlace)
                    .eq(com.erp.entity.hr.HrSocialPolicy::getDelFlag, "0"));
            if (cityOk == null || cityOk == 0) {
                throw new ServiceException(422, "新缴纳地「" + newPlace + "」未维护社保政策，请先在社保政策表维护");
            }
            t.setPlaceChange("1");
            t.setNewPlace(newPlace);
        } else {
            t.setPlaceChange("0");
            t.setNewPlace(null);
        }
        t.setUpdateBy(currentUser());
        t.setUpdateDate(LocalDateTime.now());
        trfDao.updateById(t);
        return t;
    }

    private void applyBandCheck(HrTransfer t, HrPosition toPos) {
        if (t.getSalaryAdvice() == null || t.getSalaryAdvice().isBlank()) {
            return; // 无薪酬建议不校验
        }
        java.math.BigDecimal advice;
        try {
            advice = new java.math.BigDecimal(t.getSalaryAdvice().trim());
        } catch (Exception ex) {
            return; // 非金额不阻断（格式由录入端控）
        }
        if (toPos.getGradeId() == null || toPos.getGradeId().isBlank()) {
            t.setBandNote("调入岗位未关联职级（带宽跳过）");
            return;
        }
        var g = gradeDao.selectById(toPos.getGradeId());
        if (g == null) {
            t.setBandNote("关联职级不存在（带宽跳过）");
            return;
        }
        if (g.getSalMax() != null && advice.compareTo(g.getSalMax()) > 0) {
            t.setBandExceeded("1");
            t.setBandNote("薪酬建议 " + advice.stripTrailingZeros().toPlainString() + " 超职级 "
                    + g.getGradeNo() + " 上限 " + g.getSalMax().stripTrailingZeros().toPlainString()
                    + "（FR-4.7-4-5 L2）");
        } else if (g.getSalMin() != null && advice.compareTo(g.getSalMin()) < 0) {
            t.setBandExceeded("0");
            t.setBandNote("薪酬建议 " + advice.stripTrailingZeros().toPlainString() + " 低于职级 "
                    + g.getGradeNo() + " 下限 " + g.getSalMin().stripTrailingZeros().toPlainString()
                    + "（FR-4.7-4-5 L2）");
        }
    }

    /**
     * 缝合 add-org-management D5：双部门确认须为部门主管本人（或 HR 经理/管理员代确认留痕）。
     * 部门无主管时放行并标注「无主管，HR 代确认」（存量部门大多无主管，硬校验会卡死流程）。
     */
    private void requireDeptManager(String deptId, String label) {
        if (deptId == null) {
            return;
        }
        var dept = deptDao.selectById(deptId);
        if (dept == null || dept.getManagerEmpId() == null || dept.getManagerEmpId().isBlank()) {
            log.info("{}确认：部门 {} 无主管，HR 代确认（D5 放行标注）", label, deptId);
            return;
        }
        var mgr = empDao.selectById(dept.getManagerEmpId());
        String user = currentUser();
        if (mgr != null && user != null && user.equals(mgr.getEmpNo())) {
            return; // 主管本人
        }
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.getAuthorities() != null && auth.getAuthorities().stream().anyMatch(
                a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_HR_MGR".equals(a.getAuthority()))) {
            log.info("{}确认：非主管本人，由 HR 经理/管理员代确认（留痕）", label);
            return;
        }
        throw new ServiceException(422, label + "确认须由部门主管本人操作"
                + (mgr == null ? "" : "（主管：" + mgr.getName() + "）"));
    }

    private HrTransfer requirePost(String trfId, String expectStatus) {
        HrTransfer t = requireTransfer(trfId);
        if (!HrTransfer.TYPE_POST.equals(t.getType())) {
            throw new ServiceException(422, "非调岗单");
        }
        if (expectStatus != null && !expectStatus.equals(t.getStatus())) {
            throw new ServiceException(422, "状态不符（期望 " + expectStatus + "，当前 " + t.getStatus() + "）");
        }
        return t;
    }

    private void logChange(HrEmployee e, String field, String oldV, String newV, String reason) {
        HrChangeLog l = new HrChangeLog();
        l.setId(uuid());
        l.setEmpId(e.getId());
        l.setFieldName(field);
        l.setOldValue(oldV);
        l.setNewValue(newV);
        l.setReason(reason);
        l.setChangeBy(currentUser());
        l.setChangeAt(LocalDateTime.now());
        logDao.insert(l);
    }

    private void touch(HrTransfer t) {
        t.setUpdateBy(currentUser());
        t.setUpdateDate(LocalDateTime.now());
        trfDao.updateById(t);
    }

    private String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(500, "序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String jsonStr) {
        try {
            return MAPPER.readValue(jsonStr,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private HrTransfer requireTransfer(String id) {
        HrTransfer t = trfDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "变动单不存在：" + id);
        }
        return t;
    }

    private HrEmployee requireEmployee(String id) {
        HrEmployee e = empDao.selectById(id);
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在：" + id);
        }
        return e;
    }

    private String nextNo(String prefix, java.util.function.Function<String, List<String>> nosFn) {
        List<String> existing = nosFn.apply(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                String tail = no.length() > prefix.length() ? no.substring(prefix.length()) : "";
                max = Math.max(max, Integer.parseInt(tail.replaceAll("\\D", "")));
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + "-" + String.format("%04d", max + 1);
    }

    private static Integer intOrNull(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static java.math.BigDecimal dec(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(o).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ServiceException(422, field + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
