package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.BankReceiptDao;
import com.erp.dao.fin.CashDocDao;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.entity.fin.BankReceipt;
import com.erp.entity.fin.CashDoc;
import com.erp.entity.fin.FinBankAccount;
import com.erp.service.fin.BankReceiptService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
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
 * 回单对账实现（spec bank-receipt-reconciliation，design D8）。
 * 自动匹配候选 = 同账户 + 方向映射（RECEIVE↔IN/PAY↔OUT）+ 金额相等 + 日期 ±3 天，一对一占用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankReceiptServiceImpl implements BankReceiptService {

    private final BankReceiptDao receiptDao;
    private final CashDocDao docDao;
    private final FinBankAccountDao bankDao;
    /** D6 已审期间锁定守卫（add-bank-reconciliation）：AUDITED 对账单覆盖的账户+期间禁匹配/解除 */
    private final com.erp.service.fin.BankReconciliationService bankReconciliationService;

    // ================================================================
    // 录入 / 导入
    // ================================================================

    @Override
    @Transactional
    public BankReceipt create(Map<String, Object> payload) {
        requireAny("录入银行回单", "ROLE_FINANCE_MGR");
        String receiptNo = str(payload.get("receiptNo"));
        if (!hasText(receiptNo)) {
            throw new ServiceException(422, "回单号必填");
        }
        if (receiptDao.selectCount(new LambdaQueryWrapper<BankReceipt>()
                .eq(BankReceipt::getReceiptNo, receiptNo)) > 0) {
            throw new ServiceException(422, "回单号已存在：" + receiptNo);
        }
        FinBankAccount bank = bankDao.selectById(str(payload.get("bankAccountId")));
        if (bank == null) {
            throw new ServiceException(422, "银行账户不存在");
        }
        String direction = str(payload.get("direction"));
        if (!BankReceipt.DIR_RECEIVE.equals(direction) && !BankReceipt.DIR_PAY.equals(direction)) {
            throw new ServiceException(422, "方向仅支持 RECEIVE/PAY");
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(str(payload.get("amount")));
        } catch (NumberFormatException e) {
            throw new ServiceException(422, "金额格式错误");
        }
        if (amount.signum() <= 0) {
            throw new ServiceException(422, "金额必须大于 0");
        }
        LocalDate tradeDate;
        try {
            tradeDate = LocalDate.parse(str(payload.get("tradeDate")));
        } catch (RuntimeException e) {
            throw new ServiceException(422, "交易日期格式须为 yyyy-MM-dd");
        }

        BankReceipt r = new BankReceipt();
        r.setId(uuid());
        r.setReceiptNo(receiptNo);
        r.setBankAccountId(bank.getId());
        r.setTradeDate(tradeDate);
        r.setDirection(direction);
        r.setAmount(amount);
        r.setCounterparty(str(payload.get("counterparty")));
        r.setSummary(str(payload.get("summary")));
        r.setSource(BankReceipt.SRC_MANUAL);
        r.setMatchStatus(BankReceipt.ST_UNMATCHED);
        r.setCreateBy(currentUser());
        receiptDao.insert(r);
        return r;
    }

    @Override
    @Transactional
    public Map<String, Object> importCsv(String csvContent) {
        requireAny("导入银行回单", "ROLE_FINANCE_MGR");
        if (!hasText(csvContent)) {
            throw new ServiceException(422, "CSV 内容为空");
        }
        int ok = 0;
        int dup = 0;
        List<Map<String, Object>> errors = new ArrayList<>();
        String[] lines = csvContent.split("\\r?\\n");
        boolean header = true;
        for (String line : lines) {
            if (!hasText(line)) {
                continue;
            }
            if (header) {
                header = false;
                continue; // 表头行
            }
            String[] cols = line.split(",", -1);
            if (cols.length < 7) {
                errors.add(errRow(line, "列数不足（须 7 列：回单号/账户ID/日期/方向/金额/对方户名/摘要）"));
                continue;
            }
            String receiptNo = cols[0].trim();
            try {
                Map<String, Object> p = new LinkedHashMap<>();
                p.put("receiptNo", receiptNo);
                p.put("bankAccountId", cols[1].trim());
                p.put("tradeDate", cols[2].trim());
                p.put("direction", cols[3].trim());
                p.put("amount", cols[4].trim());
                p.put("counterparty", cols[5].trim());
                p.put("summary", cols[6].trim());
                BankReceipt r = create(p);
                r.setSource(BankReceipt.SRC_IMPORT);
                receiptDao.updateById(r);
                ok++;
            } catch (ServiceException e) {
                if (e.getMessage() != null && e.getMessage().contains("已存在")) {
                    dup++; // 重复行跳过并计数（spec 批量导入部分失败）
                } else {
                    errors.add(errRow(line, e.getMessage()));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", ok);
        out.put("duplicates", dup);
        out.put("failed", errors.size());
        out.put("errors", errors);
        log.info("bank receipt import: ok={} dup={} failed={}", ok, dup, errors.size());
        return out;
    }

    // ================================================================
    // 匹配
    // ================================================================

    @Override
    @Transactional
    public Map<String, Object> autoMatch() {
        requireAny("回单自动匹配", "ROLE_FINANCE_MGR");
        int matched = 0;
        List<Map<String, Object>> pending = new ArrayList<>();
        // 以未匹配回单为主驱动（回单为银行侧事实源）
        List<BankReceipt> receipts = receiptDao.selectList(new LambdaQueryWrapper<BankReceipt>()
                .eq(BankReceipt::getMatchStatus, BankReceipt.ST_UNMATCHED)
                .eq(BankReceipt::getDelFlag, "0")
                .orderByAsc(BankReceipt::getTradeDate));
        for (BankReceipt r : receipts) {
            // D6 已审期间锁定：AUDITED 对账单覆盖的回单跳过（不参与自动匹配，防已审数据漂移）
            try {
                bankReconciliationService.assertNotAuditedCover(
                        r.getBankAccountId(), r.getTradeDate(), "自动匹配");
            } catch (ServiceException locked) {
                pending.add(pendingRow(r, "该账户+期间对账已审核，跳过（先撤销对账）"));
                continue;
            }
            String docDir = BankReceipt.DIR_RECEIVE.equals(r.getDirection())
                    ? CashDoc.DIR_IN : CashDoc.DIR_OUT;
            CashDoc doc = docDao.selectOne(new LambdaQueryWrapper<CashDoc>()
                    .eq(CashDoc::getDirection, docDir)
                    .eq(CashDoc::getBankAccountId, r.getBankAccountId())
                    .eq(CashDoc::getStatus, CashDoc.ST_POSTED)
                    .eq(CashDoc::getAmount, r.getAmount())
                    .between(CashDoc::getPayDate,
                            r.getTradeDate().minusDays(3), r.getTradeDate().plusDays(3))
                    .last("LIMIT 1"));
            if (doc == null) {
                pending.add(pendingRow(r, "无候选单据（账户/方向/金额/日期±3 天）"));
                continue;
            }
            matchPair(r, doc);
            matched++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", matched);
        out.put("unmatched", pending.size());
        out.put("pending", pending);
        return out;
    }

    @Override
    @Transactional
    public BankReceipt manualMatch(String receiptId, String docId) {
        requireAny("回单人工匹配", "ROLE_FINANCE_MGR");
        BankReceipt r = require(receiptId);
        bankReconciliationService.assertNotAuditedCover(r.getBankAccountId(), r.getTradeDate(), "匹配");
        if (!BankReceipt.ST_UNMATCHED.equals(r.getMatchStatus())) {
            throw new ServiceException(422, "回单已被匹配或异常，不可重复匹配：" + r.getMatchStatus());
        }
        CashDoc doc = docDao.selectById(docId);
        if (doc == null) {
            throw new ServiceException(422, "收付款单不存在");
        }
        if (CashDoc.ST_RECONCILED.equals(doc.getStatus())) {
            throw new ServiceException(422, "单据已对账，不可重复匹配：" + doc.getDocNo());
        }
        if (!CashDoc.ST_POSTED.equals(doc.getStatus())) {
            throw new ServiceException(422, "仅已过账单据可匹配：" + doc.getStatus());
        }
        String expect = BankReceipt.DIR_RECEIVE.equals(r.getDirection())
                ? CashDoc.DIR_IN : CashDoc.DIR_OUT;
        if (!expect.equals(doc.getDirection())) {
            throw new ServiceException(422, "方向不一致：回单 " + r.getDirection()
                    + " 与单据 " + doc.getDirection() + " 不可匹配");
        }
        if (!r.getBankAccountId().equals(doc.getBankAccountId())) {
            throw new ServiceException(422, "银行账户不一致，不可匹配");
        }
        matchPair(r, doc);
        return r;
    }

    @Override
    @Transactional
    public BankReceipt markException(String receiptId, String reason) {
        requireAny("标记回单异常", "ROLE_FINANCE_MGR");
        BankReceipt r = require(receiptId);
        if (!BankReceipt.ST_UNMATCHED.equals(r.getMatchStatus())) {
            throw new ServiceException(422, "仅未匹配回单可标记异常：" + r.getMatchStatus());
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "异常原因必填");
        }
        r.setMatchStatus(BankReceipt.ST_EXCEPTION);
        r.setExceptionReason(reason);
        r.setUpdateBy(currentUser());
        receiptDao.updateById(r);
        return r;
    }

    @Override
    @Transactional
    public BankReceipt unreconcile(String receiptId, String reason) {
        requireAny("解除回单匹配", "ROLE_FINANCE_MGR");
        BankReceipt r = require(receiptId);
        bankReconciliationService.assertNotAuditedCover(r.getBankAccountId(), r.getTradeDate(), "解除匹配");
        if (!BankReceipt.ST_MATCHED.equals(r.getMatchStatus())) {
            throw new ServiceException(422, "仅已匹配回单可解除：" + r.getMatchStatus());
        }
        if (!hasText(reason)) {
            throw new ServiceException(422, "解除原因必填");
        }
        CashDoc doc = docDao.selectById(r.getMatchedDocId());
        if (doc != null) {
            // 单据回 POSTED（RECONCILED 禁冲销随状态自然解除）
            docDao.update(null, new LambdaUpdateWrapper<CashDoc>()
                    .eq(CashDoc::getId, doc.getId())
                    .eq(CashDoc::getStatus, CashDoc.ST_RECONCILED)
                    .set(CashDoc::getStatus, CashDoc.ST_POSTED)
                    .set(CashDoc::getUpdateBy, currentUser()));
        }
        receiptDao.update(null, new LambdaUpdateWrapper<BankReceipt>()
                .eq(BankReceipt::getId, r.getId())
                .eq(BankReceipt::getMatchStatus, BankReceipt.ST_MATCHED)
                .set(BankReceipt::getMatchStatus, BankReceipt.ST_UNMATCHED)
                .set(BankReceipt::getMatchedDocId, null)
                .set(BankReceipt::getMatchedDocNo, null)
                .set(BankReceipt::getMatchedBy, null)
                .set(BankReceipt::getMatchedAt, null)
                .set(BankReceipt::getReconcileBy, currentUser())
                .set(BankReceipt::getReconcileAt, LocalDateTime.now())
                .set(BankReceipt::getReconcileReason, reason)
                .set(BankReceipt::getUpdateBy, currentUser()));
        log.info("bank receipt {} unreconciled: {}", r.getReceiptNo(), reason);
        return receiptDao.selectById(receiptId);
    }

    // ================================================================
    // 查询
    // ================================================================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String matchStatus,
                                          String keyword, String dateFrom, String dateTo) {
        LambdaQueryWrapper<BankReceipt> qw = new LambdaQueryWrapper<BankReceipt>()
                .eq(hasText(matchStatus), BankReceipt::getMatchStatus, matchStatus)
                .like(hasText(keyword), BankReceipt::getReceiptNo, keyword)
                .ge(hasText(dateFrom), BankReceipt::getTradeDate, dateFrom)
                .le(hasText(dateTo), BankReceipt::getTradeDate, dateTo)
                .orderByDesc(BankReceipt::getTradeDate);
        Page<BankReceipt> raw = receiptDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (BankReceipt r : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("receiptNo", r.getReceiptNo());
            m.put("bankAccountId", r.getBankAccountId());
            m.put("tradeDate", r.getTradeDate());
            m.put("direction", r.getDirection());
            m.put("amount", r.getAmount());
            m.put("counterparty", r.getCounterparty());
            m.put("source", r.getSource());
            m.put("matchStatus", r.getMatchStatus());
            m.put("matchedDocNo", r.getMatchedDocNo());
            m.put("exceptionReason", r.getExceptionReason());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        BankReceipt r = require(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receipt", r);
        if (hasText(r.getMatchedDocId())) {
            m.put("doc", docDao.selectById(r.getMatchedDocId()));
        }
        return m;
    }

    // ================================================================
    // 内部
    // ================================================================

    /** 匹配落账：回单 MATCHED + 单据 RECONCILED（spec 回单与收付款单逐笔匹配） */
    private void matchPair(BankReceipt r, CashDoc doc) {
        int docRows = docDao.update(null, new LambdaUpdateWrapper<CashDoc>()
                .eq(CashDoc::getId, doc.getId())
                .eq(CashDoc::getStatus, CashDoc.ST_POSTED)
                .set(CashDoc::getStatus, CashDoc.ST_RECONCILED)
                .set(CashDoc::getUpdateBy, currentUser()));
        if (docRows == 0) {
            throw new ServiceException(409, "单据对账状态并发冲突：" + doc.getDocNo());
        }
        int rRows = receiptDao.update(null, new LambdaUpdateWrapper<BankReceipt>()
                .eq(BankReceipt::getId, r.getId())
                .eq(BankReceipt::getMatchStatus, BankReceipt.ST_UNMATCHED)
                .set(BankReceipt::getMatchStatus, BankReceipt.ST_MATCHED)
                .set(BankReceipt::getMatchedDocId, doc.getId())
                .set(BankReceipt::getMatchedDocNo, doc.getDocNo())
                .set(BankReceipt::getMatchedBy, currentUser())
                .set(BankReceipt::getMatchedAt, LocalDateTime.now())
                .set(BankReceipt::getUpdateBy, currentUser()));
        if (rRows == 0) {
            throw new ServiceException(409, "回单匹配并发冲突：" + r.getReceiptNo());
        }
        log.info("bank receipt {} matched to doc {}", r.getReceiptNo(), doc.getDocNo());
    }

    private Map<String, Object> pendingRow(BankReceipt r, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("receiptNo", r.getReceiptNo());
        m.put("amount", r.getAmount());
        m.put("tradeDate", r.getTradeDate());
        m.put("reason", reason);
        return m;
    }

    private Map<String, Object> errRow(String line, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", line.length() > 80 ? line.substring(0, 80) : line);
        m.put("reason", reason);
        return m;
    }

    private BankReceipt require(String id) {
        BankReceipt r = receiptDao.selectById(id);
        if (r == null) {
            throw new ServiceException(422, "银行回单不存在");
        }
        return r;
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
