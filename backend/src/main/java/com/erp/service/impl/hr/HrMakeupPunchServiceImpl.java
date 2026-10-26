package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrAttendanceExceptionDao;
import com.erp.dao.hr.HrAttendanceOpLogDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrMakeupPunchDao;
import com.erp.dao.hr.HrPunchRecordDao;
import com.erp.entity.hr.HrAttendanceException;
import com.erp.entity.hr.HrAttendanceOpLog;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrMakeupPunch;
import com.erp.entity.hr.HrPunchRecord;
import com.erp.schedule.HrAttendanceScanTask;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrMakeupPunchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 补卡申请实现（通过后写 MANUAL 打卡 + 关闭缺卡类异常 + 重识别当日）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrMakeupPunchServiceImpl implements HrMakeupPunchService {

    private final HrMakeupPunchDao makeupDao;
    private final HrPunchRecordDao punchDao;
    private final HrAttendanceExceptionDao excDao;
    private final HrEmployeeDao empDao;
    private final HrAttendanceOpLogDao opLogDao;
    private final HrAttendanceScanTask scanTask;
    private final SysParamService sysParamService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrMakeupPunch submit(Map<String, Object> payload) {
        String empId = str(payload.get("empId"));
        LocalDate date = date(payload.get("makeupDate"));
        String dir = str(payload.get("dir"));
        String reason = str(payload.get("reason"));
        if (empId == null || empId.isBlank()) {
            empId = currentEmpId();
        }
        if (date == null) {
            throw new ServiceException(422, "补卡日期必填");
        }
        require(reason, "补卡原因");
        if (!HrPunchRecord.DIR_IN.equals(dir) && !HrPunchRecord.DIR_OUT.equals(dir)) {
            throw new ServiceException(422, "补卡方向非法（IN/OUT）");
        }
        int deadline = intParam("MAKEUP_DEADLINE_DAYS", 3);
        long workDays = workDaysBetween(date, LocalDate.now());
        if (workDays > deadline) {
            throw new ServiceException(422, "补卡期限 " + deadline + " 工作日已过（逾期默认接受）");
        }
        // 同日同向已有打卡 → 不可补
        long dup = punchDao.selectCount(new LambdaQueryWrapper<HrPunchRecord>()
                .eq(HrPunchRecord::getEmpId, empId)
                .eq(HrPunchRecord::getPunchDate, date)
                .eq(HrPunchRecord::getDir, dir)
                .eq(HrPunchRecord::getDelFlag, "0"));
        if (dup > 0) {
            throw new ServiceException(422, "该日该方向已有打卡记录，无需补卡");
        }
        HrMakeupPunch m = new HrMakeupPunch();
        m.setId(uuid());
        m.setEmpId(empId);
        m.setMakeupDate(date);
        m.setDir(dir);
        m.setReason(reason);
        m.setStatus(HrMakeupPunch.ST_PENDING);
        m.setCreateBy(currentUser());
        m.setCreateDate(LocalDateTime.now());
        makeupDao.insert(m);
        log.info("makeup punch submitted: {} {} {}", empId, date, dir);
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrMakeupPunch mgrConfirm(String makeupId, Map<String, Object> payload) {
        HrMakeupPunch m = require(makeupId);
        if (!HrMakeupPunch.ST_PENDING.equals(m.getStatus())) {
            throw new ServiceException(422, "补卡单状态非法（当前：" + m.getStatus() + "）");
        }
        boolean approve = !"false".equals(String.valueOf(payload.get("approve")));
        String opinion = str(payload.get("opinion"));
        if (approve) {
            m.setMgrOpinion(opinion == null ? "主管确认通过" : opinion);
            m.setMgrBy(currentUser());
            // 保持 PENDING 等 HR 终审（两级：主管→HR）
        } else {
            m.setStatus(HrMakeupPunch.ST_REJECTED);
            m.setMgrOpinion("驳回：" + (opinion == null ? "" : opinion));
            m.setMgrBy(currentUser());
        }
        m.setUpdateBy(currentUser());
        m.setUpdateDate(LocalDateTime.now());
        makeupDao.updateById(m);
        return m;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public HrMakeupPunch hrReview(String makeupId, Map<String, Object> payload) {
        HrMakeupPunch m = require(makeupId);
        if (!HrMakeupPunch.ST_PENDING.equals(m.getStatus()) || m.getMgrBy() == null) {
            throw new ServiceException(422, "须先经主管确认再 HR 审核");
        }
        boolean approve = !"false".equals(String.valueOf(payload.get("approve")));
        String opinion = str(payload.get("opinion"));
        if (!approve) {
            m.setStatus(HrMakeupPunch.ST_REJECTED);
            m.setHrOpinion("驳回：" + (opinion == null ? "" : opinion));
            m.setHrBy(currentUser());
            m.setUpdateBy(currentUser());
            m.setUpdateDate(LocalDateTime.now());
            makeupDao.updateById(m);
            return m;
        }
        // 通过：写 MANUAL 打卡（时刻取班次上下班时间或缺省 09:00/18:00）
        HrEmployee e = empDao.selectById(m.getEmpId());
        String punchTime = HrPunchRecord.DIR_IN.equals(m.getDir()) ? "09:00:00" : "18:00:00";
        HrPunchRecord p = new HrPunchRecord();
        p.setId(uuid());
        p.setEmpId(m.getEmpId());
        p.setPunchDate(m.getMakeupDate());
        p.setPunchTime(punchTime);
        p.setDir(m.getDir());
        p.setSource(HrPunchRecord.SRC_MANUAL);
        p.setSuspicious("0");
        p.setRemark("补卡单 " + m.getId());
        p.setCreateBy(currentUser());
        p.setCreateDate(LocalDateTime.now());
        punchDao.insert(p);
        // 关闭该日全部 IDENTIFIED 异常（重识别后按补录打卡重建准确集合——spec：补卡通过自动关闭+重识别不复现）
        List<HrAttendanceException> excs = excDao.selectList(new LambdaQueryWrapper<HrAttendanceException>()
                .eq(HrAttendanceException::getEmpId, m.getEmpId())
                .eq(HrAttendanceException::getExcDate, m.getMakeupDate())
                .eq(HrAttendanceException::getStatus, HrAttendanceException.ST_IDENTIFIED)
                .eq(HrAttendanceException::getDelFlag, "0"));
        for (HrAttendanceException x : excs) {
            // 软删关闭（delFlag 为 @TableLogic，updateById 不改该列——须 deleteById）
            excDao.deleteById(x.getId());
        }
        m.setStatus(HrMakeupPunch.ST_APPROVED);
        m.setHrOpinion(opinion == null ? "HR 审核通过" : opinion);
        m.setHrBy(currentUser());
        m.setPunchId(p.getId());
        m.setUpdateBy(currentUser());
        m.setUpdateDate(LocalDateTime.now());
        makeupDao.updateById(m);
        // 重识别当日（幂等：补录打卡后异常不复现）
        try {
            scanTask.scanForDate(m.getMakeupDate());
        } catch (Exception ex) {
            log.warn("makeup re-scan failed: {}", ex.getMessage());
        }
        HrAttendanceOpLog l = new HrAttendanceOpLog();
        l.setId(uuid());
        l.setOpType("MAKEUP");
        l.setOperator(currentUser());
        l.setOpAt(LocalDateTime.now());
        l.setApplicableIdsJson("[\"" + m.getId() + "\"]");
        l.setReason("补卡通过：" + m.getMakeupDate() + " " + m.getDir());
        opLogDao.insert(l);
        log.info("makeup approved: {} punch {}", m.getId(), p.getId());
        return m;
    }

    @Override
    public List<HrMakeupPunch> list(String empId, String status) {
        LambdaQueryWrapper<HrMakeupPunch> qw = new LambdaQueryWrapper<HrMakeupPunch>()
                .eq(HrMakeupPunch::getDelFlag, "0")
                .orderByDesc(HrMakeupPunch::getCreateDate);
        if (empId != null && !empId.isBlank()) {
            qw.eq(HrMakeupPunch::getEmpId, empId);
        }
        if (status != null && !status.isBlank()) {
            qw.eq(HrMakeupPunch::getStatus, status);
        }
        return makeupDao.selectList(qw);
    }

    // ================================================================
    // helpers
    // ================================================================

    private String currentEmpId() {
        String username = currentUser();
        HrEmployee e = one(empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getEmpNo, username)
                .eq(HrEmployee::getDelFlag, "0")));
        if (e == null) {
            throw new ServiceException(404, "未找到当前用户对应员工主档：" + username);
        }
        return e.getId();
    }

    private long workDaysBetween(LocalDate from, LocalDate to) {
        long days = 0;
        LocalDate d = from.plusDays(1);
        while (!d.isAfter(to)) {
            if (d.getDayOfWeek().getValue() < 6) {
                days++;
            }
            d = d.plusDays(1);
        }
        return days;
    }

    private HrMakeupPunch require(String id) {
        HrMakeupPunch m = makeupDao.selectById(id);
        if (m == null) {
            throw new ServiceException(404, "补卡单不存在：" + id);
        }
        return m;
    }

    private int intParam(String key, int def) {
        String v = sysParamService.getValue(key);
        try {
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static HrEmployee one(List<HrEmployee> list) {
        return list.isEmpty() ? null : list.get(0);
    }

    private static void require(String v, String label) {
        if (v == null || v.trim().isEmpty()) {
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
            throw new ServiceException(422, "日期格式非法：" + o);
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
