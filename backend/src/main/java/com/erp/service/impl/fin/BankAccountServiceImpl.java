package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinBankAccountDao;
import com.erp.entity.fin.FinBankAccount;
import com.erp.service.fin.BankAccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 银行账户实现（spec bank-account，design D1/D4）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankAccountServiceImpl implements BankAccountService {

    private final FinBankAccountDao bankDao;

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

    private Map<String, Object> row(FinBankAccount a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("accountName", a.getAccountName());
        m.put("accountNo", a.getAccountNo());
        m.put("currency", a.getCurrency());
        m.put("balance", a.getBalance());
        m.put("status", a.getStatus());
        m.put("remark", a.getRemark());
        return m;
    }
}
