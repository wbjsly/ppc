package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.crm.ContractDao;
import com.erp.dao.crm.ContractPlanDao;
import com.erp.dao.fin.ArInvoiceDao;
import com.erp.dao.fin.ArItemDao;
import com.erp.dao.fin.ArReceiptDao;
import com.erp.dao.fin.ArStatementDao;
import com.erp.dao.fin.ArStatementLineDao;
import com.erp.dao.fin.ArWriteoffDao;
import com.erp.dao.sd.SoDao;
import com.erp.entity.crm.Contract;
import com.erp.entity.crm.ContractPlan;
import com.erp.entity.fin.ArInvoice;
import com.erp.entity.fin.ArItem;
import com.erp.entity.fin.ArReceipt;
import com.erp.entity.fin.ArStatement;
import com.erp.entity.fin.ArStatementLine;
import com.erp.entity.fin.ArWriteoff;
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
    private final ArWriteoffDao writeoffDao;
    private final ArReceiptDao receiptDao;
    private final ArStatementDao statementDao;
    private final ArStatementLineDao statementLineDao;
    private final ContractDao contractDao;
    private final ContractPlanDao planDao;
    private final SoDao soDao;
    private final GlVoucherService voucherService;

    public ArServiceImpl(ArInvoiceDao arDao,
                         ArItemDao arItemDao,
                         ArWriteoffDao writeoffDao,
                         ArReceiptDao receiptDao,
                         ArStatementDao statementDao,
                         ArStatementLineDao statementLineDao,
                         ContractDao contractDao,
                         ContractPlanDao planDao,
                         SoDao soDao,
                         GlVoucherService voucherService) {
        this.arDao = arDao;
        this.arItemDao = arItemDao;
        this.writeoffDao = writeoffDao;
        this.receiptDao = receiptDao;
        this.statementDao = statementDao;
        this.statementLineDao = statementLineDao;
        this.contractDao = contractDao;
        this.planDao = planDao;
        this.soDao = soDao;
        this.voucherService = voucherService;
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
        out.put("writeoffs", writeoffDao.selectList(new LambdaQueryWrapper<ArWriteoff>()
                .eq(ArWriteoff::getArId, arId).orderByDesc(ArWriteoff::getPayDate)));
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
        requireAny("登记回款", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        if (isBlank(customerId)) {
            throw new ServiceException(422, "客户必填");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "回款金额必须大于 0");
        }
        LocalDate pay = payDate == null ? LocalDate.now() : payDate;

        ArReceipt receipt = new ArReceipt();
        receipt.setId(uuid());
        receipt.setRcptNo(nextNo("RC", receiptDao::selectNosByPrefix));
        ArInvoice probe = arDao.selectList(new LambdaQueryWrapper<ArInvoice>()
                .eq(ArInvoice::getCustomerId, customerId).last("LIMIT 1"))
                .stream().findFirst().orElse(null);
        receipt.setCustomerId(customerId);
        receipt.setCustomerCode(probe == null ? null : probe.getCustomerCode());
        receipt.setCustomerName(probe == null ? null : probe.getCustomerName());
        receipt.setAmount(amount);
        receipt.setPayDate(pay);
        receipt.setMatchedAmt(BigDecimal.ZERO);
        receipt.setUnmatchedAmt(amount);
        receipt.setStatus(ArReceipt.ST_PENDING);
        receipt.setRemark(remark);
        receipt.setCreateBy(currentUser());
        receiptDao.insert(receipt);

        // FIFO：最早未核销应收优先，支持部分核销
        List<ArInvoice> pool = arDao.selectFifoPool(customerId);
        BigDecimal remain = amount;
        List<Map<String, Object>> details = new ArrayList<>();
        for (ArInvoice ar : pool) {
            if (remain.signum() <= 0) {
                break;
            }
            BigDecimal open = balanceOf(ar);
            if (open.signum() <= 0) {
                continue;
            }
            BigDecimal take = open.min(remain);
            if (take.signum() <= 0) {
                continue;
            }
            applyWriteoff(ar, take, pay, "AUTO", receipt.getId(), receipt.getRcptNo(),
                    "FIFO 自动核销（最早应收优先）");
            remain = remain.subtract(take);
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("arNo", ar.getArNo());
            d.put("amount", take);
            d.put("balance", balanceOf(ar));
            details.add(d);
        }
        BigDecimal matched = amount.subtract(remain);
        receipt.setMatchedAmt(matched);
        receipt.setUnmatchedAmt(remain);
        receipt.setStatus(remain.signum() <= 0 ? ArReceipt.ST_AUTO
                : (matched.signum() > 0 ? ArReceipt.ST_PARTIAL : ArReceipt.ST_PENDING));
        receiptDao.updateById(receipt);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("receipt", receipt);
        out.put("details", details);
        out.put("unmatched", remain);
        if (remain.signum() > 0) {
            out.put("manualHint", matched.signum() > 0
                    ? "部分核销完成，余额 " + strip(remain) + " 转人工核销队列"
                    : "无未清应收可匹配，回款 " + strip(amount) + " 转人工核销队列");
        }
        log.info("receipt {} matched {} unmatched {}", receipt.getRcptNo(), matched, remain);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> manualWriteoff(String receiptId, String arId, BigDecimal amount,
                                              LocalDate payDate, String remark) {
        requireAny("人工核销", "ROLE_FINANCE_MGR", "ROLE_ADMIN");
        ArInvoice ar = require(arId);
        if (amount == null || amount.signum() <= 0) {
            throw new ServiceException(422, "核销金额必须大于 0");
        }
        BigDecimal open = balanceOf(ar);
        if (amount.compareTo(open) > 0) {
            throw new ServiceException(422, "核销金额超出应收余额 " + strip(open));
        }
        ArReceipt receipt = null;
        if (isNotBlank(receiptId)) {
            receipt = receiptDao.selectById(receiptId);
            if (receipt == null) {
                throw new ServiceException(404, "回款单不存在：" + receiptId);
            }
            if (!receipt.getCustomerId().equals(ar.getCustomerId())) {
                throw new ServiceException(422, "回款单客户与应收客户不一致，禁止交叉核销");
            }
            if (amount.compareTo(nvl(receipt.getUnmatchedAmt())) > 0) {
                throw new ServiceException(422, "核销金额超出回款待匹配余额 "
                        + strip(nvl(receipt.getUnmatchedAmt())));
            }
        }
        LocalDate pay = payDate == null ? LocalDate.now() : payDate;
        applyWriteoff(ar, amount, pay, "MANUAL",
                receipt == null ? null : receipt.getId(),
                receipt == null ? null : receipt.getRcptNo(),
                isBlank(remark) ? "人工核销" : remark);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ar", ar);
        if (receipt != null) {
            receipt.setUnmatchedAmt(nvl(receipt.getUnmatchedAmt()).subtract(amount));
            receipt.setMatchedAmt(nvl(receipt.getMatchedAmt()).add(amount));
            receipt.setStatus(receipt.getUnmatchedAmt().signum() <= 0
                    ? ArReceipt.ST_MANUAL : ArReceipt.ST_PARTIAL);
            receiptDao.updateById(receipt);
            out.put("receipt", receipt);
        }
        return out;
    }

    /** 核销落库：核销记录 + 应收回写 + 核销凭证 */
    private void applyWriteoff(ArInvoice ar, BigDecimal amount, LocalDate payDate,
                               String writeType, String receiptId, String receiptNo,
                               String remark) {
        BigDecimal before = nvl(ar.getPaidAmount());
        ar.setPaidAmount(before.add(amount));
        BigDecimal balance = balanceOf(ar);
        ar.setStatus(balance.signum() <= 0 ? ArInvoice.ST_PAID
                : (ar.getPaidAmount().signum() > 0 ? ArInvoice.ST_PARTIAL : ArInvoice.ST_UNPAID));
        arDao.updateById(ar);

        ArWriteoff wo = new ArWriteoff();
        wo.setId(uuid());
        wo.setWoNo(nextNo("WO", writeoffDao::selectNosByPrefix));
        wo.setArId(ar.getId());
        wo.setArNo(ar.getArNo());
        wo.setCustomerId(ar.getCustomerId());
        wo.setAmount(amount);
        wo.setPayDate(payDate);
        wo.setDueDate(ar.getDueDate());
        wo.setOnTime(ar.getDueDate() != null && !payDate.isAfter(ar.getDueDate()) ? "1" : "0");
        wo.setWriteType(writeType);
        wo.setReceiptId(receiptId);
        wo.setReceiptNo(receiptNo);
        wo.setRemark(remark);
        wo.setCreateBy(currentUser());
        writeoffDao.insert(wo);

        // 核销凭证：借 1002 银行存款 / 贷 1122 应收账款（spec 场景核销凭证）
        List<GlVoucherService.FinVoucherLineSpec> lines = new ArrayList<>();
        lines.add(GlVoucherService.FinVoucherLineSpec.of("1002", "DR", amount,
                "回款核销 " + ar.getArNo()));
        lines.add(GlVoucherService.FinVoucherLineSpec.of("1122", "CR", amount,
                "应收核销 " + ar.getArNo()));
        FinVoucher v = voucherService.create("ARW", payDate,
                "应收核销 " + ar.getArNo() + " " + strip(amount),
                "AR_WRITEOFF", wo.getWoNo(), null, lines);
        wo.setVoucherId(v.getId());
        writeoffDao.updateById(wo);
    }

    @Override
    public Page<ArReceipt> receiptPage(long current, long size, String status,
                                       String keyword, String customerId) {
        return receiptDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ArReceipt>()
                        .eq(isNotBlank(status), ArReceipt::getStatus, status)
                        .eq(isNotBlank(customerId), ArReceipt::getCustomerId, customerId)
                        .and(isNotBlank(keyword), w -> w.like(ArReceipt::getRcptNo, keyword)
                                .or().like(ArReceipt::getCustomerName, keyword))
                        .orderByDesc(ArReceipt::getCreateDate));
    }

    @Override
    public Page<ArWriteoff> writeoffPage(long current, long size, String keyword,
                                         String customerId, String arNo) {
        return writeoffDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<ArWriteoff>()
                        .eq(isNotBlank(customerId), ArWriteoff::getCustomerId, customerId)
                        .eq(isNotBlank(arNo), ArWriteoff::getArNo, arNo)
                        .and(isNotBlank(keyword), w -> w.like(ArWriteoff::getWoNo, keyword)
                                .or().like(ArWriteoff::getArNo, keyword)
                                .or().like(ArWriteoff::getReceiptNo, keyword))
                        .orderByDesc(ArWriteoff::getPayDate));
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
        BigDecimal openWriteoff = nvl(writeoffDao.selectWriteoffBefore(customerId, period));
        BigDecimal openBal = openInvoice.subtract(openWriteoff);
        BigDecimal invoiceAmt = nvl(arDao.selectPeriodInvoiceAmt(customerId, period));
        BigDecimal writeoffAmt = nvl(writeoffDao.selectPeriodWriteoffAmt(customerId, period));
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
            line.setWriteoffAmt(nvl(writeoffDao.selectSumByArPeriod(ar.getId(), period)));
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
