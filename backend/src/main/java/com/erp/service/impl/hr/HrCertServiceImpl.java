package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrCertCategoryDao;
import com.erp.dao.hr.HrEmployeeCertDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrRemindDao;
import com.erp.dao.hr.HrScheduleDao;
import com.erp.dao.hr.HrShiftDao;
import com.erp.dao.system.HrSysAttachmentDao;
import com.erp.entity.hr.HrCertCategory;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrEmployeeCert;
import com.erp.entity.hr.HrRemind;
import com.erp.entity.hr.HrSchedule;
import com.erp.entity.hr.HrShift;
import com.erp.entity.system.HrSysAttachment;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrCertService;
import com.erp.service.hr.HrSkillMatrixService;
import com.erp.service.system.NoticeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 资格认证实现（qualification-certification）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrCertServiceImpl implements HrCertService {

    private final HrCertCategoryDao cateDao;
    private final HrEmployeeCertDao certDao;
    private final HrEmployeeDao empDao;
    private final HrScheduleDao scheduleDao;
    private final HrShiftDao shiftDao;
    private final HrRemindDao remindDao;
    private final HrSysAttachmentDao attachmentDao;
    private final HrSkillMatrixService matrixService;
    private final NoticeService noticeService;
    private final SysParamService sysParamService;

    private static final String BIZ_TRAIN_CERT = HrSysAttachment.BIZ_TRAIN_CERT;

    // ================================================================
    // 类别字典
    // ================================================================

    @Override
    public List<HrCertCategory> categoryList() {
        return cateDao.selectList(new LambdaQueryWrapper<HrCertCategory>()
                .eq(HrCertCategory::getDelFlag, "0")
                .orderByAsc(HrCertCategory::getCateCode));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrCertCategory createCategory(Map<String, Object> payload) {
        String name = str(payload.get("cateName"));
        require(name, "类别名称");
        HrCertCategory c = new HrCertCategory();
        c.setId(uuid());
        c.setCateCode(nextCode());
        c.setCateName(name);
        c.setIsKey("1".equals(String.valueOf(payload.get("isKey"))) ? "1" : "0");
        c.setSkillCode(str(payload.get("skillCode")));
        c.setSkillLevel(str(payload.get("skillLevel")));
        c.setStatus(HrCertCategory.ST_ACTIVE);
        c.setCreateBy(currentUser());
        cateDao.insert(c);
        log.info("cert category created: {} {}", c.getCateCode(), name);
        return c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrCertCategory updateCategory(String id, Map<String, Object> payload) {
        HrCertCategory c = requireCate(id);
        String code = str(payload.get("cateCode"));
        if (code != null && !code.isBlank() && !code.equals(c.getCateCode())) {
            throw new ServiceException(422, "类别编码创建后不可修改");
        }
        if (payload.containsKey("cateName")) {
            String name = str(payload.get("cateName"));
            require(name, "类别名称");
            c.setCateName(name);
        }
        if (payload.containsKey("isKey")) {
            c.setIsKey("1".equals(String.valueOf(payload.get("isKey"))) ? "1" : "0");
        }
        if (payload.containsKey("skillCode")) {
            c.setSkillCode(str(payload.get("skillCode")));
        }
        if (payload.containsKey("skillLevel")) {
            c.setSkillLevel(str(payload.get("skillLevel")));
        }
        if (payload.containsKey("status")) {
            String st = str(payload.get("status"));
            if (!HrCertCategory.ST_ACTIVE.equals(st) && !HrCertCategory.ST_DISABLED.equals(st)) {
                throw new ServiceException(422, "状态非法（ACTIVE/DISABLED）");
            }
            c.setStatus(st);
        }
        c.setUpdateBy(currentUser());
        c.setUpdateDate(LocalDateTime.now());
        cateDao.updateById(c);
        return c;
    }

    // ================================================================
    // 证书台账
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrEmployeeCert submit(String empId, Map<String, Object> payload) {
        HrEmployee emp = empDao.selectById(empId);
        if (emp == null) {
            throw new ServiceException(404, "员工不存在：" + empId);
        }
        String cateId = str(payload.get("cateId"));
        require(cateId, "证书类别");
        HrCertCategory cate = requireCate(cateId);
        if (!HrCertCategory.ST_ACTIVE.equals(cate.getStatus())) {
            throw new ServiceException(422, "类别已停用，不可提交新证书");
        }
        // 同类别唯一有效：存在 VALID/REVIEW_DUE 时拒绝（须先换证归档旧证）
        Long inFlight = certDao.selectCount(new LambdaQueryWrapper<HrEmployeeCert>()
                .eq(HrEmployeeCert::getEmpId, empId)
                .eq(HrEmployeeCert::getCateId, cateId)
                .in(HrEmployeeCert::getStatus, HrEmployeeCert.ST_VALID, HrEmployeeCert.ST_REVIEW_DUE));
        if (inFlight != null && inFlight > 0) {
            throw new ServiceException(422, "该类别已有有效证书，请先办理换证（归档旧证）后再提交");
        }
        LocalDate validTo = date(payload.get("validTo"));
        requireDate(validTo, "有效期至");
        HrEmployeeCert c = new HrEmployeeCert();
        c.setId(uuid());
        c.setEmpId(empId);
        c.setEmpName(emp.getName());
        c.setEmpNo(emp.getEmpNo());
        c.setCateId(cateId);
        c.setCateName(cate.getCateName());
        c.setCertNo(str(payload.get("certNo")));
        c.setIssueOrg(str(payload.get("issueOrg")));
        c.setValidFrom(date(payload.get("validFrom")));
        c.setValidTo(validTo);
        c.setStatus(HrEmployeeCert.ST_PENDING);
        c.setCreateBy(currentUser());
        certDao.insert(c);
        log.info("cert submitted: {} {} {}", empId, cate.getCateName(), c.getId());
        return c;
    }

    @Override
    public List<Map<String, Object>> myList(String empId) {
        List<HrEmployeeCert> certs = certDao.selectList(new LambdaQueryWrapper<HrEmployeeCert>()
                .eq(HrEmployeeCert::getEmpId, empId)
                .eq(HrEmployeeCert::getDelFlag, "0")
                .orderByDesc(HrEmployeeCert::getCreateDate));
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrEmployeeCert c : certs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cert", c);
            HrCertCategory cate = cateDao.selectById(c.getCateId());
            row.put("cate", cate);
            long daysLeft = c.getValidTo() == null ? -1 : ChronoUnit.DAYS.between(today, c.getValidTo());
            row.put("daysLeft", c.getValidTo() == null ? null : daysLeft);
            row.put("attachments", attachmentDao.selectList(new LambdaQueryWrapper<HrSysAttachment>()
                    .eq(HrSysAttachment::getBizType, BIZ_TRAIN_CERT)
                    .eq(HrSysAttachment::getBizId, c.getId())));
            out.add(row);
        }
        return out;
    }

    @Override
    public List<HrEmployeeCert> ledger(String status, String cateId) {
        LambdaQueryWrapper<HrEmployeeCert> qw = new LambdaQueryWrapper<HrEmployeeCert>()
                .eq(HrEmployeeCert::getDelFlag, "0")
                .orderByDesc(HrEmployeeCert::getCreateDate);
        if (status != null && !status.isBlank()) {
            qw.eq(HrEmployeeCert::getStatus, status);
        }
        if (cateId != null && !cateId.isBlank()) {
            qw.eq(HrEmployeeCert::getCateId, cateId);
        }
        return certDao.selectList(qw);
    }

    @Override
    public List<HrEmployeeCert> pendingList() {
        return certDao.selectList(new LambdaQueryWrapper<HrEmployeeCert>()
                .eq(HrEmployeeCert::getDelFlag, "0")
                .eq(HrEmployeeCert::getStatus, HrEmployeeCert.ST_PENDING)
                .orderByAsc(HrEmployeeCert::getCreateDate));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrEmployeeCert audit(String id, boolean pass, String opinion) {
        HrEmployeeCert c = require(id);
        if (!HrEmployeeCert.ST_PENDING.equals(c.getStatus())) {
            throw new ServiceException(422, "仅待审核记录可审核（当前：" + c.getStatus() + "）");
        }
        c.setAuditBy(currentUser());
        c.setAuditAt(LocalDateTime.now());
        if (!pass) {
            c.setStatus(HrEmployeeCert.ST_REJECTED);
            c.setReviewOpinion(opinion);
            touch(c);
            return c;
        }
        // 同类别旧证归档（换证语义：VALID/REVIEW_DUE/EXPIRED → ARCHIVED，矩阵 CERT 行联动失效）
        for (HrEmployeeCert old : certDao.selectList(new LambdaQueryWrapper<HrEmployeeCert>()
                .eq(HrEmployeeCert::getEmpId, c.getEmpId())
                .eq(HrEmployeeCert::getCateId, c.getCateId())
                .ne(HrEmployeeCert::getId, c.getId())
                .in(HrEmployeeCert::getStatus, HrEmployeeCert.ST_VALID,
                        HrEmployeeCert.ST_REVIEW_DUE, HrEmployeeCert.ST_EXPIRED))) {
            old.setStatus(HrEmployeeCert.ST_ARCHIVED);
            touch(old);
            matrixService.expireFromCert(old);
        }
        c.setStatus(HrEmployeeCert.ST_VALID);
        c.setReviewOpinion(null);
        touch(c);
        matrixService.syncFromCert(c);
        log.info("cert approved: {} {} -> VALID", c.getId(), c.getCateName());
        return c;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrEmployeeCert archive(String id) {
        HrEmployeeCert c = require(id);
        if (HrEmployeeCert.ST_ARCHIVED.equals(c.getStatus())) {
            return c;
        }
        c.setStatus(HrEmployeeCert.ST_ARCHIVED);
        touch(c);
        matrixService.expireFromCert(c);
        log.info("cert archived (换证办理): {} {}", c.getId(), c.getCateName());
        return c;
    }

    // ================================================================
    // 每日翻转与预警
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dailyFlipScan() {
        int reviewDays = sysParamService.getInt("CERT_REVIEW_REMIND_DAYS", 60);
        LocalDate today = LocalDate.now();
        int dueCnt = 0;
        int expiredCnt = 0;
        for (HrEmployeeCert c : certDao.selectList(new LambdaQueryWrapper<HrEmployeeCert>()
                .eq(HrEmployeeCert::getDelFlag, "0")
                .in(HrEmployeeCert::getStatus, HrEmployeeCert.ST_VALID, HrEmployeeCert.ST_REVIEW_DUE))) {
            if (c.getValidTo() == null) {
                continue;
            }
            long left = ChronoUnit.DAYS.between(today, c.getValidTo());
            if (left < 0) {
                c.setStatus(HrEmployeeCert.ST_EXPIRED);
                touch(c);
                matrixService.expireFromCert(c);
                remindAndPush(c, "证书已失效：" + c.getCateName()
                        + "（到期日 " + c.getValidTo() + "），关键岗位资格失效已限制排班上岗，须重新认证（BR-4.7-06）");
                expiredCnt++;
            } else if (left <= reviewDays && HrEmployeeCert.ST_VALID.equals(c.getStatus())) {
                c.setStatus(HrEmployeeCert.ST_REVIEW_DUE);
                touch(c);
                remindAndPush(c, "证书复审提醒：" + c.getCateName()
                        + " 距到期日剩 " + left + " 天（" + c.getValidTo() + "），请尽快安排复审换证（BR-4.7-06）");
                dueCnt++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewDue", dueCnt);
        out.put("expired", expiredCnt);
        return out;
    }

    @Override
    public Map<String, Object> alertLists() {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> reviewDue = new ArrayList<>();
        List<Map<String, Object>> expired = new ArrayList<>();
        for (HrEmployeeCert c : certDao.selectList(new LambdaQueryWrapper<HrEmployeeCert>()
                .eq(HrEmployeeCert::getDelFlag, "0")
                .in(HrEmployeeCert::getStatus, HrEmployeeCert.ST_REVIEW_DUE, HrEmployeeCert.ST_EXPIRED)
                .orderByAsc(HrEmployeeCert::getValidTo))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cert", c);
            row.put("daysLeft", c.getValidTo() == null ? null : ChronoUnit.DAYS.between(today, c.getValidTo()));
            if (HrEmployeeCert.ST_REVIEW_DUE.equals(c.getStatus())) {
                reviewDue.add(row);
            } else {
                expired.add(row);
            }
        }
        // 带病排班：EXPIRED 关键证书员工 × 未来排班
        Set<String> blocked = expiredKeyCertEmpIds(null);
        Map<String, HrEmployeeCert> empCert = new HashMap<>();
        for (HrEmployeeCert c : certDao.selectExpiredKeyCerts()) {
            empCert.putIfAbsent(c.getEmpId(), c);
        }
        List<Map<String, Object>> sick = new ArrayList<>();
        for (HrSchedule s : scheduleDao.selectList(new LambdaQueryWrapper<HrSchedule>()
                .eq(HrSchedule::getDelFlag, "0")
                .eq(HrSchedule::getIsRest, "0") // 休息日无上岗语义，不进带病清单
                .ge(HrSchedule::getWorkDate, today)
                .orderByAsc(HrSchedule::getWorkDate))) {
            if (!blocked.contains(s.getEmpId())) {
                continue;
            }
            HrEmployeeCert cert = empCert.get(s.getEmpId());
            HrEmployee emp = empDao.selectById(s.getEmpId());
            HrShift shift = s.getShiftId() == null ? null : shiftDao.selectById(s.getShiftId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scheduleId", s.getId());
            row.put("empId", s.getEmpId());
            row.put("empName", emp == null ? null : emp.getName());
            row.put("empNo", emp == null ? null : emp.getEmpNo());
            row.put("deptId", emp == null ? null : emp.getDeptId());
            row.put("workDate", s.getWorkDate());
            row.put("shiftName", shift == null ? "休息" : shift.getName());
            row.put("certCate", cert == null ? null : cert.getCateName());
            row.put("certNo", cert == null ? null : cert.getCertNo());
            row.put("certValidTo", cert == null ? null : cert.getValidTo());
            sick.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reviewDue", reviewDue);
        out.put("expired", expired);
        out.put("sickSchedules", sick);
        return out;
    }

    @Override
    public Set<String> expiredKeyCertEmpIds(Collection<String> empIds) {
        Set<String> out = new HashSet<>();
        for (HrEmployeeCert c : certDao.selectExpiredKeyCerts()) {
            if (empIds == null || empIds.isEmpty() || empIds.contains(c.getEmpId())) {
                out.add(c.getEmpId());
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> expiredKeyCertDesc(String empId) {
        List<String> parts = new ArrayList<>();
        for (HrEmployeeCert c : certDao.selectExpiredKeyCerts().stream()
                .filter(x -> x.getEmpId().equals(empId)).collect(Collectors.toList())) {
            parts.add(c.getCateName() + "（编号 " + (c.getCertNo() == null ? "-" : c.getCertNo())
                    + "，到期日 " + c.getValidTo() + "）");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("desc", String.join("；", parts));
        out.put("count", parts.size());
        return out;
    }

    // ================================================================
    // internals
    // ================================================================

    /** 翻转日提醒：hr_remind（UK 防重）+ 站内推送（HR 角色 + 员工本人） */
    private void remindAndPush(HrEmployeeCert c, String detail) {
        try {
            HrRemind r = new HrRemind();
            r.setId(uuid());
            r.setRemindType(HrRemind.TYPE_CERT_REVIEW);
            r.setEmpId(c.getEmpId());
            r.setEmpName(c.getEmpName());
            r.setRemindDate(LocalDate.now());
            r.setDetail(detail);
            r.setCreateBy("system");
            remindDao.insert(r);
        } catch (Exception ignored) {
            // 同日已提醒（UK）跳过
        }
        HrEmployee emp = empDao.selectById(c.getEmpId());
        String user = emp == null ? null : emp.getUserId();
        try {
            noticeService.push("ROLE_HR", null, "资格认证提醒-" + c.getCateName(), detail,
                    "CERT_REVIEW", c.getId());
            if (user != null && !user.isBlank()) {
                noticeService.push(null, user, "我的证书提醒-" + c.getCateName(), detail,
                        "CERT_REVIEW", c.getId());
            }
        } catch (Exception e) {
            log.warn("cert notice push failed {}: {}", c.getId(), e.getMessage());
        }
    }

    private String nextCode() {
        int max = 0;
        for (String code : cateDao.selectCodesByPrefix("CATE%")) {
            try {
                max = Math.max(max, Integer.parseInt(code.replaceAll("[^0-9]", "")));
            } catch (Exception ignored) {
                // skip
            }
        }
        return String.format("CATE%04d", max + 1);
    }

    private HrEmployeeCert require(String id) {
        HrEmployeeCert c = certDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "证书记录不存在：" + id);
        }
        return c;
    }

    private HrCertCategory requireCate(String id) {
        HrCertCategory c = cateDao.selectById(id);
        if (c == null) {
            throw new ServiceException(404, "证书类别不存在：" + id);
        }
        return c;
    }

    private void touch(HrEmployeeCert c) {
        c.setUpdateBy("system");
        c.setUpdateDate(LocalDateTime.now());
        certDao.updateById(c);
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static void requireDate(LocalDate d, String label) {
        if (d == null) {
            throw new ServiceException(422, label + "必填");
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o).trim();
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).trim().isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(String.valueOf(o).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法（yyyy-MM-dd）：" + o);
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
