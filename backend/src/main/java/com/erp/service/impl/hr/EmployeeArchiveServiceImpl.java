package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrChangeLogDao;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrOnboardingDao;
import com.erp.dao.hr.HrPositionDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.hr.HrTransferDao;
import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrOnboarding;
import com.erp.entity.hr.HrPosition;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.hr.HrTransfer;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.hr.EmployeeArchiveService;
import com.erp.service.hr.HrAccountLinkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.util.UUID;

/**
 * 员工档案实现（employee-archive）。
 * 建档六步状态机（外部核验离线留桩）/ 变更日志 / 锁定 L2 特批 / 试用期提醒 / 二次入职。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeArchiveServiceImpl implements EmployeeArchiveService {

    private final HrDeptDao deptDao;
    private final HrPositionDao posDao;
    private final HrEmployeeDao empDao;
    private final HrOnboardingDao onbDao;
    private final HrChangeLogDao logDao;
    private final HrRemindDao remindDao;
    private final HrTransferDao transferDao;
    private final SysParamService sysParamService;
    private final ApprovalEngine approvalEngine;
    private final HrAccountLinkService accountLinkService; // 字段注入防循环见下方 @Autowired 备注

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter NO_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    public static final String BIZ_LOCK_CHANGE = "HrLockChange";

    // ================================================================
    // 2.2 建档六步状态机
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrOnboarding createOnboarding(Map<String, Object> payload) {
        String offerNo = str(payload.get("offerNo"));
        require(offerNo, "录用审批单编号");
        boolean approved = "1".equals(str(payload.get("offerApproved")))
                || Boolean.TRUE.equals(payload.get("offerApprovedBool"));
        HrOnboarding o = new HrOnboarding();
        o.setId(uuid());
        o.setOnbNo(nextNo("ONB", onbDao::selectNosByPrefix));
        o.setStep(1);
        o.setStatus(HrOnboarding.ST_IN_PROGRESS);
        o.setOfferNo(offerNo);
        o.setOfferApproved(approved ? "1" : "0");
        o.setCreateBy(currentUser());
        onbDao.insert(o);
        log.info("onboarding created: {}", o.getOnbNo());
        return o;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrOnboarding advance(String onbId, Map<String, Object> payload) {
        HrOnboarding o = requireOnb(onbId);
        if (HrOnboarding.ST_DONE.equals(o.getStatus())) {
            throw new ServiceException(422, "建档单已完成");
        }
        int step = o.getStep() == null ? 1 : o.getStep();
        switch (step) {
            case 1 -> advanceStep1(o, payload);
            case 2 -> advanceStep2(o, payload);
            case 3 -> advanceStep3(o, payload);
            case 4 -> advanceStep4(o, payload);
            case 5 -> advanceStep5(o, payload);
            case 6 -> advanceStep6(o, payload);
            default -> throw new ServiceException(422, "非法步骤：" + step);
        }
        o.setUpdateBy(currentUser());
        o.setUpdateDate(LocalDateTime.now());
        onbDao.updateById(o);
        return o;
    }

    /** ①录用单接收：未审批通过不可推进（FR-4.7-1-1 门禁） */
    private void advanceStep1(HrOnboarding o, Map<String, Object> payload) {
        String offerNo = str(payload.get("offerNo"));
        if (offerNo != null && !offerNo.isBlank()) {
            o.setOfferNo(offerNo);
        }
        boolean approved = "1".equals(str(payload.get("offerApproved")));
        if (payload.containsKey("offerApproved")) {
            o.setOfferApproved(approved ? "1" : "0");
        }
        if (!"1".equals(o.getOfferApproved())) {
            throw new ServiceException(422, "录用审批单未审批通过，不可推进建档（FR-4.7-1-1）");
        }
        o.setStep(2);
    }

    /** ②信息登记：候选信息 JSON 必填 + 实名/四要素人工核验留桩 */
    private void advanceStep2(HrOnboarding o, Map<String, Object> payload) {
        String candidateJson = str(payload.get("candidateJson"));
        require(candidateJson, "候选人登记信息");
        Map<String, Object> candidate;
        try {
            candidate = MAPPER.readValue(candidateJson,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            throw new ServiceException(422, "候选人信息 JSON 非法");
        }
        require(str(candidate.get("name")), "姓名");
        require(str(candidate.get("idCardNo")), "身份证号");
        o.setCandidateJson(candidateJson);
        if ("1".equals(str(payload.get("realnameCheck")))) {
            o.setRealnameCheck("1");
            o.setRealnameBy(currentUser());
            o.setRealnameAt(LocalDateTime.now());
        }
        if ("1".equals(str(payload.get("bank4Check")))) {
            o.setBank4Check("1");
        }
        if (!"1".equals(o.getRealnameCheck())) {
            throw new ServiceException(422, "实名认证须完成人工核验登记（核验方式/核验人/时间）");
        }
        if (!"1".equals(o.getBank4Check())) {
            throw new ServiceException(422, "银行卡四要素须完成人工核验登记");
        }
        o.setStep(3);
    }

    /** ③证件核验：离职证明缺失 L1 硬阻断（FR-4.7-1-3） */
    private void advanceStep3(HrOnboarding o, Map<String, Object> payload) {
        String docsJson = str(payload.get("docsJson"));
        require(docsJson, "证件清单");
        if (!docsJson.contains("离职证明") || !docsJson.contains("\"submitted\":true")
                && !docsJson.contains("\"submitted\": true")) {
            // 清单须含离职证明且已提交——宽松解析：含「离职证明」且该条 submitted=true
            Map<String, Object> parsed = parseDocs(docsJson);
            Object proof = parsed.get("离职证明");
            boolean submitted = proof instanceof Map
                    && Boolean.TRUE.equals(((Map<?, ?>) proof).get("submitted"));
            if (!submitted) {
                throw new ServiceException(422, "离职证明为必备项（FR-4.7-1-3 L1 硬阻断），缺失不可继续建档");
            }
        }
        o.setDocsJson(docsJson);
        o.setDocsVerified("1");
        o.setStep(4);
    }

    /** ④合同签订：试用期合规 L1（合同期 ≥3 年 → 试用期 ≤6 个月） */
    private void advanceStep4(HrOnboarding o, Map<String, Object> payload) {
        String contractJson = str(payload.get("contractJson"));
        require(contractJson, "合同信息");
        Map<String, Object> c;
        try {
            c = MAPPER.readValue(contractJson,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            throw new ServiceException(422, "合同信息 JSON 非法");
        }
        require(str(c.get("contractNo")), "合同编号");
        LocalDate start = date(c.get("contractStart"));
        LocalDate end = date(c.get("contractEnd"));
        Integer probationMonths = intOrNull(c.get("probationMonths"));
        if (start == null || end == null) {
            throw new ServiceException(422, "合同起止日期必填");
        }
        long contractMonths = monthsBetween(start, end);
        if (contractMonths >= 36 && (probationMonths == null || probationMonths > 6)) {
            throw new ServiceException(422, "试用期超法定上限：合同期限 ≥ 3 年时试用期不得超过 6 个月（FR-4.7-1-4 L1）");
        }
        if (probationMonths != null && probationMonths > 0 && contractMonths < 36 && probationMonths > 2) {
            throw new ServiceException(422, "试用期超法定上限：合同期限 < 3 年时试用期不得超过 2 个月（FR-4.7-1-4 L1）");
        }
        o.setContractJson(contractJson);
        o.setContractOk("1");
        o.setStep(5);
    }

    /** ⑤生成主档：EMP 工号 + 身份证唯一 L1 + 二次入职引导 */
    private void advanceStep5(HrOnboarding o, Map<String, Object> payload) {
        Map<String, Object> candidate = parseCandidate(o.getCandidateJson());
        String idCardNo = str(candidate.get("idCardNo"));
        String name = str(candidate.get("name"));
        // BR-4.7-09 身份证唯一
        HrEmployee dup = empDao.selectOne(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .eq(HrEmployee::getIdCardNo, idCardNo)
                .last("LIMIT 1"));
        if (dup != null) {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("existingEmpNo", dup.getEmpNo());
            probe.put("existingName", dup.getName());
            probe.put("existingStatus", dup.getEmpStatus());
            throw new ServiceException(422, "BR-4.7-09 身份证号已存在（工号 " + dup.getEmpNo()
                    + "，状态 " + dup.getEmpStatus() + "）：二次入职请走「重新入职」专用流程（rehireOf="
                    + dup.getId() + "）");
        }
        String deptId = str(payload.get("deptId"));
        String posId = str(payload.get("posId"));
        require(deptId, "所属部门");
        require(posId, "岗位");
        HrPosition pos = posDao.selectById(posId);
        if (pos == null || !"1".equals(pos.getStatus())) {
            throw new ServiceException(422, "岗位不存在或已停用");
        }
        HrEmployee e = new HrEmployee();
        e.setId(uuid());
        e.setEmpNo(nextEmpNo());
        e.setName(name);
        e.setIdCardNo(idCardNo);
        e.setGender(str(candidate.get("gender")));
        e.setBirthDate(date(candidate.get("birthDate")));
        e.setPhone(str(candidate.get("phone")));
        e.setAddress(str(candidate.get("address")));
        e.setEmergencyContact(str(candidate.get("emergencyContact")));
        e.setBankCardNo(str(candidate.get("bankCardNo")));
        e.setEducation(str(candidate.get("education")));
        e.setWorkHistoryJson(str(candidate.get("workHistoryJson")));
        e.setDeptId(deptId);
        e.setPosId(posId);
        e.setCostCenter(str(payload.get("costCenter")));
        e.setHireDate(date(payload.get("hireDate")) != null ? date(payload.get("hireDate")) : LocalDate.now());
        // 合同信息从④快照带入
        Map<String, Object> c = parseJsonSafe(o.getContractJson());
        e.setContractNo(str(c.get("contractNo")));
        e.setContractStart(date(c.get("contractStart")));
        e.setContractEnd(date(c.get("contractEnd")));
        Integer probationMonths = intOrNull(c.get("probationMonths"));
        if (probationMonths != null && probationMonths > 0) {
            e.setEmpStatus(HrEmployee.ST_PROBATION);
            e.setProbationEnd(e.getHireDate().plusMonths(probationMonths));
        } else {
            e.setEmpStatus(HrEmployee.ST_REGULAR);
        }
        String rejoinOf = str(payload.get("rehireOf"));
        e.setRejoinOf(rejoinOf);
        e.setCreateBy(currentUser());
        empDao.insert(e);
        o.setEmpId(e.getId());
        o.setStep(6);
        log.info("employee archived: {} {} (onboarding {})", e.getEmpNo(), name, o.getOnbNo());
    }

    /** ⑥社保增员留桩 → DONE（触发账号开通审批联动） */
    private void advanceStep6(HrOnboarding o, Map<String, Object> payload) {
        String socialJson = str(payload.get("socialJson"));
        require(socialJson, "社保增员登记（缴纳地/参保类型/增员日期）");
        Map<String, Object> s = parseJsonSafe(socialJson);
        require(str(s.get("place")), "社保缴纳地");
        o.setSocialJson(socialJson);
        o.setSocialDone("1");
        o.setStatus(HrOnboarding.ST_DONE);
        HrEmployee e = empDao.selectById(o.getEmpId());
        if (e != null) {
            e.setSocialPlace(str(s.get("place")));
            e.setSocialType(str(s.get("type")));
            e.setSocialDate(date(s.get("date")) != null ? date(s.get("date")) : LocalDate.now());
            e.setUpdateBy(currentUser());
            e.setUpdateDate(LocalDateTime.now());
            empDao.updateById(e);
        }
        // 账号联动：建档完成 → 生成入职开通审批待办（design D3 触点①）
        try {
            accountLinkService.onArchived(o.getEmpId());
        } catch (Exception ex) {
            log.warn("account link (onboard) failed for {}: {}", o.getEmpId(), ex.getMessage());
        }
        log.info("onboarding done: {}", o.getOnbNo());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrOnboarding returnStep(String onbId, String reason) {
        HrOnboarding o = requireOnb(onbId);
        if (HrOnboarding.ST_DONE.equals(o.getStatus())) {
            throw new ServiceException(422, "已完成建档单不可退回");
        }
        require(reason, "退回原因");
        if (o.getStep() != null && o.getStep() > 1) {
            o.setStep(o.getStep() - 1);
        }
        o.setStatus(HrOnboarding.ST_RETURNED);
        o.setUpdateBy(currentUser());
        o.setUpdateDate(LocalDateTime.now());
        onbDao.updateById(o);
        log.info("onboarding returned: {} step {} reason {}", o.getOnbNo(), o.getStep(), reason);
        return o;
    }

    @Override
    public List<HrOnboarding> listOnboarding(String status) {
        LambdaQueryWrapper<HrOnboarding> qw = new LambdaQueryWrapper<HrOnboarding>()
                .eq(HrOnboarding::getDelFlag, "0").orderByDesc(HrOnboarding::getCreateDate);
        if (status != null && !status.isBlank()) {
            qw.eq(HrOnboarding::getStatus, status);
        }
        return onbDao.selectList(qw);
    }

    @Override
    public HrOnboarding detailOnboarding(String onbId) {
        return requireOnb(onbId);
    }

    // ================================================================
    // 2.3 信息维护（BR-4.7-07 锁定 + 变更日志）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrEmployee updateEmployee(String empId, Map<String, Object> payload) {
        HrEmployee e = requireEmp(empId);
        // 锁定字段直改 422
        if (payload.containsKey("idCardNo") && !String.valueOf(payload.get("idCardNo")).equals(e.getIdCardNo())) {
            throw new ServiceException(422, "BR-4.7-07 身份证号不可直接修改，须走特殊审批（L2 特批）");
        }
        if (payload.containsKey("empNo") && !String.valueOf(payload.get("empNo")).equals(e.getEmpNo())) {
            throw new ServiceException(422, "BR-4.7-07 工号不可直接修改，须走特殊审批（L2 特批）");
        }
        String reason = str(payload.get("reason"));
        require(reason, "变更原因");
        // 可变字段白名单逐项比对留痕
        if (payload.containsKey("name") && !eq(payload.get("name"), e.getName())) {
            logChange(e, "姓名", e.getName(), str(payload.get("name")), reason);
            e.setName(str(payload.get("name")));
        }
        if (payload.containsKey("phone") && !eq(payload.get("phone"), e.getPhone())) {
            logChange(e, "联系方式", e.getPhone(), str(payload.get("phone")), reason);
            e.setPhone(str(payload.get("phone")));
        }
        if (payload.containsKey("address") && !eq(payload.get("address"), e.getAddress())) {
            logChange(e, "地址", e.getAddress(), str(payload.get("address")), reason);
            e.setAddress(str(payload.get("address")));
        }
        if (payload.containsKey("emergencyContact") && !eq(payload.get("emergencyContact"), e.getEmergencyContact())) {
            logChange(e, "紧急联系人", e.getEmergencyContact(), str(payload.get("emergencyContact")), reason);
            e.setEmergencyContact(str(payload.get("emergencyContact")));
        }
        if (payload.containsKey("bankCardNo") && !eq(payload.get("bankCardNo"), e.getBankCardNo())) {
            logChange(e, "银行卡号", mask(e.getBankCardNo()), mask(str(payload.get("bankCardNo"))), reason);
            e.setBankCardNo(str(payload.get("bankCardNo")));
        }
        if (payload.containsKey("education") && !eq(payload.get("education"), e.getEducation())) {
            logChange(e, "学历学位", e.getEducation(), str(payload.get("education")), reason);
            e.setEducation(str(payload.get("education")));
        }
        if (payload.containsKey("deptId") && !eq(payload.get("deptId"), e.getDeptId())) {
            logChange(e, "所属部门", e.getDeptId(), str(payload.get("deptId")), reason);
            e.setDeptId(str(payload.get("deptId")));
        }
        if (payload.containsKey("posId") && !eq(payload.get("posId"), e.getPosId())) {
            String newPosId = str(payload.get("posId"));
            HrPosition pos = posDao.selectById(newPosId);
            if (pos == null || !"1".equals(pos.getStatus())) {
                throw new ServiceException(422, "岗位不存在或已停用");
            }
            logChange(e, "岗位", e.getPosId(), newPosId, reason);
            e.setPosId(newPosId);
        }
        if (payload.containsKey("costCenter") && !eq(payload.get("costCenter"), e.getCostCenter())) {
            logChange(e, "成本中心", e.getCostCenter(), str(payload.get("costCenter")), reason);
            e.setCostCenter(str(payload.get("costCenter")));
        }
        e.setUpdateBy(currentUser());
        e.setUpdateDate(LocalDateTime.now());
        empDao.updateById(e);
        return e;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> requestLockChange(String empId, String field, String newValue, String reason) {
        HrEmployee e = requireEmp(empId);
        boolean isIdCard = "idCardNo".equals(field);
        boolean isEmpNo = "empNo".equals(field);
        if (!isIdCard && !isEmpNo) {
            throw new ServiceException(422, "锁定字段仅支持 idCardNo / empNo");
        }
        require(newValue, "新值");
        require(reason, "特批原因");
        if (isIdCard) {
            HrEmployee dup = empDao.selectOne(new LambdaQueryWrapper<HrEmployee>()
                    .eq(HrEmployee::getDelFlag, "0")
                    .eq(HrEmployee::getIdCardNo, newValue)
                    .ne(HrEmployee::getId, empId)
                    .last("LIMIT 1"));
            if (dup != null) {
                throw new ServiceException(422, "新身份证号已被 " + dup.getEmpNo() + " 占用");
            }
        }
        // 特批 payload 暂存于 reason 链（简化：审批通过回调按 field/newValue 应用）
        Map<String, Object> payload = Map.of("empId", empId, "field", field,
                "newValue", newValue, "reason", reason);
        String bizId = empId + ":" + field + ":" + UUID.randomUUID().toString().substring(0, 8);
        var inst = approvalEngine.submit(BIZ_LOCK_CHANGE, bizId,
                "主档锁定字段特批：" + e.getEmpNo() + " " + field, "ROLE_HR_MGR",
                List.of(List.of(ApprovalNodeSpec.sign("ROLE_HR_MGR", "HR 经理特批"))));
        // 特批内容持久化到变更日志草稿（pending 态，审批通过后应用）
        HrChangeLog pending = new HrChangeLog();
        pending.setId(uuid());
        pending.setEmpId(empId);
        pending.setFieldName("LOCK:" + field);
        pending.setOldValue(isIdCard ? mask(e.getIdCardNo()) : e.getEmpNo());
        pending.setNewValue(newValue); // 原文存档供审批回调应用（NEW_VALUE 即特批目标值）
        pending.setReason("[PENDING:" + inst.getId() + "] " + reason);
        pending.setChangeBy(currentUser());
        pending.setChangeAt(LocalDateTime.now());
        logDao.insert(pending);
        return Map.of("apprId", inst.getId(), "bizId", bizId, "payload", payload);
    }

    @Override
    public List<HrChangeLog> changeLogs(String empId) {
        return logDao.selectList(new LambdaQueryWrapper<HrChangeLog>()
                .eq(HrChangeLog::getEmpId, empId)
                .orderByDesc(HrChangeLog::getChangeAt));
    }

    /** 特批通过回调应用（ApprovalCallback 调用） */
    @Transactional(rollbackFor = Exception.class)
    public void applyLockChange(String bizId) {
        // bizId = empId:field:rand —— 从 pending 日志找对应记录应用
        String empId = bizId.substring(0, bizId.indexOf(':'));
        String field = bizId.substring(bizId.indexOf(':') + 1, bizId.lastIndexOf(':'));
        List<HrChangeLog> pendings = logDao.selectList(new LambdaQueryWrapper<HrChangeLog>()
                .eq(HrChangeLog::getEmpId, empId)
                .eq(HrChangeLog::getFieldName, "LOCK:" + field)
                .like(HrChangeLog::getReason, "[PENDING:")
                .orderByDesc(HrChangeLog::getChangeAt));
        if (pendings.isEmpty()) {
            log.warn("lock change pending not found: {}", bizId);
            return;
        }
        HrChangeLog p = pendings.get(0);
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            return;
        }
        // pending.NEW_VALUE 即特批目标值原文（requestLockChange 已保证）
        if ("empNo".equals(field)) {
            e.setEmpNo(p.getNewValue());
        } else if ("idCardNo".equals(field)) {
            e.setIdCardNo(p.getNewValue());
        }
        p.setReason(p.getReason().replace("[PENDING:", "[APPROVED:"));
        p.setChangeAt(LocalDateTime.now());
        logDao.updateById(p);
        e.setUpdateBy("approval");
        e.setUpdateDate(LocalDateTime.now());
        empDao.updateById(e);
        log.info("lock change applied: {} {} -> {}", e.getEmpNo(), field, p.getNewValue());
    }

    /** 特批通过（携带新值原文，由回调从审批上下文取） */
    @Transactional(rollbackFor = Exception.class)
    public void applyLockChangeWithNewValue(String empId, String field, String newValue) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            return;
        }
        String old = "idCardNo".equals(field) ? e.getIdCardNo() : e.getEmpNo();
        if ("idCardNo".equals(field)) {
            e.setIdCardNo(newValue);
        } else {
            e.setEmpNo(newValue);
        }
        logChange(e, "idCardNo".equals(field) ? "身份证号（特批）" : "工号（特批）",
                old, newValue, "锁定字段 L2 特批通过");
        e.setUpdateBy("approval");
        e.setUpdateDate(LocalDateTime.now());
        empDao.updateById(e);
    }

    // ================================================================
    // 2.4 查询与提醒
    // ================================================================

    @Override
    public List<Map<String, Object>> listEmployees(String deptId, String posId, String status, String keyword) {
        LambdaQueryWrapper<HrEmployee> qw = new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .ne(HrEmployee::getEmpStatus, HrEmployee.ST_RESIGNED); // 默认在职（试用+正式）
        if (deptId != null && !deptId.isBlank()) {
            qw.eq(HrEmployee::getDeptId, deptId);
        }
        if (posId != null && !posId.isBlank()) {
            qw.eq(HrEmployee::getPosId, posId);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrEmployee::getEmpStatus, status);
        }
        if (keyword != null && !keyword.isBlank()) {
            qw.and(w -> w.like(HrEmployee::getName, keyword).or().like(HrEmployee::getEmpNo, keyword));
        }
        qw.orderByAsc(HrEmployee::getEmpNo);
        List<Map<String, Object>> out = new ArrayList<>();
        int remindDays = intParam("HR_PROBATION_REMIND_DAYS", 15);
        for (HrEmployee e : empDao.selectList(qw)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employee", e);
            if (HrEmployee.ST_PROBATION.equals(e.getEmpStatus()) && e.getProbationEnd() != null) {
                long left = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), e.getProbationEnd());
                row.put("probationDaysLeft", left);
                row.put("probationWarning", left >= 0 && left <= remindDays);
            }
            out.add(row);
        }
        return out;
    }

    @Override
    public Map<String, Object> detailEmployee(String empId) {
        HrEmployee e = requireEmp(empId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("employee", e);
        out.put("changeLogs", changeLogs(empId));
        // 关联建档单
        HrOnboarding onb = onbDao.selectOne(new LambdaQueryWrapper<HrOnboarding>()
                .eq(HrOnboarding::getEmpId, empId)
                .eq(HrOnboarding::getDelFlag, "0")
                .last("LIMIT 1"));
        out.put("onboarding", onb);
        // 转正/调岗历史
        out.put("transfers", transferDao.selectList(new LambdaQueryWrapper<HrTransfer>()
                .eq(HrTransfer::getEmpId, empId)
                .eq(HrTransfer::getDelFlag, "0")
                .orderByDesc(HrTransfer::getCreateDate)));
        return out;
    }

    @Override
    public List<Map<String, Object>> probationRemindings() {
        int remindDays = intParam("HR_PROBATION_REMIND_DAYS", 15);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrEmployee e : empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .eq(HrEmployee::getEmpStatus, HrEmployee.ST_PROBATION)
                .isNotNull(HrEmployee::getProbationEnd))) {
            long left = java.time.temporal.ChronoUnit.DAYS.between(today, e.getProbationEnd());
            if (left >= 0 && left <= remindDays) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("empNo", e.getEmpNo());
                row.put("name", e.getName());
                row.put("hireDate", e.getHireDate());
                row.put("probationEnd", e.getProbationEnd());
                row.put("posId", e.getPosId());
                row.put("daysLeft", left);
                // 缝合 add-org-management D5：推送目标 = 部门主管（无主管放行标注）
                HrDept dept = e.getDeptId() == null ? null : deptDao.selectById(e.getDeptId());
                HrEmployee mgr = dept == null || dept.getManagerEmpId() == null ? null
                        : empDao.selectById(dept.getManagerEmpId());
                row.put("managerEmpNo", mgr == null ? null : mgr.getEmpNo());
                row.put("managerName", mgr == null ? null : mgr.getName());
                out.add(row);
                // 写提醒记录（同员工同日 UK 防重）
                try {
                    HrRemind r = new HrRemind();
                    r.setId(uuid());
                    r.setRemindType(HrRemind.TYPE_PROBATION);
                    r.setEmpId(e.getId());
                    r.setEmpName(e.getName());
                    r.setRemindDate(today);
                    r.setDetail("试用期剩 " + left + " 天（截止 " + e.getProbationEnd() + "）");
                    r.setCreateBy("system");
                    remindDao.insert(r);
                } catch (Exception ignored) {
                    // 同日已提醒（UK 冲突）跳过
                }
                // 二次提醒档（FR-4.7-4-1 异常处理）：到期前 7 天仍未处理 → 再催主管 + 抄送 HR 经理
                int secondDays = intParam("HR_PROBATION_REMIND_DAYS2", 7);
                if (left <= secondDays) {
                    try {
                        HrRemind r2 = new HrRemind();
                        r2.setId(uuid());
                        r2.setRemindType(HrRemind.TYPE_PROBATION_SECOND);
                        r2.setEmpId(e.getId());
                        r2.setEmpName(e.getName());
                        r2.setRemindDate(today);
                        r2.setDetail("转正二次提醒：试用期剩 " + left + " 天（截止 " + e.getProbationEnd()
                                + "），已抄送 HR 经理，请主管尽快评价");
                        r2.setCreateBy("system");
                        remindDao.insert(r2);
                    } catch (Exception ignored) {
                        // 同日已提醒（UK 冲突）跳过
                    }
                }
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> rehireProbe(String idCardNo) {
        require(idCardNo, "身份证号");
        HrEmployee e = empDao.selectOne(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getDelFlag, "0")
                .eq(HrEmployee::getIdCardNo, idCardNo)
                .last("LIMIT 1"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", e != null);
        if (e != null) {
            out.put("empNo", e.getEmpNo());
            out.put("name", e.getName());
            out.put("status", e.getEmpStatus());
            out.put("rehireAllowed", HrEmployee.ST_RESIGNED.equals(e.getEmpStatus()));
        }
        return out;
    }

    // ================================================================
    // 内部
    // ================================================================

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

    private String nextNo(String prefix, java.util.function.Function<String, List<String>> nosFn) {
        List<String> existing = nosFn.apply(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                // 格式 PREFIX-0001：跳过分隔符取流水
                String tail = no.length() > prefix.length() ? no.substring(prefix.length()) : "";
                max = Math.max(max, Integer.parseInt(tail.replaceAll("\\D", "")));
            } catch (Exception ignored) {
                // 非数字后缀跳过
            }
        }
        return prefix + "-" + String.format("%04d", max + 1);
    }

    private String nextEmpNo() {
        String prefix = sysParamService.getValue("HR_EMP_NO_PREFIX");
        if (prefix == null || prefix.isBlank()) {
            prefix = "EMP";
        }
        String full = prefix + LocalDate.now().getYear();
        List<String> existing = empDao.selectNosByPrefix(full + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(full.length())));
            } catch (Exception ignored) {
                // 非数字跳过
            }
        }
        return full + String.format("%04d", max + 1);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCandidate(String json) {
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            throw new ServiceException(422, "候选人信息缺失或非法");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonSafe(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseDocs(String docsJson) {
        try {
            return MAPPER.readValue(docsJson,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (Exception e) {
            throw new ServiceException(422, "证件清单 JSON 非法");
        }
    }

    private static long monthsBetween(LocalDate start, LocalDate end) {
        return java.time.temporal.ChronoUnit.MONTHS.between(start, end);
    }

    private HrOnboarding requireOnb(String id) {
        HrOnboarding o = onbDao.selectById(id);
        if (o == null) {
            throw new ServiceException(404, "建档单不存在：" + id);
        }
        return o;
    }

    private HrEmployee requireEmp(String id) {
        HrEmployee e = empDao.selectById(id);
        if (e == null) {
            throw new ServiceException(404, "员工档案不存在：" + id);
        }
        return e;
    }

    private int intParam(String key, int dflt) {
        String v = sysParamService.getValue(key);
        try {
            return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return dflt;
        }
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

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return LocalDate.parse(s.length() >= 10 ? s.substring(0, 10) : s);
    }

    private static String mask(String v) {
        if (v == null || v.length() < 8) {
            return v;
        }
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : String.valueOf(a).equals(String.valueOf(b));
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
