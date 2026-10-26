package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.EmployeeLoanDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.fin.EmployeeLoan;
import com.erp.entity.system.SysUser;
import com.erp.service.SysParamService;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.EmployeeLoanService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 员工借款实现（M8-8-2，spec expense-reimbursement，决策④ ApprovalEngine 节点链 +
 * 决策⑥ 账龄判定实时化）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmployeeLoanServiceImpl implements EmployeeLoanService {

    public static final String BIZ_TYPE = "EmployeeLoan";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final EmployeeLoanDao loanDao;
    private final SysUserDao userDao;
    private final SysParamService sysParamService;
    private final ApprovalEngine approvalEngine;

    @Override
    @Transactional
    public Map<String, Object> apply(Map<String, Object> payload) {
        BigDecimal amount = dec(payload.get("amount"));
        String purpose = str(payload.get("purpose")).trim();
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "借款金额必须大于 0");
        }
        if (purpose.isEmpty()) {
            throw new ServiceException(422, "借款事由必填");
        }
        String employeeId = hasText(str(payload.get("employeeId")))
                ? str(payload.get("employeeId")) : currentUser();
        String employeeName = employeeName(employeeId);

        EmployeeLoan loan = new EmployeeLoan();
        loan.setId(uuid());
        loan.setLoanNo(nextLoanNo());
        loan.setEmployeeId(employeeId);
        loan.setEmployeeName(employeeName);
        loan.setPurpose(purpose);
        loan.setAmount(amount);
        String expected = str(payload.get("expectedDate"));
        if (hasText(expected)) {
            try {
                loan.setExpectedDate(LocalDate.parse(expected));
            } catch (RuntimeException e) {
                throw new ServiceException(422, "预计核销日期格式须为 yyyy-MM-dd");
            }
        }
        loan.setOutstandingBalance(BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
        loan.setStatus(EmployeeLoan.ST_PENDING);
        loan.setRemark(str(payload.get("remark")));
        loan.setCreateBy(currentUser());
        loan.setCreateDate(LocalDateTime.now());
        loan.setVerNo(0);
        loanDao.insert(loan);

        // 决策⑥：实时超期判定（> LOAN_OVERDUE_DAYS 默认 90）→ 加签财务主管（C-4.6-14）
        int overdue = overdueDaysOf(employeeId);
        boolean doubleSign = overdue > 0;
        String snapshot = doubleSign
                ? String.format("（超期判定：%d 天未核销借款 > %d 天阈值，已加签财务主管双签解冻）",
                    overdue, sysParamService.getInt("LOAN_OVERDUE_DAYS", 90))
                : "（超期判定：无超期未核销借款，常规部门主管审批）";
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_DEPT_HEAD", "部门主管审批")));
        if (doubleSign) {
            chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务主管双签（前款不清后款不借）")));
        }
        var inst = approvalEngine.submit(BIZ_TYPE, loan.getId(),
                "员工借款 " + amount.toPlainString() + "：" + purpose + " [" + employeeName + "]" + snapshot,
                "ROLE_FINANCE_MGR", chain);
        loan.setApprId(inst.getId());
        loanDao.updateById(loan);

        log.info("employee loan {} submitted: {} by {} overdue={} chain={}",
                loan.getLoanNo(), amount, employeeId, overdue, doubleSign ? 2 : 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", loan.getId());
        out.put("loanNo", loan.getLoanNo());
        out.put("apprId", inst.getId());
        out.put("overdueDetected", doubleSign);
        out.put("overdueDays", overdue);
        out.put("chainLength", chain.size());
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String employeeId, String status) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmployeeLoan l : loanDao.selectList(new LambdaQueryWrapper<EmployeeLoan>()
                .eq(hasText(employeeId), EmployeeLoan::getEmployeeId, employeeId)
                .eq(hasText(status), EmployeeLoan::getStatus, status)
                .orderByDesc(EmployeeLoan::getCreateDate))) {
            out.add(row(l));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        EmployeeLoan l = loanDao.selectById(id);
        if (l == null) {
            throw new ServiceException(404, "借款单不存在");
        }
        Map<String, Object> out = row(l);
        out.put("overdueDays", agingOf(l));
        return out;
    }

    @Override
    public List<Map<String, Object>> agingView() {
        int threshold = sysParamService.getInt("LOAN_OVERDUE_DAYS", 90);
        Map<String, Map<String, Object>> byEmp = new LinkedHashMap<>();
        for (EmployeeLoan l : loanDao.selectList(new LambdaQueryWrapper<EmployeeLoan>()
                .eq(EmployeeLoan::getStatus, EmployeeLoan.ST_PAID)
                .gt(EmployeeLoan::getOutstandingBalance, BigDecimal.ZERO))) {
            Map<String, Object> m = byEmp.computeIfAbsent(l.getEmployeeId(), k -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("employeeId", l.getEmployeeId());
                row.put("employeeName", l.getEmployeeName());
                row.put("loanCount", 0);
                row.put("outstandingTotal", BigDecimal.ZERO.setScale(2, java.math.RoundingMode.HALF_UP));
                row.put("maxAgingDays", 0);
                row.put("overdue", false);
                return row;
            });
            m.put("loanCount", ((Integer) m.get("loanCount")) + 1);
            m.put("outstandingTotal", ((BigDecimal) m.get("outstandingTotal")).add(
                    l.getOutstandingBalance() == null ? BigDecimal.ZERO : l.getOutstandingBalance()));
            int aging = agingOf(l);
            if (aging > (Integer) m.get("maxAgingDays")) {
                m.put("maxAgingDays", aging);
            }
            if (aging > threshold) {
                m.put("overdue", true); // 红标「需财务主管处理」（提示性，约束在 apply 加签）
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(byEmp.values());
        out.sort((a, b) -> Integer.compare((Integer) b.get("maxAgingDays"), (Integer) a.get("maxAgingDays")));
        return out;
    }

    @Override
    public int overdueDaysOf(String employeeId) {
        if (!hasText(employeeId)) {
            return 0;
        }
        Integer maxAging = loanDao.selectMaxAgingDays(employeeId);
        int threshold = sysParamService.getInt("LOAN_OVERDUE_DAYS", 90);
        return maxAging != null && maxAging > threshold ? maxAging : 0;
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 单笔账龄：PAID 且有余额 → 今日 − 放款日，否则 0 */
    private int agingOf(EmployeeLoan l) {
        if (!EmployeeLoan.ST_PAID.equals(l.getStatus())
                || l.getOutstandingBalance() == null || l.getOutstandingBalance().signum() <= 0
                || l.getPaidDate() == null) {
            return 0;
        }
        return (int) java.time.temporal.ChronoUnit.DAYS.between(l.getPaidDate(), LocalDate.now());
    }

    private String nextLoanNo() {
        String prefix = "LN" + LocalDate.now().format(DAY);
        Integer max = loanDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private String employeeName(String employeeId) {
        try {
            SysUser u = userDao.selectById(employeeId);
            if (u != null) {
                return hasText(u.getNickName()) ? u.getNickName() : u.getUsername();
            }
        } catch (RuntimeException ignore) {
            // 用户表不可用时回退 ID
        }
        return employeeId;
    }

    private Map<String, Object> row(EmployeeLoan l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("loanNo", l.getLoanNo());
        m.put("employeeId", l.getEmployeeId());
        m.put("employeeName", l.getEmployeeName());
        m.put("purpose", l.getPurpose());
        m.put("amount", l.getAmount());
        m.put("outstandingBalance", l.getOutstandingBalance());
        m.put("status", l.getStatus());
        m.put("expectedDate", l.getExpectedDate());
        m.put("paidDate", l.getPaidDate());
        m.put("apprId", l.getApprId());
        m.put("voucherId", l.getVoucherId());
        m.put("createDate", l.getCreateDate());
        return m;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static BigDecimal dec(Object o) {
        if (o == null || str(o).isBlank()) {
            return null;
        }
        return new BigDecimal(str(o));
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return "system";
        }
    }
}
