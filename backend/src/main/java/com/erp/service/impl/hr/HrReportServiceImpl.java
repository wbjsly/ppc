package com.erp.service.impl.hr;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.hr.HrChangeLogDao;
import com.erp.dao.hr.HrDeptDao;
import com.erp.dao.hr.HrEmployeeDao;
import com.erp.entity.hr.HrChangeLog;
import com.erp.entity.hr.HrDept;
import com.erp.entity.hr.HrEmployee;
import com.erp.service.SysParamService;
import com.erp.service.hr.HrReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 员工直接汇报关系（M10-2-3，design D2）：
 * 设置 REPORT_TO 时沿祖先链上溯防环（命中本人即环）；深度超 HR_REPORT_DEPTH_MAX 拒绝；
 * 变更写 hr_change_log 留痕。部门主管（两层模型部门层）由 hr_dept.MANAGER_EMP_ID 承载，
 * 本服务不重复建模。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HrReportServiceImpl implements HrReportService {

    private final HrEmployeeDao empDao;
    private final HrDeptDao deptDao;
    private final HrChangeLogDao logDao;
    private final SysParamService sysParamService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> setReportTo(String empId, String reportToEmpId, String reason) {
        HrEmployee e = require(empId);
        HrEmployee old = e.getReportToEmpId() == null ? null
                : empDao.selectById(e.getReportToEmpId());
        String oldReportTo = e.getReportToEmpId();
        HrEmployee newMgr = null;

        if (reportToEmpId == null || reportToEmpId.isBlank()) {
            // 清空汇报人
            e.setReportToEmpId(null);
        } else {
            // 自汇报 422
            if (empId.equals(reportToEmpId)) {
                throw new ServiceException(422, "不可将直接汇报人设置为本人");
            }
            HrEmployee mgr = empDao.selectById(reportToEmpId);
            newMgr = mgr;
            if (mgr == null) {
                throw new ServiceException(404, "汇报人不存在：" + reportToEmpId);
            }
            if ("RESIGNED".equals(mgr.getEmpStatus())) {
                throw new ServiceException(422, "汇报人已离职");
            }
            // 环路 422：沿 reportTo 的祖先链上溯，命中 empId 即环
            int max = depthLimit();
            String cur = reportToEmpId;
            int depth = 0;
            while (cur != null && depth <= max) {
                if (empId.equals(cur)) {
                    throw new ServiceException(422, "汇报关系成环（" + displayName(mgr)
                            + " 已在 " + displayName(e) + " 的汇报链上）");
                }
                HrEmployee up = empDao.selectById(cur);
                cur = up == null ? null : up.getReportToEmpId();
                depth++;
            }
            if (depth > max) {
                throw new ServiceException(422, "汇报链深度超过上限 " + max + "（HR_REPORT_DEPTH_MAX）");
            }
            e.setReportToEmpId(reportToEmpId);
        }
        e.setUpdateBy(currentUser());
        e.setUpdateDate(LocalDateTime.now());
        empDao.updateById(e);

        // 变更留痕（BR-4.7-07 口径）
        HrChangeLog cl = new HrChangeLog();
        cl.setEmpId(empId);
        cl.setFieldName("直接汇报人");
        cl.setOldValue(old == null ? "" : displayName(old) + "(" + oldReportTo + ")");
        cl.setNewValue(reportToEmpId == null || reportToEmpId.isBlank() ? "" : displayName(mgr(reportToEmpId)) + "(" + reportToEmpId + ")");
        cl.setReason(reason == null || reason.isBlank() ? "汇报关系维护" : reason);
        cl.setChangeBy(currentUser());
        cl.setChangeAt(LocalDateTime.now());
        logDao.insert(cl);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("empId", empId);
        out.put("reportToEmpId", e.getReportToEmpId());
        out.put("logId", cl.getId());
        log.info("report line updated: {} -> {}", e.getEmpNo(), reportToEmpId);
        return out;
    }

    @Override
    public Map<String, Object> ancestors(String empId) {
        HrEmployee e = require(empId);
        int max = depthLimit();
        List<Map<String, Object>> chain = new ArrayList<>();
        String cur = e.getReportToEmpId();
        int depth = 0;
        while (cur != null && depth < max) {
            HrEmployee up = empDao.selectById(cur);
            if (up == null) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("empId", up.getId());
            row.put("empNo", up.getEmpNo());
            row.put("name", up.getName());
            chain.add(row);
            cur = up.getReportToEmpId();
            depth++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("empId", empId);
        out.put("depth", chain.size());
        out.put("ancestors", chain);
        return out;
    }

    @Override
    public List<Map<String, Object>> directReports(String empId) {
        require(empId);
        List<HrEmployee> reports = empDao.selectList(new LambdaQueryWrapper<HrEmployee>()
                .eq(HrEmployee::getReportToEmpId, empId)
                .eq(HrEmployee::getDelFlag, "0")
                .orderByAsc(HrEmployee::getEmpNo));
        List<Map<String, Object>> out = new ArrayList<>();
        for (HrEmployee r : reports) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("empId", r.getId());
            row.put("empNo", r.getEmpNo());
            row.put("name", r.getName());
            row.put("empStatus", r.getEmpStatus());
            out.add(row);
        }
        return out;
    }

    // ================================================================
    // helpers
    // ================================================================

    private HrEmployee mgr(String id) {
        return empDao.selectById(id);
    }

    private String displayName(HrEmployee e) {
        return e == null ? "" : e.getName();
    }

    private HrEmployee require(String empId) {
        HrEmployee e = empDao.selectById(empId);
        if (e == null) {
            throw new ServiceException(404, "员工不存在：" + empId);
        }
        return e;
    }

    private int depthLimit() {
        String v = sysParamService.getValue("HR_REPORT_DEPTH_MAX");
        try {
            return v == null ? 10 : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 10;
        }
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
