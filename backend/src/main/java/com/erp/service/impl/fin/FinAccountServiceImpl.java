package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.fin.FinAccountDao;
import com.erp.entity.fin.FinAccount;
import com.erp.service.fin.FinAccountService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会计科目维护实现（add-gl-management，design D6/D7：编码只增停不改）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FinAccountServiceImpl implements FinAccountService {

    private final FinAccountDao accountDao;

    @Override
    public Page<FinAccount> page(long current, long size, String keyword, String status, String category) {
        LambdaQueryWrapper<FinAccount> qw = new LambdaQueryWrapper<FinAccount>()
                .eq(FinAccount::getDelFlag, "0")
                .orderByAsc(FinAccount::getAccountCode);
        if (hasText(keyword)) {
            qw.and(w -> w.like(FinAccount::getAccountCode, keyword)
                    .or().like(FinAccount::getAccountName, keyword));
        }
        if (hasText(status)) {
            qw.eq(FinAccount::getStatus, status);
        }
        if (hasText(category)) {
            qw.eq(FinAccount::getCategory, category);
        }
        return accountDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public FinAccount create(FinAccount account) {
        if (!hasText(account.getAccountCode()) || !hasText(account.getAccountName())) {
            throw new ServiceException(422, "科目编码与名称必填");
        }
        if (!"DR".equals(account.getDirection()) && !"CR".equals(account.getDirection())) {
            throw new ServiceException(422, "方向仅 DR（借）/ CR（贷）");
        }
        Long dup = accountDao.selectCount(new LambdaQueryWrapper<FinAccount>()
                .eq(FinAccount::getAccountCode, account.getAccountCode()));
        if (dup != null && dup > 0) {
            throw new ServiceException(422, "科目编码已存在：" + account.getAccountCode());
        }
        account.setId(null);
        account.setStatus(hasText(account.getStatus()) ? account.getStatus() : "ACTIVE");
        account.setCreateBy(SecurityUtils.getCurrentUserId());
        accountDao.insert(account);
        return account;
    }

    @Override
    @Transactional
    public void setStatus(String accountCode, String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new ServiceException(422, "状态仅 ACTIVE / DISABLED");
        }
        FinAccount acc = accountDao.selectOne(new LambdaQueryWrapper<FinAccount>()
                .eq(FinAccount::getAccountCode, accountCode)
                .eq(FinAccount::getDelFlag, "0").last("LIMIT 1"));
        if (acc == null) {
            throw new ServiceException(404, "科目不存在：" + accountCode);
        }
        accountDao.update(null, new LambdaUpdateWrapper<FinAccount>()
                .eq(FinAccount::getId, acc.getId())
                .set(FinAccount::getStatus, status)
                .set(FinAccount::getUpdateBy, SecurityUtils.getCurrentUserId())
                .set(FinAccount::getVerNo, acc.getVerNo() + 1));
    }

    @Override
    public Map<String, Object> detail(String accountCode) {
        FinAccount acc = accountDao.selectOne(new LambdaQueryWrapper<FinAccount>()
                .eq(FinAccount::getAccountCode, accountCode)
                .eq(FinAccount::getDelFlag, "0").last("LIMIT 1"));
        if (acc == null) {
            throw new ServiceException(404, "科目不存在：" + accountCode);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("account", acc);
        out.put("auxSet", acc.getAuxSet());
        return out;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
