package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.CashDocDao;
import com.erp.dao.fin.EmployeeLoanDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.LoanWriteoffDao;
import com.erp.dao.fin.ReimburseDao;
import com.erp.dao.fin.ReimburseLineDao;
import com.erp.entity.fin.BankReceipt;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.fin.EmployeeLoan;
import com.erp.entity.fin.FinBankAccount;
import com.erp.entity.fin.Reimburse;
import com.erp.entity.fin.ReimburseLine;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.fin.ExpenseReimburseService;
import com.erp.service.fin.ExpenseTypeService;
import com.erp.service.fin.GlVoucherService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 费用报销实现（M8-8-1，spec expense-reimbursement）。
 * 决策④：审批两节点串行（部门主管→财务审核）；决策⑤：账务由 Callback 自动，
 * 资金（补付/退款）为出纳手动动作，复用收付中心机制件。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseReimburseServiceImpl implements ExpenseReimburseService {

    public static final String BIZ_TYPE = "ExpenseReimburse";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ReimburseDao reimburseDao;
    private final ReimburseLineDao lineDao;
    private final ExpenseTypeService expenseTypeService;
    private final ApprovalEngine approvalEngine;
    private final EmployeeLoanDao loanDao;
    private final LoanWriteoffDao writeoffDao;
    private final FinBankAccountDao bankDao;
    private final CashDocDao docDao;
    private final GlVoucherService voucherService;
    private final com.erp.dao.system.SysUserDao userDao;

    // ================================================================
    // 提交（3.1 + 3.4 审批两节点）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> submit(Map<String, Object> payload) {
        Object rawLines = payload.get("lines");
        if (!(rawLines instanceof List<?> list) || list.isEmpty()) {
            throw new ServiceException(422, "报销明细行不能为空");
        }
        String employeeId = hasText(str(payload.get("employeeId")))
                ? str(payload.get("employeeId")) : currentUser();

        // 行校验：金额/日期/发票必填、映射可解析、发票历史重复 L1（C-4.6-13）
        List<ReimburseLine> lines = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        java.util.Set<String> seenInvoices = new java.util.HashSet<>();
        for (Object o : list) {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) o;
            ReimburseLine ln = new ReimburseLine();
            ln.setId(uuid());
            String typeCode = str(p.get("expenseType"));
            // 映射校验（缺失/停用 → 422 提示补录，FR-4.6-7-5；凭证生成时再取一次作纵深防御）
            expenseTypeService.resolveAccountCode(typeCode);
            ln.setExpenseType(typeCode);
            BigDecimal amt = dec(p.get("amount"));
            if (amt == null || amt.signum() <= 0) {
                throw new ServiceException(422, "明细行金额必须大于 0");
            }
            ln.setAmount(amt);
            try {
                ln.setOccurDate(LocalDate.parse(str(p.get("occurDate"))));
            } catch (RuntimeException e) {
                throw new ServiceException(422, "费用发生日期格式须为 yyyy-MM-dd");
            }
            ln.setCostCenterId(str(p.get("costCenterId")));
            ln.setCostCenterName(str(p.get("costCenterName")));
            String invoiceNo = str(p.get("invoiceNo")).trim();
            if (invoiceNo.isEmpty()) {
                throw new ServiceException(422, "发票号必填（C-4.6-13 查验锚点）");
            }
            ln.setInvoiceNo(invoiceNo);
            ln.setInvoiceVendor(str(p.get("invoiceVendor")));
            // 单据内重复
            if (!seenInvoices.add(invoiceNo)) {
                throw new ServiceException(422, "本次提交中发票号重复：" + invoiceNo);
            }
            // 历史重复 L1（C-4.6-13 展示原单号）
            String occupied = lineDao.selectOccupiedByInvoiceNo(invoiceNo, null);
            if (occupied != null) {
                throw new ServiceException(422, "发票号已用于历史报销（原单 " + occupied + "），阻断提交"
                        + "（C-4.6-13：请更换发票或说明情况）");
            }
            ln.setRemark(str(p.get("remark")));
            ln.setCreateBy(currentUser());
            ln.setCreateDate(LocalDateTime.now());
            ln.setVerNo(0);
            lines.add(ln);
            sum = sum.add(amt);
        }

        BigDecimal total = dec(payload.get("totalAmount"));
        if (total != null && total.compareTo(sum) != 0) {
            throw new ServiceException(422, "报销总额 " + total.toPlainString()
                    + " 与明细行合计 " + sum.toPlainString() + " 不一致");
        }

        Reimburse r = new Reimburse();
        r.setId(uuid());
        r.setExpNo(nextExpNo());
        r.setEmployeeId(employeeId);
        r.setEmployeeName(employeeName(employeeId));
        r.setTotalAmount(sum);
        r.setStatus(Reimburse.ST_PENDING);
        r.setSettleStatus(Reimburse.SETTLE_NONE);
        r.setSettleAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        String period = str(payload.get("occurPeriod"));
        if (!period.matches("\\d{6}")) {
            period = lines.get(0).getOccurDate().format(DateTimeFormatter.ofPattern("yyyyMM"));
        }
        r.setOccurPeriod(period);
        r.setRemark(str(payload.get("remark")));
        r.setCreateBy(currentUser());
        r.setCreateDate(LocalDateTime.now());
        r.setVerNo(0);
        reimburseDao.insert(r);
        for (ReimburseLine ln : lines) {
            ln.setReimburseId(r.getId());
            ln.setExpNo(r.getExpNo());
            lineDao.insert(ln);
        }

        // 决策④：两节点串行（部门主管 → 财务审核票据合规性）
        List<List<ApprovalNodeSpec>> chain = new ArrayList<>();
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_DEPT_HEAD", "部门主管审批")));
        chain.add(List.of(ApprovalNodeSpec.sign("ROLE_FINANCE_MGR", "财务审核（票据合规性）")));
        var inst = approvalEngine.submit(BIZ_TYPE, r.getId(),
                "费用报销 " + sum.toPlainString() + "：" + r.getExpNo()
                        + " [" + r.getEmployeeName() + "]", "ROLE_FINANCE_MGR", chain);
        r.setApprId(inst.getId());
        reimburseDao.updateById(r);

        log.info("reimburse {} submitted: {} lines={} amount={} by {}",
                r.getExpNo(), r.getEmployeeName(), lines.size(), sum, currentUser());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("expNo", r.getExpNo());
        out.put("totalAmount", sum);
        out.put("lineCount", lines.size());
        out.put("apprId", inst.getId());
        return out;
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public List<Map<String, Object>> list(String status, String employeeId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Reimburse r : reimburseDao.selectList(new LambdaQueryWrapper<Reimburse>()
                .eq(hasText(status), Reimburse::getStatus, status)
                .eq(hasText(employeeId), Reimburse::getEmployeeId, employeeId)
                .orderByDesc(Reimburse::getCreateDate))) {
            out.add(row(r));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        Reimburse r = require(id);
        Map<String, Object> out = row(r);
        List<Map<String, Object>> lines = new ArrayList<>();
        for (ReimburseLine ln : lineDao.selectList(new LambdaQueryWrapper<ReimburseLine>()
                .eq(ReimburseLine::getReimburseId, id)
                .orderByAsc(ReimburseLine::getCreateDate))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("expenseType", ln.getExpenseType());
            m.put("amount", ln.getAmount());
            m.put("occurDate", ln.getOccurDate());
            m.put("costCenterName", ln.getCostCenterName());
            m.put("invoiceNo", ln.getInvoiceNo());
            m.put("invoiceVendor", ln.getInvoiceVendor());
            lines.add(m);
        }
        out.put("lines", lines);
        List<Map<String, Object>> writeoffs = new ArrayList<>();
        for (var w : writeoffDao.selectByReimburse(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("woNo", w.getWoNo());
            m.put("loanNo", w.getLoanNo());
            m.put("amount", w.getAmount());
            m.put("writeoffDate", w.getWriteoffDate());
            m.put("voucherId", w.getVoucherId());
            writeoffs.add(m);
        }
        out.put("writeoffs", writeoffs);
        out.put("settleInfo", settleInfo(id));
        return out;
    }

    @Override
    public Map<String, Object> settleInfo(String id) {
        Reimburse r = require(id);
        BigDecimal written = writeoffDao.selectSumByReimburse(id);
        BigDecimal diff = r.getTotalAmount().subtract(written == null ? BigDecimal.ZERO : written)
                .setScale(2, RoundingMode.HALF_UP);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAmount", r.getTotalAmount());
        m.put("writeoffTotal", written == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : written);
        m.put("difference", diff); // >0 待补付（决策⑤出纳手动）
        m.put("settledAmount", r.getSettleAmount());
        m.put("settleStatus", r.getSettleStatus());
        m.put("status", r.getStatus());
        return m;
    }

    // ================================================================
    // 差额结算（3.3，出纳手动：补付 / 退款）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> payDifference(String id, Map<String, Object> payload) {
        requireAny("补付报销差额", "ROLE_FIN_ASSET", "ROLE_FIN_MGR");
        Reimburse r = require(id);
        if (!Reimburse.ST_APPROVED.equals(r.getStatus())) {
            throw new ServiceException(422, "仅已审批报销单可补付差额：" + r.getStatus());
        }
        BigDecimal written = writeoffDao.selectSumByReimburse(id);
        BigDecimal diff = r.getTotalAmount().subtract(written == null ? BigDecimal.ZERO : written)
                .setScale(2, RoundingMode.HALF_UP);
        if (diff.signum() <= 0) {
            throw new ServiceException(422, "无待补差额（差额 " + diff.toPlainString() + "）");
        }
        if (r.getSettleAmount() != null && r.getSettleAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new ServiceException(422, "报销单已结算补付，禁止重复：" + r.getExpNo());
        }
        FinBankAccount bank = bankDao.selectById(str(payload.get("bankAccountId")));
        if (bank == null) {
            throw new ServiceException(422, "银行账户必填");
        }
        if (!FinBankAccount.ST_ACTIVE.equals(bank.getStatus())) {
            throw new ServiceException(422, "银行账户已停用：" + bank.getAccountName());
        }
        LocalDate payDate;
        try {
            payDate = LocalDate.parse(str(payload.get("payDate")).isEmpty()
                    ? LocalDate.now().toString() : str(payload.get("payDate")));
        } catch (RuntimeException e) {
            throw new ServiceException(422, "付款日期格式须为 yyyy-MM-dd");
        }
        String payMethod = hasText(str(payload.get("payMethod"))) ? str(payload.get("payMethod")) : "电汇";

        // 余额条件扣减（复用收付中心机制件，BR-4.6-21：不足 422 同事务回滚）
        if (bankDao.deductBalance(bank.getId(), diff) == 0) {
            throw new ServiceException(422, "银行账户余额不足：" + bank.getAccountName()
                    + "，需补付 " + diff.toPlainString());
        }
        // 凭证：借 2241 其他应付款-员工 / 贷 1002 银行存款（FR-4.6-7-7）
        var v = voucherService.create("PAY", payDate,
                "报销补付差额：" + r.getExpNo() + "（" + r.getEmployeeName() + "）",
                "REIMB", r.getExpNo(), null,
                List.of(GlVoucherService.FinVoucherLineSpec.of("2241", "DR", diff,
                        "其他应付款-员工报销-" + r.getEmployeeName()),
                        GlVoucherService.FinVoucherLineSpec.of("1002", "CR", diff,
                                "银行存款-" + bank.getAccountName())));
        // 收付中心单据承载（D5：PAY_TYPE=REIMBURSE，关联报销单）
        CashDoc doc = new CashDoc();
        doc.setId(uuid());
        doc.setDocNo("PY" + r.getExpNo());
        doc.setDirection(CashDoc.DIR_OUT);
        doc.setPartyType("EMPLOYEE");
        doc.setPartyId(r.getEmployeeId());
        doc.setPartyName(r.getEmployeeName());
        doc.setAmount(diff);
        doc.setDeductAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        doc.setActualAmount(diff);
        doc.setCurrency(bank.getCurrency() == null ? "CNY" : bank.getCurrency());
        doc.setPayMethod(payMethod);
        doc.setBankAccountId(bank.getId());
        doc.setAccountName(bank.getAccountName());
        doc.setAccountNo(bank.getAccountNo());
        doc.setPayDate(payDate);
        doc.setStatus(CashDoc.ST_POSTED);
        doc.setReqId(r.getId());
        doc.setReqNo(r.getExpNo());
        doc.setPayType("REIMBURSE");
        doc.setVoucherId(v.getId());
        doc.setCreateBy(currentUser());
        doc.setCreateDate(LocalDateTime.now());
        doc.setVerNo(0);
        docDao.insert(doc);

        r.setSettleAmount(diff);
        r.setSettleStatus(Reimburse.SETTLE_DONE);
        r.setUpdateBy(currentUser());
        r.setUpdateDate(LocalDateTime.now());
        reimburseDao.updateById(r);

        log.info("reimburse {} paid difference {} via {} by {}", r.getExpNo(), diff,
                bank.getAccountNo(), currentUser());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", doc.getId());
        out.put("docNo", doc.getDocNo());
        out.put("voucherNo", v.getVoucherNo());
        out.put("difference", diff);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> refund(String id, Map<String, Object> payload) {
        requireAny("登记员工退款", "ROLE_FIN_ASSET", "ROLE_FIN_MGR");
        Reimburse r = require(id);
        EmployeeLoan loan = loanDao.selectById(str(payload.get("loanId")));
        if (loan == null) {
            throw new ServiceException(422, "借款单必填（剩余借款退款锚点）");
        }
        if (!r.getEmployeeId().equals(loan.getEmployeeId())) {
            throw new ServiceException(422, "借款单与报销单员工不一致");
        }
        BigDecimal amount = dec(payload.get("amount"));
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "退款金额必须大于 0");
        }
        BigDecimal outstanding = loan.getOutstandingBalance() == null ? BigDecimal.ZERO : loan.getOutstandingBalance();
        if (amount.compareTo(outstanding) > 0) {
            throw new ServiceException(422, "退款金额超过借款未核销余额 " + outstanding.toPlainString());
        }
        FinBankAccount bank = bankDao.selectById(str(payload.get("bankAccountId")));
        if (bank == null || !FinBankAccount.ST_ACTIVE.equals(bank.getStatus())) {
            throw new ServiceException(422, "银行账户必填且须启用");
        }
        LocalDate payDate;
        try {
            payDate = LocalDate.parse(hasText(str(payload.get("payDate")))
                    ? str(payload.get("payDate")) : LocalDate.now().toString());
        } catch (RuntimeException e) {
            throw new ServiceException(422, "退款日期格式须为 yyyy-MM-dd");
        }
        // 收款入账（复用收付中心机制件，IN 方向）
        if (bankDao.addBalance(bank.getId(), amount) == 0) {
            throw new ServiceException(422, "银行账户不可入账（停用/不存在）");
        }
        // 凭证：借 1002 银行存款 / 贷 1299 其他应收款-员工借款（FR-4.6-7-7 差额<0 员工退回）
        var v = voucherService.create("LOANREF", payDate,
                "员工借款退款：" + loan.getLoanNo() + "（" + loan.getEmployeeName() + "）",
                "LOANREF", loan.getLoanNo(), null,
                List.of(GlVoucherService.FinVoucherLineSpec.of("1002", "DR", amount,
                        "银行存款-" + bank.getAccountName()),
                        GlVoucherService.FinVoucherLineSpec.of("1299", "CR", amount,
                                "其他应收款-员工借款-" + loan.getEmployeeName())));
        CashDoc doc = new CashDoc();
        doc.setId(uuid());
        doc.setDocNo("RF" + uuid().substring(0, 24).toUpperCase());
        doc.setDirection(CashDoc.DIR_IN);
        doc.setPartyType("EMPLOYEE");
        doc.setPartyId(loan.getEmployeeId());
        doc.setPartyName(loan.getEmployeeName());
        doc.setAmount(amount);
        doc.setDeductAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        doc.setActualAmount(amount);
        doc.setCurrency(bank.getCurrency() == null ? "CNY" : bank.getCurrency());
        doc.setPayMethod(hasText(str(payload.get("payMethod"))) ? str(payload.get("payMethod")) : "电汇");
        doc.setBankAccountId(bank.getId());
        doc.setAccountName(bank.getAccountName());
        doc.setAccountNo(bank.getAccountNo());
        doc.setPayDate(payDate);
        doc.setStatus(CashDoc.ST_POSTED);
        doc.setReqId(r.getId());
        doc.setReqNo(r.getExpNo());
        doc.setPayType("LOAN_REFUND");
        doc.setVoucherId(v.getId());
        doc.setCreateBy(currentUser());
        doc.setCreateDate(LocalDateTime.now());
        doc.setVerNo(0);
        docDao.insert(doc);

        // 借款余额回写（条件更新防并发）
        int updated = loanDao.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EmployeeLoan>()
                        .eq(EmployeeLoan::getId, loan.getId())
                        .apply("OUTSTANDING_BALANCE >= {0}", amount)
                        .setSql("OUTSTANDING_BALANCE = OUTSTANDING_BALANCE - "
                                + amount.toPlainString())
                        .set(EmployeeLoan::getUpdateBy, currentUser())
                        .set(EmployeeLoan::getUpdateDate, LocalDateTime.now()));
        if (updated == 0) {
            throw new ServiceException(422, "借款余额并发更新冲突：" + loan.getLoanNo());
        }
        log.info("loan {} refunded {} by {}", loan.getLoanNo(), amount, currentUser());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("docId", doc.getId());
        out.put("docNo", doc.getDocNo());
        out.put("voucherNo", v.getVoucherNo());
        out.put("amount", amount);
        out.put("loanOutstanding", outstanding.subtract(amount));
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    private Reimburse require(String id) {
        Reimburse r = reimburseDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "报销单不存在");
        }
        return r;
    }

    private Map<String, Object> row(Reimburse r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("expNo", r.getExpNo());
        m.put("employeeId", r.getEmployeeId());
        m.put("employeeName", r.getEmployeeName());
        m.put("totalAmount", r.getTotalAmount());
        m.put("status", r.getStatus());
        m.put("settleStatus", r.getSettleStatus());
        m.put("settleAmount", r.getSettleAmount());
        m.put("occurPeriod", r.getOccurPeriod());
        m.put("apprId", r.getApprId());
        m.put("voucherId", r.getVoucherId());
        m.put("createDate", r.getCreateDate());
        return m;
    }

    private String nextExpNo() {
        String prefix = "EXP" + LocalDate.now().format(DAY);
        Integer max = reimburseDao.selectMaxSeq(prefix);
        return prefix + String.format("%03d", (max == null ? 0 : max) + 1);
    }

    private String employeeName(String employeeId) {
        try {
            var u = userDao.selectById(employeeId);
            if (u != null) {
                return hasText(u.getNickName()) ? u.getNickName() : u.getUsername();
            }
        } catch (RuntimeException ignore) {
            // 用户表不可用时回退 ID
        }
        return employeeId;
    }

    private void requireAny(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        for (var a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equalsIgnoreCase(r)) {
                return;
            }
            for (String want : allowed) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action);
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
