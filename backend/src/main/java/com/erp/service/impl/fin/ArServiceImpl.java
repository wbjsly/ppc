package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.crm.ContractPlanDao;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArItemDao;
import com.erp.dao.fin.ArStatementDao;
import com.erp.dao.fin.ArStatementLineDao;
import com.erp.dao.sd.SoDao;
import com.erp.entity.crm.Contract;
import com.erp.entity.crm.ContractPlan;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArItem;
import com.erp.entity.fin.ArStatement;
import com.erp.entity.fin.ArStatementLine;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.sd.So;
import com.erp.service.fin.ArService;
import com.erp.service.fin.GlVoucherService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 应收核销实现（tasks 10.6~10.8，spec sales-invoicing-receivable）。
 *
 * FIFO（FR-4.3-7-5）：按 INVOICE_DATE、AR_NO 升序消耗未清应收，逐笔部分核销；
 * 回款余额未耗尽或无未清应收 → 回款单置 PENDING/PARTIAL 转人工；
 * 每次核销生成核销凭证（借 1002 银行存款 / 贷 1122 应收账款，按核销笔数分行）。
 */
@Slf4j
@Service
public class ArServiceImpl implements ArService {

    private final ArInvoiceDao arDao;
    private final ArItemDao arItemDao;
    private final com.erp.dao.fin.CashWriteoffDao cashWoDao;
    private final com.erp.service.fin.CashDocService cashDocService;
    private final com.erp.service.fin.CashWriteoffService cashWriteoffService;
    private final ArStatementDao statementDao;
    private final ArStatementLineDao statementLineDao;
    private final ContractDao contractDao;
    private final ContractPlanDao planDao;
    private final SoDao soDao;
    private final GlVoucherService voucherService;
    private final com.erp.dao.fin.FinBankAccountDao bankDao;

    public ArServiceImpl(ArInvoiceDao arDao,
                         ArItemDao arItemDao,
                         com.erp.dao.fin.CashWriteoffDao cashWoDao,
                         com.erp.service.fin.CashDocService cashDocService,
                         com.erp.service.fin.CashWriteoffService cashWriteoffService,
                         ArStatementDao statementDao,
                         ArStatementLineDao statementLineDao,
                         ContractDao contractDao,
                         ContractPlanDao planDao,
                         SoDao soDao,
                         GlVoucherService voucherService,
                         com.erp.dao.fin.FinBankAccountDao bankDao) {
        this.arDao = arDao;
        this.arItemDao = arItemDao;
        this.cashWoDao = cashWoDao;
        this.cashDocService = cashDocService;
        this.cashWriteoffService = cashWriteoffService;
        this.statementDao = statementDao;
        this.statementLineDao = statementLineDao;
        this.contractDao = contractDao;
        this.planDao = planDao;
        this.soDao = soDao;
        this.voucherService = voucherService;
        this.bankDao = bankDao;
    }

    // ---------- 3.8.3 应收台账多维查询 ----------

    @Override
    public Page<ArInvoice> arPage(long current, long size, String keyword, String status,
                                  String customerId, String soNo, String contractId,
                                  Boolean overdue) {
        // 合同维度过滤：合同 → SO → 应收
        List<String> soIds = null;
        if (isNotBlank(contractId)) {
            soIds = new ArrayList<>();
            for (So so : soDao.selectList(new LambdaQueryWrapper<So>()
                    .eq(So::getContractId, contractId))) {
                soIds.add(so.getId());
            }
            if (soIds.isEmpty()) {
                return new Page<>(current, size);
            }
        }
        LambdaQueryWrapper<ArInvoice> qw = new LambdaQueryWrapper<ArInvoice>()
                .eq(isNotBlank(status), ArInvoice::getStatus, status)
                .eq(isNotBlank(customerId), ArInvoice::getCustomerId, customerId)
                .eq(isNotBlank(soNo), ArInvoice::getSoNo, soNo)
                .in(soIds != null, ArInvoice::getSoId, soIds)
                .and(isNotBlank(keyword), w -> w.like(ArInvoice::getArNo, keyword)
                        .or().like(ArInvoice::getCustomerName, keyword)
                        .or().like(ArInvoice::getInvoiceNo, keyword)
                        .or().like(ArInvoice::getSoNo, keyword))
                .orderByDesc(ArInvoice::getInvoiceDate);
        Page<ArInvoice> page = arDao.selectPage(new Page<>(current, size), qw);
        if (Boolean.TRUE.equals(overdue)) {
            LocalDate today = LocalDate.now();
            page.getRecords().removeIf(r -> r.getDueDate() == null
                    || !r.getDueDate().isBefore(today)
                    || nvl(r.getAmount()).subtract(nvl(r.getRedAmount()))
                        .subtract(nvl(r.getPaidAmount())).signum() <= 0);
        }
        return page;
    }

    @Override
    public Map<String, Object> arDetail(String arId) {
        ArInvoice ar = require(arId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ar", ar);
        out.put("items", arItemDao.selectList(new LambdaQueryWrapper<ArItem>()
                .eq(ArItem::getArId, arId).orderByAsc(ArItem::getLineNo)));
        out.put("writeoffs", cashWoDao.selectList(new LambdaQueryWrapper<com.erp.entity.fin.CashWriteoff>()
                .eq(com.erp.entity.fin.CashWriteoff::getTargetType, "AR")
                .eq(com.erp.entity.fin.CashWriteoff::getTargetId, arId)
                .orderByDesc(com.erp.entity.fin.CashWriteoff::getWriteoffDate)));
        out.put("balance", balanceOf(ar));

        // 回看所属合同期次与收款进度（10.8：合同 → SO → 应收链路）
        if (isNotBlank(ar.getSoId())) {
            So so = soDao.selectById(ar.getSoId());
            out.put("so", so);
            if (so != null && isNotBlank(so.getContractId())) {
                Contract contract = contractDao.selectById(so.getContractId());
                out.put("contract", contract);
                if (contract != null) {
                    List<ContractPlan> plans = planDao.selectList(
                            new LambdaQueryWrapper<ContractPlan>()
                                    .eq(ContractPlan::getContractId, contract.getId())
                                    .orderByAsc(ContractPlan::getPeriodNo));
                    out.put("plans", plans);
                    out.put("planProgress", attachProgress(contract, plans));
                }
            }
        }
        return out;
    }

    // ---------- 10.6 FIFO 自动核销 ----------

    @Override
    @Transactional
    public Map<String, Object> registerReceipt(String customerId, BigDecimal amount,
                                               LocalDate payDate, String remark) {
        // add-payment-collection：回款登记迁移统一收付款中心（IN 创建 + 过账，
        // spec sales-invoicing-receivable MODIFIED：核销不产凭证，资金凭证归收款单过账）
        requireAny("登记回款", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        if (isBlank(customerId)) {
            throw new ServiceException(422, "客户必填");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "回款金额必须大于 0");
        }
        LocalDate pay = payDate == null ? LocalDate.now() : payDate;
        ArInvoice probe = arDao.selectList(new LambdaQueryWrapper<ArInvoice>()
                .eq(ArInvoice::getCustomerId, customerId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("direction", "IN");
        payload.put("partyId", customerId);
        payload.put("partyCode", probe == null ? null : probe.getCustomerCode());
        payload.put("partyName", probe == null ? null : probe.getCustomerName());
        payload.put("amount", amount);
        payload.put("payMethod", "电汇");
        payload.put("bankAccountId", defaultAccountId());
        payload.put("payDate", pay.toString());
        payload.put("currency", "CNY");
        payload.put("remark", isBlank(remark) ? "回款登记" : remark);
        var doc = cashDocService.create(payload);
        Map<String, Object> posted = cashDocService.post(doc.getId());

        BigDecimal writeoffTotal = (BigDecimal) posted.get("writeoffTotal");
        BigDecimal remaining = (BigDecimal) posted.get("remaining");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receipt", cashDocService.detail(doc.getId()).get("doc"));
        List<Map<String, Object>> details = new ArrayList<>();
        for (Map<String, Object> w : cashWriteoffService.listByDoc(doc.getId())) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("arNo", w.get("targetNo"));
            d.put("amount", w.get("amount"));
            d.put("balance", BigDecimal.ZERO);
            details.add(d);
        }
        out.put("details", details);
        out.put("unmatched", remaining);
        out.put("voucherNo", posted.get("voucherNo"));
        if (remaining.signum() > 0) {
            out.put("manualHint", writeoffTotal.signum() > 0
                    ? "部分核销完成，余额 " + strip(remaining) + " 转预收账款（2205）"
                    : "无未清应收可匹配，回款 " + strip(amount) + " 全额转预收账款（2205）");
        }
        log.info("receipt {} via cash doc {}: writeoff {} remaining {}",
                doc.getDocNo(), doc.getId(), writeoffTotal, remaining);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> manualWriteoff(String receiptId, String arId, BigDecimal amount,
                                              LocalDate payDate, String remark) {
        // add-payment-collection：人工核销迁移统一中心（cash_writeoff；跨期自动挂审批 C-4.6-02）
        requireAny("人工核销", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        if (isBlank(receiptId)) {
            throw new ServiceException(422, "收款单必填（统一收付款单 ID）");
        }
        var out = cashWriteoffService.manualSettle(receiptId, "AR", arId, amount);
        log.info("manual writeoff ar={} amount={} -> {}", arId, amount, out);
        return out;
    }

    /** 默认收款账户（回款登记快捷入口；无参数时取首个 ACTIVE 账户） */
    private String defaultAccountId() {
        var list = bankDao.selectList(new LambdaQueryWrapper<com.erp.entity.fin.FinBankAccount>()
                .eq(com.erp.entity.fin.FinBankAccount::getStatus, "ACTIVE")
                .last("LIMIT 1"));
        if (list.isEmpty()) {
            throw new ServiceException(422, "无可用银行账户，请在收付管理中维护");
        }
        return list.get(0).getId();
    }

    @Override
    public Page<com.erp.entity.fin.CashDoc> receiptPage(long current, long size, String status,
                                                        String keyword, String customerId) {
        // add-payment-collection：回款单 = 统一收付款单 IN 方向
        var raw = cashDocService.page(current, size, "IN", status, customerId, keyword, null, null);
        Page<com.erp.entity.fin.CashDoc> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<com.erp.entity.fin.CashDoc> rows = new ArrayList<>();
        for (Map<String, Object> m : raw.getRecords()) {
            com.erp.entity.fin.CashDoc d = new com.erp.entity.fin.CashDoc();
            d.setId((String) m.get("id"));
            d.setDocNo((String) m.get("docNo"));
            d.setPartyName((String) m.get("partyName"));
            d.setAmount((BigDecimal) m.get("amount"));
            d.setPayDate((java.time.LocalDate) m.get("payDate"));
            d.setStatus((String) m.get("status"));
            rows.add(d);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Page<com.erp.entity.fin.CashWriteoff> writeoffPage(long current, long size, String keyword,
                                                              String customerId, String arNo) {
        // add-payment-collection：核销明细 = 统一核销表（TARGET_TYPE=AR）
        LambdaQueryWrapper<com.erp.entity.fin.CashWriteoff> qw =
                new LambdaQueryWrapper<com.erp.entity.fin.CashWriteoff>()
                .eq(com.erp.entity.fin.CashWriteoff::getTargetType, "AR")
                .eq(isNotBlank(customerId), com.erp.entity.fin.CashWriteoff::getCustomerId, customerId)
                .eq(isNotBlank(arNo), com.erp.entity.fin.CashWriteoff::getTargetNo, arNo)
                .and(isNotBlank(keyword), w -> w.like(com.erp.entity.fin.CashWriteoff::getWoNo, keyword)
                        .or().like(com.erp.entity.fin.CashWriteoff::getTargetNo, keyword)
                        .or().like(com.erp.entity.fin.CashWriteoff::getDocNo, keyword))
                .orderByDesc(com.erp.entity.fin.CashWriteoff::getWriteoffDate);
        return cashWoDao.selectPage(new Page<>(current, size), qw);
    }

    // ---------- 10.7 月度对账单 ----------

    @Override
    @Transactional
    public Map<String, Object> generateStatement(String customerId, String period) {
        requireAny("生成对账单", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        if (isBlank(customerId) || isBlank(period) || period.length() != 6) {
            throw new ServiceException(422, "客户与对账期间（yyyyMM）必填");
        }
        ArInvoice probe = arDao.selectList(new LambdaQueryWrapper<ArInvoice>()
                .eq(ArInvoice::getCustomerId, customerId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        if (probe == null) {
            throw new ServiceException(422, "该客户无应收记录，无法生成对账单");
        }

        BigDecimal openInvoice = nvl(arDao.selectOpenBalanceBefore(customerId, period));
        BigDecimal openWriteoff = nvl(cashWoDao.selectWriteoffBefore(customerId, period));
        BigDecimal openBal = openInvoice.subtract(openWriteoff);
        BigDecimal invoiceAmt = nvl(arDao.selectPeriodInvoiceAmt(customerId, period));
        BigDecimal writeoffAmt = nvl(cashWoDao.selectPeriodWriteoffAmt(customerId, period));
        BigDecimal closeBal = openBal.add(invoiceAmt).subtract(writeoffAmt);

        // 幂等：同客户同期间刷新
        String existId = statementDao.selectByCustomerPeriod(customerId, period);
        ArStatement stmt = existId == null ? new ArStatement() : statementDao.selectById(existId);
        boolean fresh = stmt.getId() == null;
        if (fresh) {
            stmt.setId(uuid());
            stmt.setStmtNo(nextNo("ST", statementDao::selectNosByPrefix));
            stmt.setCustomerId(customerId);
            stmt.setCustomerCode(probe.getCustomerCode());
            stmt.setCustomerName(probe.getCustomerName());
            stmt.setPeriod(period);
            stmt.setStatus(ArStatement.ST_GENERATED);
            stmt.setCreateBy(currentUser());
        }
        stmt.setOpenBal(openBal);
        stmt.setInvoiceAmt(invoiceAmt);
        stmt.setWriteoffAmt(writeoffAmt);
        stmt.setCloseBal(closeBal);
        if (fresh) {
            statementDao.insert(stmt);
        } else {
            statementDao.updateById(stmt);
            statementLineDao.delete(new LambdaQueryWrapper<ArStatementLine>()
                    .eq(ArStatementLine::getStmtId, stmt.getId()));
        }

        // 未核销明细（期末仍未清 + 本期发生过的应收）
        List<ArInvoice> ars = arDao.selectList(new LambdaQueryWrapper<ArInvoice>()
                .eq(ArInvoice::getCustomerId, customerId)
                .apply("INVOICE_DATE <= LAST_DAY(STR_TO_DATE(CONCAT({0},'01'),'%Y%m%d'))", period)
                .orderByAsc(ArInvoice::getInvoiceDate));
        LocalDate periodEnd = periodEnd(period);
        int unpaid = 0;
        int seq = 1;
        for (ArInvoice ar : ars) {
            BigDecimal bal = balanceOf(ar);
            boolean inScope = bal.signum() > 0
                    || (ar.getInvoiceDate() != null && !ar.getInvoiceDate().isAfter(periodEnd)
                        && !ar.getInvoiceDate().isBefore(periodStart(period)));
            if (!inScope) {
                continue;
            }
            if (bal.signum() <= 0) {
                continue;   // 期末已结清的不进未核销明细
            }
            unpaid++;
            ArStatementLine line = new ArStatementLine();
            line.setId(uuid());
            line.setStmtId(stmt.getId());
            line.setArId(ar.getId());
            line.setArNo(ar.getArNo());
            line.setInvoiceNo(ar.getInvoiceNo());
            line.setInvoiceDate(ar.getInvoiceDate());
            line.setDueDate(ar.getDueDate());
            line.setAmount(nvl(ar.getAmount()));
            line.setPaidAmount(nvl(ar.getPaidAmount()));
            line.setBalance(bal);
            line.setWriteoffAmt(nvl(cashWoDao.selectSumByArPeriod(ar.getId(), period)));
            int overdue = ar.getDueDate() == null ? 0 : (int) (periodEnd.toEpochDay()
                    - ar.getDueDate().toEpochDay());
            line.setOverdueDays(Math.max(overdue, 0));
            line.setDiffAmt(BigDecimal.ZERO);
            line.setCheckStatus(ArStatementLine.CHK_PENDING);
            line.setCreateBy(currentUser());
            statementLineDao.insert(line);
            seq++;
        }
        stmt.setUnpaidCnt(unpaid);
        statementDao.updateById(stmt);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", stmt);
        return out;
    }

    @Override
    public Page<ArStatement> statementPage(long current, long size, String keyword,
                                           String customerId) {
        return statementDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ArStatement>()
                        .eq(isNotBlank(customerId), ArStatement::getCustomerId, customerId)
                        .and(isNotBlank(keyword), w -> w.like(ArStatement::getStmtNo, keyword)
                                .or().like(ArStatement::getCustomerName, keyword)
                                .or().like(ArStatement::getPeriod, keyword))
                        .orderByDesc(ArStatement::getPeriod));
    }

    @Override
    public Map<String, Object> statementDetail(String stmtId) {
        ArStatement stmt = statementDao.selectById(stmtId);
        if (stmt == null) {
            throw new ServiceException(404, "对账单不存在：" + stmtId);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", stmt);
        out.put("lines", statementLineDao.selectList(new LambdaQueryWrapper<ArStatementLine>()
                .eq(ArStatementLine::getStmtId, stmtId).orderByAsc(ArStatementLine::getArNo)));
        return out;
    }

    @Override
    @Transactional
    public void checkLine(String lineId, String checkStatus, String note, BigDecimal diffAmt) {
        requireAny("对账差异排查", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        ArStatementLine line = statementLineDao.selectById(lineId);
        if (line == null) {
            throw new ServiceException(404, "对账明细行不存在：" + lineId);
        }
        if (isBlank(checkStatus)) {
            checkStatus = ArStatementLine.CHK_CHECKED;
        }
        if (ArStatementLine.CHK_DIFF.equals(checkStatus) && isBlank(note)) {
            throw new ServiceException(422, "标记差异必须填写排查记录");
        }
        line.setCheckStatus(checkStatus);
        line.setCheckNote(note);
        line.setDiffAmt(diffAmt == null ? BigDecimal.ZERO : diffAmt);
        line.setCheckBy(currentUser());
        line.setCheckAt(LocalDateTime.now());
        statementLineDao.updateById(line);
    }

    // ---------- 10.8 计划达成对比 ----------

    @Override
    public List<Map<String, Object>> planProgress(String contractId) {
        Contract contract = contractDao.selectById(contractId);
        if (contract == null) {
            throw new ServiceException(404, "合同不存在：" + contractId);
        }
        List<ContractPlan> plans = planDao.selectList(new LambdaQueryWrapper<ContractPlan>()
                .eq(ContractPlan::getContractId, contractId)
                .orderByAsc(ContractPlan::getPeriodNo));
        return attachProgress(contract, plans);
    }

    @Override
    public Map<String, Object> contractArSummary(String contractId) {
        Map<String, Object> agg = arDao.selectContractAgg(contractId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("arCount", agg.get("cnt"));
        out.put("arAmt", agg.get("amt"));
        out.put("paidAmt", agg.get("paid"));
        return out;
    }

    /**
     * 逐期达成：实际应收按 AR 应收到期日落入该期计划区间归属；
     * 达成率 = 该期已核销 / 计划金额；计划到期日已过且未收足 → overdue。
     */
    private List<Map<String, Object>> attachProgress(Contract contract, List<ContractPlan> plans) {
        List<ArInvoice> ars = arDao.selectByContract(contract.getId());
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < plans.size(); i++) {
            ContractPlan p = plans.get(i);
            LocalDate from = i == 0 ? null : plans.get(i - 1).getDueDate();
            BigDecimal arAmt = BigDecimal.ZERO;
            BigDecimal paid = BigDecimal.ZERO;
            int arCount = 0;
            for (ArInvoice ar : ars) {
                LocalDate due = ar.getDueDate();
                if (due == null) {
                    continue;
                }
                boolean ge = from == null || due.isAfter(from) || due.equals(from);
                boolean le = !due.isAfter(p.getDueDate());
                if (ge && le) {
                    arCount++;
                    arAmt = arAmt.add(nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount())));
                    paid = paid.add(nvl(ar.getPaidAmount()));
                }
            }
            BigDecimal planAmt = nvl(p.getPlanAmount());
            BigDecimal rate = planAmt.signum() > 0
                    ? paid.divide(planAmt, 4, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("periodNo", p.getPeriodNo());
            row.put("dueDate", p.getDueDate());
            row.put("planAmount", planAmt);
            row.put("arCount", arCount);
            row.put("arAmount", arAmt);
            row.put("paidAmount", paid);
            row.put("rate", rate);
            row.put("overdue", p.getDueDate() != null && p.getDueDate().isBefore(today)
                    && paid.compareTo(planAmt) < 0);
            row.put("status", p.getDueDate() != null && p.getDueDate().isBefore(today)
                    ? (paid.compareTo(planAmt) >= 0 ? "RECEIVED" : "OVERDUE") : "PENDING");
            out.add(row);
        }
        return out;
    }

    // ---------- 通用 ----------

    /** 未清余额 = AMOUNT − RED − PAID（红冲进入匹配池口径） */
    private static BigDecimal balanceOf(ArInvoice ar) {
        return nvl(ar.getAmount()).subtract(nvl(ar.getRedAmount()))
                .subtract(nvl(ar.getPaidAmount()));
    }

    private static LocalDate periodStart(String period) {
        return LocalDate.of(Integer.parseInt(period.substring(0, 4)),
                Integer.parseInt(period.substring(4, 6)), 1);
    }

    private static LocalDate periodEnd(String period) {
        LocalDate start = periodStart(period);
        return start.withDayOfMonth(start.lengthOfMonth());
    }

    private ArInvoice require(String id) {
        ArInvoice ar = arDao.selectById(id);
        if (ar == null) {
            throw new ServiceException(404, "应收单不存在：" + id);
        }
        return ar;
    }

    private void requireAny(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权" + action + "（需 "
                + String.join("/", allowed).replace("ROLE_", "") + "）");
    }

    private String currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }

    private String nextNo(String kind, PrefixQuery query) {
        String prefix = kind + LocalDate.now().toString().replace("-", "") + "-";
        int max = 0;
        for (String no : query.byPrefix(prefix + "%")) {   // LIKE 通配符由调用方拼接（项目惯例）
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length())));
            } catch (Exception ignore) {
                // 种子编号跳过
            }
        }
        return prefix + String.format("%04d", max + 1);
    }

    private interface PrefixQuery {
        List<String> byPrefix(String prefix);
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String strip(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static boolean isNotBlank(String s) {
        return !isBlank(s);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
