package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrCertCategoryDao;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeCertDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.dao.hr.HrScheduleDao;
import com.erp.dao.hr.HrShiftDao;
import com.erp.entity.hr.HrCertCategory;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.entity.hr.HrEmployeeCert;
import com.erp.entity.hr.HrSchedule;
import com.erp.entity.hr.HrShift;
import com.erp.service.hr.HrCertService;
import com.erp.service.hr.HrTrainSickScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 带病排班处置实现（attendance-core 门禁存量：不回溯删除，页面处置）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrTrainSickScheduleServiceImpl implements HrTrainSickScheduleService {

    private final HrScheduleDao scheduleDao;
    private final HrEmployeeDao empDao;
    private final HrDeptDao deptDao;
    private final HrShiftDao shiftDao;
    private final HrEmployeeCertDao certDao;
    private final HrCertCategoryDao cateDao;
    private final HrCertService certService;

    @Override
    public List<Map<String, Object>> list() {
        Set<String> blocked = certService.expiredKeyCertEmpIds(null);
        Map<String, HrEmployeeCert> empCert = new HashMapLike();
        for (HrEmployeeCert c : certDao.selectExpiredKeyCerts()) {
            empCert.putIfAbsent(c.getEmpId(), c);
        }
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrSchedule s : scheduleDao.selectList(new LambdaQueryWrapper<HrSchedule>()
                .eq(HrSchedule::getDelFlag, "0")
                .eq(HrSchedule::getIsRest, "0") // 休息日无上岗语义，不进带病清单
                .ge(HrSchedule::getWorkDate, today)
                .orderByAsc(HrSchedule::getWorkDate))) {
            if (!blocked.contains(s.getEmpId())) {
                continue;
            }
            HrEmployee emp = empDao.selectById(s.getEmpId());
            HrShift shift = s.getShiftId() == null ? null : shiftDao.selectById(s.getShiftId());
            HrEmployeeCert cert = empCert.get(s.getEmpId());
            HrDept dept = emp == null ? null : deptDao.selectById(emp.getDeptId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("scheduleId", s.getId());
            row.put("empId", s.getEmpId());
            row.put("empName", emp == null ? null : emp.getName());
            row.put("empNo", emp == null ? null : emp.getEmpNo());
            row.put("deptId", emp == null ? null : emp.getDeptId());
            row.put("deptName", dept == null ? null : dept.getName());
            row.put("workDate", s.getWorkDate());
            row.put("shiftName", shift == null ? "休息" : shift.getName());
            row.put("certCate", cert == null ? null : cert.getCateName());
            row.put("certNo", cert == null ? null : cert.getCertNo());
            row.put("certValidTo", cert == null ? null : cert.getValidTo());
            out.add(row);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> dispose(String scheduleId, String action, String newEmpId) {
        HrSchedule s = scheduleDao.selectById(scheduleId);
        if (s == null) {
            throw new ServiceException(404, "排班不存在：" + scheduleId);
        }
        // 仅带病排班可处置（认证失效员工的未来排班）
        if (!certService.expiredKeyCertEmpIds(java.util.List.of(s.getEmpId())).contains(s.getEmpId())
                || s.getWorkDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "非带病排班（认证失效员工未来排班），无需处置");
        }
        HrEmployee emp = empDao.selectById(s.getEmpId());
        requireOperatorPermission(emp);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scheduleId", s.getId());
        out.put("action", action);
        if ("CANCEL".equalsIgnoreCase(action)) {
            scheduleDao.deleteById(s.getId()); // @TableLogic 逻辑删除
            out.put("result", "已取消该员工当日排班");
            log.info("sick schedule cancelled: {} {} {}", s.getEmpId(), s.getWorkDate(), currentUser());
            return out;
        }
        if ("REASSIGN".equalsIgnoreCase(action)) {
            if (newEmpId == null || newEmpId.isBlank()) {
                throw new ServiceException(422, "改派须指定新员工");
            }
            HrEmployee target = empDao.selectById(newEmpId);
            if (target == null || !("PROBATION".equals(target.getEmpStatus()) || "REGULAR".equals(target.getEmpStatus()))) {
                throw new ServiceException(422, "新员工不存在或非在职状态");
            }
            if (certService.expiredKeyCertEmpIds(java.util.List.of(newEmpId)).contains(newEmpId)) {
                Map<String, Object> desc = certService.expiredKeyCertDesc(newEmpId);
                throw new ServiceException(422, "新员工同样认证失效，不可改派：" + desc.get("desc"));
            }
            String oldName = emp == null ? s.getEmpId() : emp.getName();
            s.setEmpId(newEmpId);
            s.setRemark((s.getRemark() == null ? "" : s.getRemark() + "；")
                    + "带病排班改派 " + oldName + "→" + target.getName() + " by " + currentUser()
                    + " " + LocalDateTime.now().toLocalDate());
            s.setUpdateBy(currentUser());
            s.setUpdateDate(LocalDateTime.now());
            scheduleDao.updateById(s);
            out.put("result", "已改派至 " + target.getName());
            log.info("sick schedule reassigned: {} -> {} {}", s.getId(), newEmpId, currentUser());
            return out;
        }
        throw new ServiceException(422, "处置动作非法（CANCEL/REASSIGN）");
    }

    /** HR/HR_MGR/ADMIN 或该员工部门主管（hr_dept.MANAGER_EMP_ID）可处置 */
    private void requireOperatorPermission(HrEmployee emp) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未认证");
        }
        String username = auth.getName();
        boolean roleOk = auth.getAuthorities().stream().anyMatch(a ->
                "ROLE_HR".equals(a.getAuthority()) || "ROLE_HR_MGR".equals(a.getAuthority())
                        || "ROLE_ADMIN".equals(a.getAuthority()));
        if (roleOk) {
            return;
        }
        if (emp != null && emp.getDeptId() != null) {
            HrDept dept = deptDao.selectById(emp.getDeptId());
            if (dept != null && dept.getManagerEmpId() != null) {
                HrEmployee manager = empDao.selectOne(new LambdaQueryWrapper<HrEmployee>()
                        .eq(HrEmployee::getId, dept.getManagerEmpId())
                        .last("LIMIT 1"));
                if (manager != null && username.equals(manager.getUserId())) {
                    return; // 本部门主管
                }
            }
        }
        throw new ServiceException(403, "仅 HR 或该员工部门主管可处置带病排班");
    }

    /** 简单 HashMap 别名（可读性） */
    private static class HashMapLike extends java.util.HashMap<String, HrEmployeeCert> {
    }

    private static String currentUser() {
        try {
            return SecurityContextHolder.getContext().getAuthentication().getName();
        } catch (Exception e) {
            return "system";
        }
    }
}
