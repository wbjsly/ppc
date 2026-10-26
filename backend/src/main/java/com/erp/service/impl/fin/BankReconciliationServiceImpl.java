package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.BankReconcileItemDao;
import com.erp.dao.fin.BankStatementDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.dao.fin.CashDocDao;
import com.erp.dao.fin.BankReceiptDao;
import com.erp.entity.fin.BankReceipt;
import com.erp.entity.fin.BankReconcileItem;
import com.erp.entity.fin.BankStatement;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.fin.FinBankAccount;
import com.erp.service.fin.BankReconciliationService;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 银行对账实现（M8-7，spec bank-reconciliation，design D1~D7）。
 * 头表锚余额，流水复用 bank_receipt；四类分类/差异率/调节表全实时计算（D3/D4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankReconciliationServiceImpl implements BankReconciliationService {

    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.005");
    private static final int AGING_DAYS = 90;

    private final BankStatementDao stmtDao;
    private final BankReconcileItemDao itemDao;
    private final FinBankAccountDao bankDao;
    private final CashDocDao cashDocDao;
    private final BankReceiptDao receiptDao;

    // ================================================================
    // 对账单 CRUD（2.2）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        requireAny("维护银行对账单", "ROLE_FIN_ASSET", "ROLE_FIN_MGR");
        String accountId = str(payload.get("bankAccountId"));
        String period = str(payload.get("period"));
        FinBankAccount acct = bankDao.selectById(accountId);
        if (acct == null) {
            throw new ServiceException(422, "银行账户不存在");
        }
        if (period == null || !period.matches("\\d{6}")) {
            throw new ServiceException(422, "期间格式须为 yyyyMM");
        }
        String id = str(payload.get("id"));
        BankStatement existing = stmtDao.selectByAccountPeriod(accountId, period);
        if (existing != null && !existing.getId().equals(id)) {
            throw new ServiceException(422, "同账户同期间对账单已存在：" + acct.getAccountName() + " " + period);
        }
        BigDecimal opening = dec(payload.get("openingBalance"));
        BigDecimal closing = dec(payload.get("closingBalance"));
        if (closing == null) {
            throw new ServiceException(422, "期末余额必填（对账单载明值）");
        }
        if (opening == null) {
            opening = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        BankStatement st;
        if (existing != null) {
            st = existing;
            if (BankStatement.ST_AUDITED.equals(st.getStatus())) {
                throw new ServiceException(422, "已审核对账单不可修改，请先撤销");
            }
        } else {
            st = new BankStatement();
            st.setId(uuid());
            st.setStatus(BankStatement.ST_DRAFT);
            st.setCreateBy(currentUser());
            st.setCreateDate(LocalDateTime.now());
        }
        st.setBankAccountId(accountId);
        st.setAccountName(acct.getAccountName());
        st.setAccountNo(acct.getAccountNo());
        st.setCurrency(acct.getCurrency());
        st.setPeriod(period);
        st.setStmtNo(str(payload.get("stmtNo")));
        st.setOpeningBalance(opening);
        st.setClosingBalance(closing);
        st.setRemark(str(payload.get("remark")));
        st.setUpdateBy(currentUser());
        st.setUpdateDate(LocalDateTime.now());
        st.setVerNo(st.getVerNo() == null ? 0 : st.getVerNo());

        // 勾稽自校验：期初 + 本期已匹配收 − 本期已匹配付 = 期末（不平提示仍允许保存，差额即未达账项）
        BigDecimal matchedNet = matchedNet(accountId, period);
        BigDecimal expected = opening.add(matchedNet);
        BigDecimal gap = closing.subtract(expected);
        if (existing == null) {
            stmtDao.insert(st);
        } else {
            stmtDao.updateById(st);
        }
        // 差异率与超容差标记（C-0-02）
        applyDiffFlag(st);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", st.getId());
        out.put("status", st.getStatus());
        out.put("selfCheckGap", gap);
        out.put("selfCheckBalanced", gap.signum() == 0);
        out.put("overTolerance", "1".equals(st.getOverTolerance()));
        out.put("diffRate", diffRate(st));
        return out;
    }

    @Override
    public List<Map<String, Object>> list(String period) {
        List<Map<String, Object>> out = new ArrayList<>();
        LambdaQueryWrapper<BankStatement> qw = new LambdaQueryWrapper<BankStatement>()
                .eq(hasText(period), BankStatement::getPeriod, period)
                .orderByDesc(BankStatement::getPeriod)
                .orderByAsc(BankStatement::getAccountNo);
        for (BankStatement st : stmtDao.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", st.getId());
            m.put("bankAccountId", st.getBankAccountId());
            m.put("accountName", st.getAccountName());
            m.put("accountNo", st.getAccountNo());
            m.put("currency", st.getCurrency());
            m.put("period", st.getPeriod());
            m.put("stmtNo", st.getStmtNo());
            m.put("openingBalance", st.getOpeningBalance());
            m.put("closingBalance", st.getClosingBalance());
            m.put("status", st.getStatus());
            m.put("overTolerance", st.getOverTolerance());
            m.put("confirmBy", st.getConfirmBy());
            m.put("auditBy", st.getAuditBy());
            m.put("createDate", st.getCreateDate());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        BankStatement st = require(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", st);
        out.put("diffAmount", diffAmount(st));
        out.put("diffRate", diffRate(st));
        out.put("overTolerance", "1".equals(st.getOverTolerance()));
        Map<String, Object> table = reconcileTable(st);
        out.put("reconcileTable", table);
        out.put("outstanding", outstandingItems(st.getBankAccountId(), st.getPeriod()));
        return out;
    }

    // ================================================================
    // 确认状态机（2.3）
    // ================================================================

    @Override
    @Transactional
    public void confirm(String id) {
        requireAny("确认对账", "ROLE_FIN_ASSET", "ROLE_FIN_MGR");
        BankStatement st = require(id);
        if (!BankStatement.ST_DRAFT.equals(st.getStatus())) {
            throw new ServiceException(422, "仅草稿可确认，当前状态：" + st.getStatus());
        }
        Map<String, Object> table = reconcileTable(st);
        if (!Boolean.TRUE.equals(table.get("balanced"))) {
            throw new ServiceException(422, "调节表两侧不等，请继续排查差异：" + table.get("leftSide")
                    + " vs " + table.get("rightSide"));
        }
        st.setStatus(BankStatement.ST_CONFIRMED);
        st.setConfirmBy(currentUser());
        st.setConfirmAt(LocalDateTime.now());
        st.setUpdateBy(currentUser());
        st.setUpdateDate(LocalDateTime.now());
        stmtDao.updateById(st);
        log.info("bank statement {} confirmed by {} ({} {})", st.getId(), st.getConfirmBy(),
                st.getAccountNo(), st.getPeriod());
    }

    @Override
    @Transactional
    public void audit(String id) {
        requireAny("审核对账", "ROLE_FIN_MGR");
        BankStatement st = require(id);
        if (!BankStatement.ST_CONFIRMED.equals(st.getStatus())) {
            throw new ServiceException(422, "仅已确认可审核（职责分离），当前状态：" + st.getStatus());
        }
        st.setStatus(BankStatement.ST_AUDITED);
        st.setAuditBy(currentUser());
        st.setAuditAt(LocalDateTime.now());
        st.setUpdateBy(currentUser());
        st.setUpdateDate(LocalDateTime.now());
        stmtDao.updateById(st);
        log.info("bank statement {} audited by {} ({} {})", st.getId(), st.getAuditBy(),
                st.getAccountNo(), st.getPeriod());
    }

    @Override
    @Transactional
    public void revoke(String id, String reason) {
        BankStatement st = require(id);
        if (BankStatement.ST_DRAFT.equals(st.getStatus())) {
            return; // 幂等
        }
        if (BankStatement.ST_CONFIRMED.equals(st.getStatus())) {
            requireAny("撤销确认", "ROLE_FIN_ASSET", "ROLE_FIN_MGR");
            st.setStatus(BankStatement.ST_DRAFT);
            st.setConfirmBy(null);
            st.setConfirmAt(null);
        } else {
            requireAny("撤销审核", "ROLE_FIN_MGR");
            if (!hasText(reason)) {
                throw new ServiceException(422, "撤销审核必填原因");
            }
            st.setStatus(BankStatement.ST_DRAFT);
            st.setAuditBy(null);
            st.setAuditAt(null);
            st.setRevokeReason(reason);
        }
        st.setRevokeBy(currentUser());
        st.setRevokeAt(LocalDateTime.now());
        st.setUpdateBy(currentUser());
        st.setUpdateDate(LocalDateTime.now());
        stmtDao.updateById(st);
        log.info("bank statement {} revoked to DRAFT by {} reason={}", st.getId(), currentUser(), reason);
    }

    // ================================================================
    // 四类未达账项分类与账龄（3.1）
    // ================================================================

    @Override
    public Map<String, Object> outstandingItems(String bankAccountId, String period) {
        LocalDate[] range = periodRange(period);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> items = new ArrayList<>();

        // ①② 账面有银行无：期间 POSTED 未对账收付款单
        for (CashDoc d : cashDocDao.selectList(new LambdaQueryWrapper<CashDoc>()
                .eq(CashDoc::getBankAccountId, bankAccountId)
                .eq(CashDoc::getStatus, CashDoc.ST_POSTED)
                .between(CashDoc::getPayDate, range[0], range[1]))) {
            items.add(itemRow(
                    CashDoc.DIR_IN.equals(d.getDirection())
                            ? BankReconcileItem.TYPE_ENT_RCPT_BANK_PEND
                            : BankReconcileItem.TYPE_ENT_PAY_BANK_PEND,
                    BankReconcileItem.ANCHOR_CASH_DOC, d.getId(), d.getDocNo(),
                    nvl(d.getActualAmount()), d.getPayDate(), today));
        }
        // ③④ 银行有账面无：期间 UNMATCHED 回单
        for (BankReceipt r : receiptDao.selectList(new LambdaQueryWrapper<BankReceipt>()
                .eq(BankReceipt::getBankAccountId, bankAccountId)
                .eq(BankReceipt::getMatchStatus, BankReceipt.ST_UNMATCHED)
                .between(BankReceipt::getTradeDate, range[0], range[1]))) {
            items.add(itemRow(
                    BankReceipt.DIR_RECEIVE.equals(r.getDirection())
                            ? BankReconcileItem.TYPE_BANK_RCPT_ENT_PEND
                            : BankReconcileItem.TYPE_BANK_PAY_ENT_PEND,
                    BankReconcileItem.ANCHOR_RECEIPT, r.getId(), r.getReceiptNo(),
                    nvl(r.getAmount()), r.getTradeDate(), today));
        }

        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        sums.put(BankReconcileItem.TYPE_ENT_RCPT_BANK_PEND, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        sums.put(BankReconcileItem.TYPE_ENT_PAY_BANK_PEND, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        sums.put(BankReconcileItem.TYPE_BANK_RCPT_ENT_PEND, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        sums.put(BankReconcileItem.TYPE_BANK_PAY_ENT_PEND, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        int agedCount = 0;
        for (Map<String, Object> m : items) {
            sums.merge((String) m.get("itemType"), (BigDecimal) m.get("amount"), BigDecimal::add);
            if (Boolean.TRUE.equals(m.get("aged"))) {
                agedCount++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("sums", sums);
        out.put("agedCount", agedCount);
        return out;
    }

    // ================================================================
    // 差异率（3.2）与调节表（3.3）
    // ================================================================

    @Override
    public Map<String, Object> closingCheck(String period) {
        long total = stmtDao.countStatementsForActive(period);
        long unaudited = stmtDao.countUnauditedForActive(period);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", total);
        out.put("unaudited", unaudited);
        if (unaudited > 0) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (BankStatement st : stmtDao.selectList(new LambdaQueryWrapper<BankStatement>()
                    .eq(BankStatement::getPeriod, period)
                    .ne(BankStatement::getStatus, BankStatement.ST_AUDITED)
                    .orderByAsc(BankStatement::getAccountNo))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("accountName", st.getAccountName());
                m.put("period", st.getPeriod());
                m.put("status", st.getStatus());
                list.add(m);
            }
            out.put("unauditedList", list);
        }
        return out;
    }

    /** 差异 = |银行期末 − 账面|；差异率 = 差异 / 银行期末（银行 0 按 0） */
    private BigDecimal diffAmount(BankStatement st) {
        FinBankAccount acct = bankDao.selectById(st.getBankAccountId());
        BigDecimal book = acct == null ? BigDecimal.ZERO : nvl(acct.getBalance());
        return st.getClosingBalance().subtract(book).abs().setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal diffRate(BankStatement st) {
        if (st.getClosingBalance().signum() == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return diffAmount(st).divide(st.getClosingBalance(), 4, RoundingMode.HALF_UP);
    }

    private void applyDiffFlag(BankStatement st) {
        boolean over = diffRate(st).compareTo(TOLERANCE) > 0;
        st.setOverTolerance(over ? "1" : "0");
        stmtDao.updateById(st);
    }

    /** 调节表（FR-4.6-6-6）：账面 + ③ − ④ = 银行 + ① − ② */
    private Map<String, Object> reconcileTable(BankStatement st) {
        Map<String, Object> o = outstandingItems(st.getBankAccountId(), st.getPeriod());
        @SuppressWarnings("unchecked")
        Map<String, BigDecimal> sums = (Map<String, BigDecimal>) o.get("sums");
        FinBankAccount acct = bankDao.selectById(st.getBankAccountId());
        BigDecimal book = acct == null ? BigDecimal.ZERO : nvl(acct.getBalance()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal left = book.add(sums.get(BankReconcileItem.TYPE_BANK_RCPT_ENT_PEND))
                .subtract(sums.get(BankReconcileItem.TYPE_BANK_PAY_ENT_PEND));
        BigDecimal right = st.getClosingBalance()
                .add(sums.get(BankReconcileItem.TYPE_ENT_RCPT_BANK_PEND))
                .subtract(sums.get(BankReconcileItem.TYPE_ENT_PAY_BANK_PEND));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bookBalance", book);
        out.put("bankClosingBalance", st.getClosingBalance());
        out.put("sums", sums);
        out.put("leftSide", left.setScale(2, RoundingMode.HALF_UP));
        out.put("rightSide", right.setScale(2, RoundingMode.HALF_UP));
        out.put("balanced", left.setScale(2, RoundingMode.HALF_UP)
                .compareTo(right.setScale(2, RoundingMode.HALF_UP)) == 0);
        return out;
    }

    // ================================================================
    // 差异处理留痕（3.4）
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> markHandling(Map<String, Object> payload) {
        requireAny("处理对账差异", "ROLE_FIN_ASSET", "ROLE_FIN_MGR");
        String status = str(payload.get("status"));
        if (!BankReconcileItem.ST_PROCESSING.equals(status)
                && !BankReconcileItem.ST_DONE.equals(status)
                && !BankReconcileItem.ST_ESCALATED.equals(status)) {
            throw new ServiceException(422, "处理状态仅支持 PROCESSING/DONE/ESCALATED");
        }
        BankReconcileItem it = new BankReconcileItem();
        it.setId(uuid());
        it.setBankAccountId(str(payload.get("bankAccountId")));
        it.setPeriod(str(payload.get("period")));
        it.setItemType(str(payload.get("itemType")));
        it.setAnchorType(str(payload.get("anchorType")));
        it.setAnchorId(str(payload.get("anchorId")));
        it.setAnchorNo(str(payload.get("anchorNo")));
        it.setAmount(dec(payload.get("amount")));
        it.setTradeDate(payload.get("tradeDate") == null ? null
                : LocalDate.parse(str(payload.get("tradeDate"))));
        it.setStatus(status);
        it.setHandlingNote(str(payload.get("handlingNote")));
        it.setHandleBy(currentUser());
        it.setHandleAt(LocalDateTime.now());
        it.setCreateBy(currentUser());
        it.setCreateDate(LocalDateTime.now());
        it.setVerNo(0);
        itemDao.insert(it);
        log.info("bank reconcile item handled: {} {} {} by {}", it.getPeriod(), it.getItemType(),
                it.getAnchorNo(), it.getHandleBy());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", it.getId());
        out.put("status", it.getStatus());
        return out;
    }

    @Override
    public List<Map<String, Object>> handlingRecords(String bankAccountId, String period) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BankReconcileItem it : itemDao.selectList(new LambdaQueryWrapper<BankReconcileItem>()
                .eq(BankReconcileItem::getBankAccountId, bankAccountId)
                .eq(BankReconcileItem::getPeriod, period)
                .orderByDesc(BankReconcileItem::getHandleAt))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("itemType", it.getItemType());
            m.put("anchorType", it.getAnchorType());
            m.put("anchorNo", it.getAnchorNo());
            m.put("amount", it.getAmount());
            m.put("status", it.getStatus());
            m.put("handlingNote", it.getHandlingNote());
            m.put("handleBy", it.getHandleBy());
            m.put("handleAt", it.getHandleAt());
            out.add(m);
        }
        return out;
    }

    // ================================================================
    // D6 已审期间锁定（2.4 守卫）
    // ================================================================

    @Override
    public void assertNotAuditedCover(String bankAccountId, LocalDate tradeDate, String action) {
        if (tradeDate == null || !hasText(bankAccountId)) {
            return;
        }
        String period = tradeDate.format(YM);
        List<String> audited = stmtDao.selectAuditedAccountIds(period);
        if (audited.contains(bankAccountId)) {
            throw new ServiceException(422,
                    "该账户 " + period + " 对账已审核（AUDITED），先撤销对账方可" + action);
        }
    }

    // ================================================================
    // 工具
    // ================================================================

    private BankStatement require(String id) {
        BankStatement st = stmtDao.selectById(id);
        if (st == null) {
            throw new ServiceException(404, "对账单不存在");
        }
        return st;
    }

    /** 本期已匹配回单净额（收 − 付，勾稽自校验用） */
    private BigDecimal matchedNet(String accountId, String period) {
        LocalDate[] range = periodRange(period);
        BigDecimal net = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        for (BankReceipt r : receiptDao.selectList(new LambdaQueryWrapper<BankReceipt>()
                .eq(BankReceipt::getBankAccountId, accountId)
                .eq(BankReceipt::getMatchStatus, BankReceipt.ST_MATCHED)
                .between(BankReceipt::getTradeDate, range[0], range[1]))) {
            BigDecimal amt = nvl(r.getAmount());
            net = BankReceipt.DIR_RECEIVE.equals(r.getDirection()) ? net.add(amt) : net.subtract(amt);
        }
        return net;
    }

    private Map<String, Object> itemRow(String itemType, String anchorType, String anchorId,
                                        String anchorNo, BigDecimal amount, LocalDate tradeDate,
                                        LocalDate today) {
        Map<String, Object> m = new LinkedHashMap<>();
        long age = tradeDate == null ? 0 : ChronoUnit.DAYS.between(tradeDate, today);
        m.put("itemType", itemType);
        m.put("anchorType", anchorType);
        m.put("anchorId", anchorId);
        m.put("anchorNo", anchorNo);
        m.put("amount", amount);
        m.put("tradeDate", tradeDate);
        m.put("ageDays", age);
        m.put("aged", age > AGING_DAYS);
        return m;
    }

    private static LocalDate[] periodRange(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        LocalDate start = LocalDate.of(y, m, 1);
        return new LocalDate[]{start, start.withDayOfMonth(start.lengthOfMonth())};
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
        throw new ServiceException(403, "无权" + action);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal dec(Object o) {
        if (o == null || str(o).isBlank()) {
            return null;
        }
        return new BigDecimal(str(o));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return "system";
        }
    }
}
