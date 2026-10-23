package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.util.SecurityUtils;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.entity.fin.FinBankAccount;
import com.erp.service.fin.BankAccountService;
import com.erp.service.fin.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** 银行账户实现（spec bank-account，design D1/D4；add-fx-revaluation D2 三列余额模型）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankAccountServiceImpl implements BankAccountService {

    /** ISO 4217 三字母币种码 */
    private static final Pattern CCY = Pattern.compile("^[A-Z]{3}$");

    private final FinBankAccountDao bankDao;
    private final FxRateService fxRateService;

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FinBankAccount a : bankDao.selectList(new LambdaQueryWrapper<FinBankAccount>()
                .orderByAsc(FinBankAccount::getAccountNo))) {
            out.add(row(a));
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        FinBankAccount a = bankDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "银行账户不存在");
        }
        return row(a);
    }

    @Override
    public Map<String, Object> create(Map<String, Object> payload) {
        String accountName = str(payload.get("accountName"));
        String accountNo = str(payload.get("accountNo"));
        if (accountName == null || accountName.isBlank()) {
            throw new ServiceException(422, "账户名称必填");
        }
        if (accountNo == null || accountNo.isBlank()) {
            throw new ServiceException(422, "账号必填");
        }
        // 账号唯一（spec bank-account：一个账号只对应一个账户）
        Long dup = bankDao.selectCount(new LambdaQueryWrapper<FinBankAccount>()
                .eq(FinBankAccount::getAccountNo, accountNo));
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "账号已存在：" + accountNo);
        }
        String currency = str(payload.get("currency"));
        if (currency == null || currency.isBlank()) {
            currency = "CNY";
        }
        if (!CCY.matcher(currency).matches()) {
            throw new ServiceException(422, "币种须为 ISO 4217 三字母码（如 CNY/USD/EUR）：" + currency);
        }
        BigDecimal initBalance = dec(payload.get("balance"));
        if (initBalance == null || initBalance.signum() < 0) {
            throw new ServiceException(422, "期初余额不得为负（缺省 0）");
        }
        LocalDate openDate;
        try {
            String d = str(payload.get("openDate"));
            openDate = d == null || d.isBlank() ? LocalDate.now() : LocalDate.parse(d);
        } catch (RuntimeException e) {
            throw new ServiceException(422, "开户日期格式须为 yyyy-MM-dd");
        }

        FinBankAccount a = new FinBankAccount();
        a.setId(UUID.randomUUID().toString().replace("-", ""));
        a.setAccountName(accountName);
        a.setAccountNo(accountNo);
        a.setCurrency(currency);
        a.setStatus(FinBankAccount.ST_ACTIVE);
        a.setRemark(str(payload.get("remark")));
        a.setCreateBy(SecurityUtils.getCurrentUserId());
        if ("CNY".equals(currency)) {
            // CNY 账户：三列同步（FC_BALANCE≡BALANCE、BOOKING_RATE≡1），不查汇率主数据
            a.setBalance(initBalance);
            a.setFcBalance(initBalance);
            a.setBookingRate(BigDecimal.ONE);
        } else {
            // 外币账户：开户日中间价固化 BOOKING_RATE（缺汇率 L1 阻断 C-4.6-03）；
            // 期初余额视为外币额写 FC_BALANCE，BALANCE = 外币额 × 记账汇率（本位币 2 位）
            BigDecimal rate = fxRateService.requireMiddleRate(currency, "CNY", openDate);
            BigDecimal bal = initBalance.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            a.setBookingRate(rate.setScale(6, RoundingMode.HALF_UP));
            a.setFcBalance(initBalance);
            a.setBalance(bal);
        }
        bankDao.insert(a);
        log.info("bank account opened: {} {} {} fc={} bal={} rate={}", accountNo, accountName,
                currency, a.getFcBalance(), a.getBalance(), a.getBookingRate());
        return row(a);
    }

    private Map<String, Object> row(FinBankAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("accountName", a.getAccountName());
        m.put("accountNo", a.getAccountNo());
        m.put("currency", a.getCurrency());
        m.put("balance", a.getBalance());
        m.put("fcBalance", a.getFcBalance());
        m.put("bookingRate", a.getBookingRate());
        m.put("status", a.getStatus());
        m.put("remark", a.getRemark());
        return m;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal d) {
            return d;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
