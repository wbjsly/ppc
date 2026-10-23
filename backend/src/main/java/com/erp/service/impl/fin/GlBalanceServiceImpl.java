package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinBalanceDao;
import com.erp.dao.fin.FinVoucherLineDao;
import com.erp.entity.fin.FinBalance;
import com.erp.entity.fin.FinVoucher;
import com.erp.entity.fin.FinVoucherLine;
import com.erp.service.fin.GlBalanceService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 科目余额实现（add-gl-management，design D3）。
 * AUX_KEY=六辅助列 | 拼接（空串=无辅助）；journal 表按 (VOUCHER_ID,LINE_NO,DIRECTION) 唯一防重放双计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GlBalanceServiceImpl implements GlBalanceService {

    private static final DateTimeFormatter PERIOD_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final FinBalanceDao balanceDao;
    private final FinVoucherLineDao lineDao;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void writeBack(FinVoucher voucher) {
        if (voucher == null || !FinVoucher.STATUS_POSTED.equals(voucher.getStatus())) {
            return;   // 仅 POSTED 写余额
        }
        List<FinVoucherLine> lines = lineDao.selectList(new LambdaQueryWrapper<FinVoucherLine>()
                .eq(FinVoucherLine::getVoucherId, voucher.getId())
                .orderByAsc(FinVoucherLine::getLineNo));
        for (FinVoucherLine l : lines) {
            String auxKey = auxKey(l.getCostCenter(), l.getProfitCenter(), l.getAuxCustomer(),
                    l.getAuxSupplier(), l.getAuxProject(), l.getAuxEmployee(), l.getAuxDept());
            // journal 幂等：已回写则跳过（防 create 重放/冲销双计）
            if (balanceDao.journalExists(voucher.getId(), l.getLineNo(), l.getDirection()) > 0) {
                continue;
            }
            upsert(voucher.getPeriod(), l.getAccountCode(), auxKey, l.getDirection(), l.getAmount());
            // journal 落账（唯一索引兜底并发）
            jdbcTemplate.update("INSERT IGNORE INTO erp_fin_balance_journal "
                            + "(ID, VOUCHER_ID, LINE_NO, PERIOD, ACCOUNT_CODE, AUX_KEY, DIRECTION, AMOUNT) "
                            + "VALUES (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString().replace("-", ""), voucher.getId(), l.getLineNo(),
                    voucher.getPeriod(), l.getAccountCode(), auxKey, l.getDirection(), l.getAmount());
        }
        log.debug("voucher {} balances written back ({} lines)", voucher.getVoucherNo(), lines.size());
    }

    private void upsert(String period, String accountCode, String auxKey,
                        String direction, BigDecimal amount) {
        FinBalance bal = balanceDao.selectByKey(period, accountCode, auxKey);
        if (bal == null) {
            bal = new FinBalance();
            bal.setPeriod(period);
            bal.setAccountCode(accountCode);
            bal.setAuxKey(auxKey == null ? "" : auxKey);
            bal.setDrTotal("DR".equals(direction) ? amount : BigDecimal.ZERO);
            bal.setCrTotal("CR".equals(direction) ? amount : BigDecimal.ZERO);
            bal.setCreateBy(SecurityUtils.getCurrentUserId());
            balanceDao.insert(bal);
        } else {
            BigDecimal drAdd = "DR".equals(direction) ? amount : BigDecimal.ZERO;
            BigDecimal crAdd = "CR".equals(direction) ? amount : BigDecimal.ZERO;
            // UPDATE 累加（避开并发下 SELECT+UPDATE 竞态由唯一索引兜底重试）
            balanceDao.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FinBalance>()
                    .eq(FinBalance::getId, bal.getId())
                    .setSql("DR_TOTAL = DR_TOTAL + " + drAdd.toPlainString())
                    .setSql("CR_TOTAL = CR_TOTAL + " + crAdd.toPlainString())
                    .set(FinBalance::getUpdateBy, SecurityUtils.getCurrentUserId())
                    .set(FinBalance::getVerNo, bal.getVerNo() + 1));
        }
    }

    @Override
    public String auxKey(String costCenter, String profitCenter, String auxCustomer,
                         String auxSupplier, String auxProject, String auxEmployee, String auxDept) {
        // 六列 | 拼接，去除尾部空段（全空 → ''）
        List<String> parts = new java.util.ArrayList<>(List.of(
                nz(costCenter), nz(profitCenter), nz(auxCustomer),
                nz(auxSupplier), nz(auxProject), nz(auxEmployee), nz(auxDept)));
        // 剥首尾空段（保留中间空位语义）："|CUST-A|||" → "CUST-A"；"A||B" → "A||B"
        int start = 0;
        int end = parts.size() - 1;
        while (start <= end && parts.get(start).isEmpty()) {
            start++;
        }
        while (end >= start && parts.get(end).isEmpty()) {
            end--;
        }
        if (start > end) {
            return "";
        }
        return String.join("|", parts.subList(start, end + 1));
    }

    @Override
    public List<Map<String, Object>> query(String period, String accountCode, String auxKey) {
        List<Map<String, Object>> rows = balanceDao.queryBalances(period, accountCode, auxKey);
        // 期初 = 上期期末（DR−CR 累计）
        String prev = prevPeriod(period);
        for (Map<String, Object> r : rows) {
            String code = String.valueOf(r.get("ACCOUNT_CODE"));
            String key = r.get("AUX_KEY") == null ? "" : String.valueOf(r.get("AUX_KEY"));
            BigDecimal open = BigDecimal.ZERO;
            for (Map<String, Object> p : balanceDao.queryBalances(prev, code, key)) {
                open = open.add(new BigDecimal(String.valueOf(p.get("BALANCE"))));
            }
            // 期初 + 本期借 − 本期贷 = 期末
            BigDecimal dr = new BigDecimal(String.valueOf(r.get("DR_TOTAL")));
            BigDecimal cr = new BigDecimal(String.valueOf(r.get("CR_TOTAL")));
            r.put("openBalance", open.setScale(2, java.math.RoundingMode.HALF_UP));
            r.put("closeBalance", open.add(dr).subtract(cr).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return rows;
    }

    @Override
    public List<Map<String, Object>> periodBalances(String period) {
        return balanceDao.queryBalances(period, null, null);
    }

    private static String prevPeriod(String period) {
        int y = Integer.parseInt(period.substring(0, 4));
        int m = Integer.parseInt(period.substring(4, 6));
        m--;
        if (m == 0) {
            m = 12;
            y--;
        }
        return String.format("%d%02d", y, m);
    }

    private static String nz(String v) {
        return v == null ? "" : v.trim();
    }
}
