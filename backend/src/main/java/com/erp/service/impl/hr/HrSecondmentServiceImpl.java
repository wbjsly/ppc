package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrChangeLogDao;
import com.erp.dao.hr.HrDispatchAllowanceStdDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrGradeDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.hr.HrSecondmentDao;
import com.erp.dao.hr.HrSocialPolicyDao;
import com.erp.dao.mdm.MdmLegalEntityDao;
import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrDispatchAllowanceStd;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrPosition;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.hr.HrSecondment;
import com.erp.entity.hr.HrSocialPolicy;
import com.erp.entity.mdm.MdmLegalEntity;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrSocialChangeService;
import com.erp.service.hr.HrSecondmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 外派/借调管理实现（secondment-management）。
 * 状态机自建（不接 ApprovalEngine，沿考勤域先例）；双人复核比对抄调岗 C-4.7-03 模式。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrSecondmentServiceImpl implements HrSecondmentService {

    private final HrSecondmentDao secDao;
    private final HrEmployeeDao empDao;
    private final HrPositionDao posDao;
    private final HrGradeDao gradeDao;
    private final HrDispatchAllowanceStdDao stdDao;
    private final HrSocialPolicyDao socialPolicyDao;
    private final MdmLegalEntityDao legalEntityDao;
    private final HrChangeLogDao logDao;
    private final HrRemindDao remindDao;
    private final HrSocialChangeService socialChangeService;
    private final SysParamService sysParamService;
    private final com.erp.dao.system.HrSysAttachmentDao attachmentDao;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 双人复核五要素 */
    private static final List<String> REVIEW_KEYS =
            List.of("targetLe", "costSharePct", "salaryPlace", "costCenter", "startDate");
    /** 在途状态（防重 + 员工视图标记） */
    private static final List<String> IN_FLIGHT = List.of(
            HrSecondment.ST_DRAFT, HrSecondment.ST_CONFIRMED_OUT, HrSecondment.ST_CONFIRMED_IN,
            HrSecondment.ST_HR_REVIEWED, HrSecondment.ST_REVIEWING, HrSecondment.ST_REVIEW_DIFF,
            HrSecondment.ST_APPROVING, HrSecondment.ST_APPROVED, HrSecondment.ST_EFFECTED,
            HrSecondment.ST_EXTENDING, HrSecondment.ST_RETURNING, HrSecondment.ST_EXPIRED_ALERT);
    /** 员工视图可见的在途标记状态 */
    private static final List<String> VISIBLE_FLAG_STATES = List.of(
            HrSecondment.ST_EFFECTED, HrSecondment.ST_EXTENDING,
            HrSecondment.ST_RETURNING, HrSecondment.ST_EXPIRED_ALERT);

    // ================================================================
    // 发起
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Map<String, Object> payload) {
        String empId = str(payload.get("empId"));
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在");
        }
        if (HrEmployee.ST_RESIGNED.equals(e.getEmpStatus())) {
            throw new ServiceException(422, "已离职员工不可外派/借调");
        }
        String chgType = str(payload.get("chgType"));
        if (!HrSecondment.TYPE_DISPATCH.equals(chgType) && !HrSecondment.TYPE_SECOND.equals(chgType)) {
            throw new ServiceException(422, "类型非法（DISPATCH 外派 / SECOND 借调）");
        }
        // 在途防重（L1）
        HrSecondment inFlight = secDao.selectOne(new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .eq(HrSecondment::getEmpId, empId)
                .in(HrSecondment::getStatus, IN_FLIGHT)
                .last("LIMIT 1"));
        if (inFlight != null) {
            throw new ServiceException(422, "该员工已有在途外派/借调单：" + inFlight.getSecNo()
                    + "（单员工仅允许一张在途）");
        }
        String targetLe = str(payload.get("targetLe"));
        require(targetLe, "用工/新主体");
        if (e.getLegalEntity() != null && e.getLegalEntity().equals(targetLe)) {
            throw new ServiceException(422, "用工主体与员工当前法人主体相同，无需外派/借调");
        }
        LocalDate start = date(payload.get("startDate"));
        LocalDate end = date(payload.get("endDate"));
        if (start == null || end == null || !end.isAfter(start)) {
            throw new ServiceException(422, "起止日期必填且结束须晚于开始");
        }

        HrSecondment s = new HrSecondment();
        s.setId(uuid());
        s.setSecNo(nextNo());
        s.setChgType(chgType);
        s.setEmpId(e.getId());
        s.setEmpName(e.getName());
        s.setEmpNo(e.getEmpNo());
        s.setSrcLe(e.getLegalEntity());
        s.setTargetLe(targetLe);
        s.setSalaryOwner(e.getLegalEntity()); // 备案语义：恒=劳动关系主体
        s.setStartDate(start);
        s.setEndDate(end);
        s.setStatus(HrSecondment.ST_DRAFT);
        s.setExtendMonthsTotal(0);
        s.setRemindFlag("0");
        s.setLinkPending("0");
        s.setPlaceChange("1".equals(str(payload.get("placeChange"))) ? "1" : "0");
        s.setTargetPlace(str(payload.get("targetPlace")));
        s.setCostCenter(str(payload.get("costCenter")));
        s.setAgreementNo(str(payload.get("agreementNo")));
        s.setAgreementDate(date(payload.get("agreementDate")));
        s.setCreateBy(currentUser());
        s.setCreateDate(LocalDateTime.now());

        List<String> warnings = new ArrayList<>();
        // 借调必填（BR-4.7-19 备案 + 分摊比例）
        if (HrSecondment.TYPE_SECOND.equals(chgType)) {
            if (s.getAgreementNo() == null || s.getAgreementNo().isBlank()
                    || s.getAgreementDate() == null) {
                throw new ServiceException(422, "借调须备案三方协议编号与签署日期（BR-4.7-19）");
            }
            BigDecimal pct = dec(payload.get("costSharePct"));
            if (pct == null || pct.signum() < 0 || pct.compareTo(new BigDecimal("100")) > 0) {
                throw new ServiceException(422, "借调须明确费用分摊比例（0-100）");
            }
            s.setCostSharePct(pct);
        } else {
            s.setCostSharePct(dec(payload.get("costSharePct")));
        }
        // 期限 L4（FR-4.7-7-1：>12 月提示，不阻断）
        long termMonths = ChronoUnit.MONTHS.between(start, end);
        if (termMonths > 12) {
            warnings.add("借调/外派期限 " + termMonths + " 个月超过 12 个月（L4）：长期安排存在法律风险，"
                    + "借调建议转为正式外派（BR-4.7-05）");
        }
        // 补贴标准带出（FR-4.7-7-3）：缴纳地 × 职级（调入岗位职级优先，缺省取员工现岗位职级）
        BigDecimal stdMonthly = allowanceStd(s.getTargetPlace(), gradeIdOf(payload, e));
        BigDecimal allowance = dec(payload.get("allowanceMonthly"));
        s.setAllowanceStdMonthly(stdMonthly);
        s.setAllowanceMonthly(allowance != null ? allowance : stdMonthly);
        if (stdMonthly == null && s.getAllowanceMonthly() == null) {
            warnings.add("补贴标准未命中（" + s.getTargetPlace() + "×职级），请 HR 手工维护标准或直接填写补贴额");
        }
        secDao.insert(s);
        if (s.getAllowanceStdMonthly() != null && s.getAllowanceMonthly() != null
                && s.getAllowanceStdMonthly().compareTo(s.getAllowanceMonthly()) != 0) {
            warnings.add("补贴申请额 " + s.getAllowanceMonthly().stripTrailingZeros().toPlainString()
                    + " 与属地职级标准 " + s.getAllowanceStdMonthly().stripTrailingZeros().toPlainString()
                    + " 存在差异（审批复核要素）");
        }
        log.info("secondment submit: {} {} -> {}", s.getSecNo(), chgType, targetLe);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secondment", s);
        out.put("warnings", warnings);
        return out;
    }

    // ================================================================
    // 双方确认（比例锁定）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment confirmOut(String secId, String opinion, boolean agree) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_DRAFT);
        require(opinion, "调出确认意见");
        if (!agree) {
            s.setStatus(HrSecondment.ST_REJECTED);
            s.setConfirmOutJson(json(Map.of("agree", false, "opinion", opinion,
                    "by", currentUser(), "at", LocalDateTime.now().toString())));
            return touch(s);
        }
        s.setConfirmOutJson(json(Map.of("agree", true, "opinion", opinion,
                "by", currentUser(), "at", LocalDateTime.now().toString())));
        s.setStatus(HrSecondment.ST_CONFIRMED_OUT);
        return touch(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment confirmIn(String secId, String opinion, boolean agree) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_CONFIRMED_OUT);
        require(opinion, "调入确认意见");
        if (!agree) {
            s.setStatus(HrSecondment.ST_REJECTED);
            s.setConfirmInJson(json(Map.of("agree", false, "opinion", opinion,
                    "by", currentUser(), "at", LocalDateTime.now().toString())));
            return touch(s);
        }
        s.setConfirmInJson(json(Map.of("agree", true, "opinion", opinion,
                "by", currentUser(), "at", LocalDateTime.now().toString())));
        s.setStatus(HrSecondment.ST_CONFIRMED_IN);
        // 双方确认完成 → 分摊比例锁定（月度分摊凭证计算因子，改动须作废重开）
        s.setShareLocked("1");
        return touch(s);
    }

    // ================================================================
    // HR 合规审核
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> hrReview(String secId, String opinion) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_CONFIRMED_IN);
        List<String> warnings = new ArrayList<>();
        // 变更缴纳地 → 目标城市须已维护属地政策（L1，FR-4.7-7-3 同 C-4.7-02）
        if ("1".equals(s.getPlaceChange())) {
            if (s.getTargetPlace() == null || s.getTargetPlace().isBlank()) {
                throw new ServiceException(422, "勾选变更缴纳地须填写目标缴纳地");
            }
            Long ok = socialPolicyDao.selectCount(new LambdaQueryWrapper<HrSocialPolicy>()
                    .eq(HrSocialPolicy::getCity, s.getTargetPlace())
                    .eq(HrSocialPolicy::getYear, s.getStartDate().getYear())
                    .eq(HrSocialPolicy::getDelFlag, "0"));
            if (ok == null || ok == 0) {
                throw new ServiceException(422, "属地政策未配置：" + s.getTargetPlace() + " "
                        + s.getStartDate().getYear() + " 年（L1 硬阻断，先维护社保政策）");
            }
        }
        // C-4.7-12：缴纳地与用工主体注册地不一致 → L4 提示核查
        MdmLegalEntity le = legalEntityDao.selectOne(new LambdaQueryWrapper<MdmLegalEntity>()
                .eq(MdmLegalEntity::getLeCode, s.getTargetLe()).last("LIMIT 1"));
        if (le != null && le.getRegPlace() != null && s.getTargetPlace() != null
                && !le.getRegPlace().equals(s.getTargetPlace())) {
            warnings.add("缴纳地「" + s.getTargetPlace() + "」与用工主体注册地「" + le.getRegPlace()
                    + "」不一致（C-4.7-12 L4）：请核查属地合规性（社保参保地/补贴属地标准）");
        }
        s.setHrOpinion(opinion);
        s.setStatus(HrSecondment.ST_REVIEWING);
        touch(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secondment", s);
        out.put("warnings", warnings);
        return out;
    }

    // ================================================================
    // 双人复核（C-4.7-11，抄调岗比对机制）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment signReview(String secId, Map<String, Object> payload) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_REVIEWING);
        String user = currentUser();
        Map<String, Object> elements = new LinkedHashMap<>();
        for (String key : REVIEW_KEYS) {
            elements.put(key, payload.get(key));
        }
        if (s.getReview1By() == null) {
            s.setReview1By(user);
            s.setReview1At(LocalDateTime.now());
            s.setReview1Json(json(elements));
        } else if (s.getReview2By() == null) {
            if (user.equals(s.getReview1By())) {
                throw new ServiceException(422, "C-4.7-11 双人复核须两人独立确认，同一人不可双签");
            }
            s.setReview2By(user);
            s.setReview2At(LocalDateTime.now());
            s.setReview2Json(json(elements));
            // 比对五要素
            Map<String, Object> r1 = parseJson(s.getReview1Json());
            List<String> diffs = new ArrayList<>();
            for (String key : REVIEW_KEYS) {
                if (!String.valueOf(r1.get(key)).equals(String.valueOf(elements.get(key)))) {
                    diffs.add(key);
                }
            }
            if (!diffs.isEmpty()) {
                s.setStatus(HrSecondment.ST_REVIEW_DIFF);
                s.setReviewDiffJson(json(Map.of("diffs", diffs,
                        "review1", s.getReview1By(), "review2", s.getReview2By())));
                log.warn("secondment double review DIFF on {}: {}", s.getSecNo(), diffs);
                return touch(s);
            }
            s.setStatus(HrSecondment.ST_APPROVING);
        } else {
            throw new ServiceException(422, "复核已完成");
        }
        return touch(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment adjudicateDiff(String secId, String decision, String note) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_REVIEW_DIFF);
        if (!"APPROVE".equals(decision)) {
            s.setStatus(HrSecondment.ST_REJECTED);
            s.setAdjudicateNote(note);
            return touch(s);
        }
        require(note, "裁决说明");
        s.setAdjudicateNote(note);
        s.setStatus(HrSecondment.ST_APPROVING);
        return touch(s);
    }

    // ================================================================
    // 审批与生效
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment approve(String secId, boolean approveFlag, String opinion) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_APPROVING);
        if (approveFlag) {
            s.setStatus(HrSecondment.ST_APPROVED); // 待生效（到 START_DATE 由 effect/扫描执行）
        } else {
            s.setStatus(HrSecondment.ST_REJECTED);
        }
        s.setHrOpinion((s.getHrOpinion() == null ? "" : s.getHrOpinion() + "；")
                + "[" + currentUser() + " 审批" + (approveFlag ? "通过" : "驳回") + "] " + opinion);
        return touch(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment effect(String secId) {
        HrSecondment s = require(secId);
        if (HrSecondment.ST_EFFECTED.equals(s.getStatus())) {
            return s; // 幂等
        }
        requireStatus(s, HrSecondment.ST_APPROVED);
        if (LocalDate.now().isBefore(s.getStartDate())) {
            throw new ServiceException(422, "未到生效日期（" + s.getStartDate() + "）");
        }
        HrEmployee e = empDao.selectById(s.getEmpId());
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在：" + s.getEmpId());
        }
        if (HrSecondment.TYPE_DISPATCH.equals(s.getChgType())) {
            // 外派：法人主体/合同主体/缴纳地写回 + 社保减员增员双单（BR-4.7-21）
            try {
                logChange(e, "所属法人主体", e.getLegalEntity(), s.getTargetLe(), "外派生效 " + s.getSecNo());
                logChange(e, "合同主体", e.getLegalEntity(), s.getTargetLe(),
                        "外派合同主体变更 " + s.getSecNo() + "（起止 " + s.getStartDate() + "~" + s.getEndDate() + "）");
                e.setLegalEntity(s.getTargetLe());
                if ("1".equals(s.getPlaceChange()) && s.getTargetPlace() != null) {
                    logChange(e, "社保缴纳地", e.getSocialPlace(), s.getTargetPlace(), "外派生效 " + s.getSecNo());
                    e.setSocialPlace(s.getTargetPlace());
                }
                if (s.getCostCenter() != null && !s.getCostCenter().isBlank()) {
                    logChange(e, "成本中心", e.getCostCenter(), s.getCostCenter(), "外派生效 " + s.getSecNo());
                    e.setCostCenter(s.getCostCenter());
                }
                e.setUpdateBy("secondment");
                e.setUpdateDate(LocalDateTime.now());
                empDao.updateById(e);
                // 社保双单（原主体减员 + 新主体增员，BR-4.7-21；失败降级不回滚人事事务）
                socialChangeService.autoOpen(e.getId(), com.erp.entity.hr.HrSocialChange.TYPE_REMOVE,
                        "SECONDMENT", s.getSecNo(), null, s.getStartDate());
                if (s.getTargetPlace() != null && !s.getTargetPlace().isBlank()) {
                    socialChangeService.autoOpen(e.getId(), com.erp.entity.hr.HrSocialChange.TYPE_ADD,
                            "SECONDMENT", s.getSecNo(), s.getTargetPlace(), s.getStartDate());
                }
            } catch (Exception ex) {
                log.warn("secondment dispatch link failed {}: {}", s.getSecNo(), ex.getMessage());
                s.setLinkPending("1");
                s.setLinkNote("生效联动失败：" + ex.getMessage());
            }
        } else {
            // 借调：主档劳动关系字段全部不动（EMP_STATUS/LEGAL_ENTITY/合同），单据即事实
            s.setOrigCostCenter(e.getCostCenter());
        }
        s.setStatus(HrSecondment.ST_EFFECTED);
        s.setEffectiveAt(LocalDateTime.now());
        return touch(s);
    }

    // ================================================================
    // 返岗 / 延期（借调专属）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment returnPost(String secId, String note) {
        HrSecondment s = require(secId);
        if (!HrSecondment.TYPE_SECOND.equals(s.getChgType())) {
            throw new ServiceException(422, "仅借调单可返岗（外派为主体永久转移）");
        }
        if (!List.of(HrSecondment.ST_EFFECTED, HrSecondment.ST_EXTENDING,
                HrSecondment.ST_RETURNING, HrSecondment.ST_EXPIRED_ALERT).contains(s.getStatus())) {
            throw new ServiceException(422, "当前状态不可返岗（" + s.getStatus() + "）");
        }
        s.setStatus(HrSecondment.ST_RETURNED);
        s.setReturnAt(LocalDateTime.now());
        s.setHrOpinion((s.getHrOpinion() == null ? "" : s.getHrOpinion() + "；")
                + "[返岗确认] " + note);
        // 借调期间主档成本中心未变（单据即事实），返岗=关闭单据，恢复语义天然满足
        return touch(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment extend(String secId, int months, String reason) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_EFFECTED);
        if (!HrSecondment.TYPE_SECOND.equals(s.getChgType())) {
            throw new ServiceException(422, "仅借调单可延期");
        }
        if (months <= 0) {
            throw new ServiceException(422, "延期月数须为正整数");
        }
        require(reason, "延期原因");
        // BR-4.7-05：原期 + 历次延期（含本次）累计 >24 月 → L1 拒绝，须转正式外派
        long originalMonths = Math.max(1, ChronoUnit.MONTHS.between(s.getStartDate(), s.getEndDate()));
        long cumulative = originalMonths + s.getExtendMonthsTotal() + months;
        if (cumulative > 24) {
            throw new ServiceException(422, "累计借调期限将达 " + cumulative + " 个月，超过 24 个月上限（BR-4.7-05），"
                    + "超出须转为正式外派（变更劳动合同主体）");
        }
        s.setStatus(HrSecondment.ST_EXTENDING);
        s.setHrOpinion((s.getHrOpinion() == null ? "" : s.getHrOpinion() + "；")
                + "[延期申请 " + months + " 月] " + reason);
        return touch(s);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrSecondment approveExtend(String secId, boolean approveFlag, String note) {
        HrSecondment s = require(secId);
        requireStatus(s, HrSecondment.ST_EXTENDING);
        s.setHrOpinion((s.getHrOpinion() == null ? "" : s.getHrOpinion() + "；")
                + "[延期审批" + (approveFlag ? "通过" : "驳回") + " by " + currentUser() + "] " + note);
        if (approveFlag) {
            // 从意见区解析最近一次申请月数（简化：重新校验后顺延）
            int months = lastExtendMonths(s.getHrOpinion());
            s.setEndDate(s.getEndDate().plusMonths(months));
            s.setExtendMonthsTotal(s.getExtendMonthsTotal() + months);
        }
        s.setStatus(HrSecondment.ST_EFFECTED);
        return touch(s);
    }

    // ================================================================
    // 每日扫描
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> remindScan() {
        int remindDays = intParam("SECONDMENT_REMIND_DAYS", 15);
        LocalDate today = LocalDate.now();
        int reminded = 0;
        int expiredAlerted = 0;
        for (HrSecondment s : secDao.selectList(new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .eq(HrSecondment::getChgType, HrSecondment.TYPE_SECOND)
                .in(HrSecondment::getStatus, HrSecondment.ST_EFFECTED, HrSecondment.ST_RETURNING))) {
            long left = ChronoUnit.DAYS.between(today, s.getEndDate());
            if (left >= 0 && left <= remindDays && !"1".equals(s.getRemindFlag())) {
                try {
                    HrRemind r = new HrRemind();
                    r.setId(uuid());
                    r.setRemindType(HrRemind.TYPE_SECONDMENT_RETURN);
                    r.setEmpId(s.getEmpId());
                    r.setEmpName(s.getEmpName());
                    r.setRemindDate(today);
                    r.setDetail("借调 " + s.getSecNo() + " 距到期返岗日剩 " + left + " 天（"
                            + s.getEndDate() + "），请双方主管与 HR 确认返岗安排；逾期须走延期审批（BR-4.7-05）");
                    r.setCreateBy("system");
                    remindDao.insert(r);
                    s.setRemindFlag("1");
                    touch(s);
                    reminded++;
                } catch (Exception ignored) {
                    // 同日已提醒（UK）跳过
                }
            }
            if (left < 0 && HrSecondment.ST_EFFECTED.equals(s.getStatus())) {
                // 过期未返岗且无在途延期 → EXPIRED_ALERT（分摊任务将跳过，C-4.7-13 语义）
                s.setStatus(HrSecondment.ST_EXPIRED_ALERT);
                touch(s);
                try {
                    HrRemind r = new HrRemind();
                    r.setId(uuid());
                    r.setRemindType(HrRemind.TYPE_SECONDMENT_EXPIRED);
                    r.setEmpId(s.getEmpId());
                    r.setEmpName(s.getEmpName());
                    r.setRemindDate(today);
                    r.setDetail("借调 " + s.getSecNo() + " 已过返岗日 " + (-left) + " 天未返岗未延期（L1 阻断成本分摊），"
                            + "请双方 HR 与主管限期处理（返岗确认或延期审批）");
                    r.setCreateBy("system");
                    remindDao.insert(r);
                } catch (Exception ignored) {
                    // 防重跳过
                }
                expiredAlerted++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reminded", reminded);
        out.put("expiredAlerted", expiredAlerted);
        return out;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public List<HrSecondment> list(String chgType, String status) {
        LambdaQueryWrapper<HrSecondment> qw = new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .orderByDesc(HrSecondment::getCreateDate);
        if (chgType != null && !chgType.isBlank()) {
            qw.eq(HrSecondment::getChgType, chgType);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrSecondment::getStatus, status);
        }
        return secDao.selectList(qw);
    }

    @Override
    public Map<String, Object> detail(String secId) {
        HrSecondment s = require(secId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secondment", s);
        out.put("employee", empDao.selectById(s.getEmpId()));
        MdmLegalEntity le = legalEntityDao.selectOne(new LambdaQueryWrapper<MdmLegalEntity>()
                .eq(MdmLegalEntity::getLeCode, s.getTargetLe()).last("LIMIT 1"));
        out.put("targetLeName", le == null ? null : le.getLeName());
        out.put("attachments", attachmentDao.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.erp.entity.system.HrSysAttachment>()
                        .eq(com.erp.entity.system.HrSysAttachment::getBizType,
                                com.erp.entity.system.HrSysAttachment.BIZ_SECONDTM_AGREEMENT)
                        .eq(com.erp.entity.system.HrSysAttachment::getBizId, secId)
                        .eq(com.erp.entity.system.HrSysAttachment::getDelFlag, "0")));
        return out;
    }

    @Override
    public Map<String, String> inFlightFlags() {
        Map<String, String> flags = new LinkedHashMap<>();
        for (HrSecondment s : secDao.selectList(new LambdaQueryWrapper<HrSecondment>()
                .eq(HrSecondment::getDelFlag, "0")
                .in(HrSecondment::getStatus, VISIBLE_FLAG_STATES))) {
            MdmLegalEntity le = legalEntityDao.selectOne(new LambdaQueryWrapper<MdmLegalEntity>()
                .eq(MdmLegalEntity::getLeCode, s.getTargetLe()).last("LIMIT 1"));
            String leName = le == null ? s.getTargetLe() : le.getLeName();
            flags.put(s.getEmpId(), (HrSecondment.TYPE_SECOND.equals(s.getChgType()) ? "借调中·" : "外派中·")
                    + leName);
        }
        return flags;
    }

    @Override
    public List<Map<String, Object>> allowanceStdList(String place) {
        LambdaQueryWrapper<HrDispatchAllowanceStd> qw = new LambdaQueryWrapper<HrDispatchAllowanceStd>()
                .eq(HrDispatchAllowanceStd::getDelFlag, "0")
                .orderByAsc(HrDispatchAllowanceStd::getPlace);
        if (place != null && !place.isBlank()) {
            qw.eq(HrDispatchAllowanceStd::getPlace, place);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrDispatchAllowanceStd std : stdDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", std.getId());
            row.put("place", std.getPlace());
            row.put("gradeId", std.getGradeId());
            row.put("monthly", std.getMonthly());
            out.add(row);
        }
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    /** 补贴标准：place × gradeId 命中返回月标准，未命中 null */
    private BigDecimal allowanceStd(String place, String gradeId) {
        if (place == null || gradeId == null) {
            return null;
        }
        HrDispatchAllowanceStd std = stdDao.selectOne(new LambdaQueryWrapper<HrDispatchAllowanceStd>()
                .eq(HrDispatchAllowanceStd::getPlace, place)
                .eq(HrDispatchAllowanceStd::getGradeId, gradeId)
                .eq(HrDispatchAllowanceStd::getDelFlag, "0"));
        return std == null ? null : std.getMonthly();
    }

    /** 职级：payload.targetGradeId 优先，缺省员工现岗位职级 */
    private String gradeIdOf(Map<String, Object> payload, HrEmployee e) {
        String g = str(payload.get("targetGradeId"));
        if (g != null && !g.isBlank()) {
            return g;
        }
        if (e.getPosId() == null || e.getPosId().isBlank()) {
            return null;
        }
        HrPosition pos = posDao.selectById(e.getPosId());
        return pos == null ? null : pos.getGradeId();
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

    private int lastExtendMonths(String opinionTrail) {
        // 从意见区解析最近一次 [延期申请 N 月] 标记
        if (opinionTrail == null) {
            return 0;
        }
        int idx = opinionTrail.lastIndexOf("[延期申请 ");
        if (idx < 0) {
            throw new ServiceException(500, "延期申请轨迹缺失");
        }
        String seg = opinionTrail.substring(idx);
        try {
            return Integer.parseInt(seg.replaceAll("[^0-9]", " ").trim().split("\\s+")[0]);
        } catch (Exception ex) {
            throw new ServiceException(500, "延期月数解析失败：" + seg);
        }
    }

    private HrSecondment require(String secId) {
        HrSecondment s = secDao.selectById(secId);
        if (s == null) {
            throw new ServiceException(404, "外派/借调单不存在：" + secId);
        }
        return s;
    }

    private void requireStatus(HrSecondment s, String expect) {
        if (!expect.equals(s.getStatus())) {
            throw new ServiceException(422, "状态不符（期望 " + expect + "，当前 " + s.getStatus() + "）");
        }
    }

    private HrSecondment touch(HrSecondment s) {
        s.setUpdateBy(currentUser());
        s.setUpdateDate(LocalDateTime.now());
        secDao.updateById(s);
        return s;
    }

    private String nextNo() {
        String prefix = "SEC" + LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        List<String> existing = secDao.selectNosByPrefix(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(no.lastIndexOf('-') + 1)));
            } catch (Exception ignored) {
                // skip
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new ServiceException(500, "序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(jsonStr, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ServiceException(422, field + "必填");
        }
    }

    private static String str(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static BigDecimal dec(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "金额/比例格式非法：" + v);
        }
    }

    private static LocalDate date(Object v) {
        if (v == null || String.valueOf(v).isBlank()) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    }

    private int intParam(String key, int def) {
        try {
            String v = sysParamService.getValue(key);
            return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String currentUser() {
        try {
            return org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
