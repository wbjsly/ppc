package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.ExpenseTypeDao;
import com.erp.dao.fin.FinAccountDao;
import com.erp.entity.fin.ExpenseType;
import com.erp.entity.fin.FinAccount;
import com.erp.service.fin.ExpenseTypeService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 费用类型映射实现（M8-8，spec expense-reimbursement）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpenseTypeServiceImpl implements ExpenseTypeService {

    private final ExpenseTypeDao typeDao;
    private final FinAccountDao accountDao;

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExpenseType t : typeDao.selectList(new LambdaQueryWrapper<ExpenseType>()
                .orderByAsc(ExpenseType::getTypeCode))) {
            out.add(row(t));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        requireFinance("维护费用类型");
        String typeCode = str(payload.get("typeCode")).trim();
        String typeName = str(payload.get("typeName")).trim();
        String accountCode = str(payload.get("accountCode")).trim();
        if (typeCode.isEmpty() || typeName.isEmpty() || accountCode.isEmpty()) {
            throw new ServiceException(422, "类型编码/名称/费用科目均必填");
        }
        FinAccount acct = accountDao.selectOne(new LambdaQueryWrapper<FinAccount>()
                .eq(FinAccount::getAccountCode, accountCode)
                .eq(FinAccount::getDelFlag, "0")
                .last("LIMIT 1"));
        if (acct == null) {
            throw new ServiceException(422, "费用科目不存在：" + accountCode);
        }
        if (!"DR".equals(acct.getDirection())) {
            throw new ServiceException(422, "费用科目须为借方（DR）方向：" + accountCode);
        }
        String id = str(payload.get("id"));
        // 类型编码唯一（改名时排除自身）
        ExpenseType existing = typeDao.selectOne(new LambdaQueryWrapper<ExpenseType>()
                .eq(ExpenseType::getTypeCode, typeCode)
                .last("LIMIT 1"));
        if (existing != null && !existing.getId().equals(id)) {
            throw new ServiceException(422, "费用类型编码已存在：" + typeCode);
        }
        ExpenseType t;
        if (existing != null && !id.isEmpty()) {
            t = existing;
        } else if (!id.isEmpty()) {
            t = typeDao.selectById(id);
            if (t == null) {
                t = new ExpenseType();
                t.setId(uuid());
                t.setCreateBy(currentUser());
                t.setCreateDate(LocalDateTime.now());
            }
        } else {
            t = new ExpenseType();
            t.setId(uuid());
            t.setStatus(ExpenseType.ST_ACTIVE);
            t.setCreateBy(currentUser());
            t.setCreateDate(LocalDateTime.now());
        }
        t.setTypeCode(typeCode);
        t.setTypeName(typeName);
        t.setAccountCode(accountCode);
        t.setRemark(str(payload.get("remark")));
        t.setUpdateBy(currentUser());
        t.setUpdateDate(LocalDateTime.now());
        if (typeDao.selectById(t.getId()) == null) {
            typeDao.insert(t);
        } else {
            typeDao.updateById(t);
        }
        log.info("expense type saved: {} -> {} by {}", typeCode, accountCode, currentUser());
        return row(t);
    }

    @Override
    @Transactional
    public void updateStatus(String id, String status) {
        requireFinance("停启用费用类型");
        if (!ExpenseType.ST_ACTIVE.equals(status) && !ExpenseType.ST_DISABLED.equals(status)) {
            throw new ServiceException(422, "状态仅支持 ACTIVE/DISABLED");
        }
        ExpenseType t = typeDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "费用类型不存在");
        }
        t.setStatus(status);
        t.setUpdateBy(currentUser());
        t.setUpdateDate(LocalDateTime.now());
        typeDao.updateById(t);
        log.info("expense type {} status -> {} by {}", t.getTypeCode(), status, currentUser());
    }

    @Override
    public String resolveAccountCode(String typeCode) {
        ExpenseType t = typeDao.selectOne(new LambdaQueryWrapper<ExpenseType>()
                .eq(ExpenseType::getTypeCode, typeCode)
                .last("LIMIT 1"));
        if (t == null || t.getDelFlag() != null && "1".equals(t.getDelFlag())) {
            throw new ServiceException(422, "费用类型映射缺失，请先补录映射：" + typeCode);
        }
        if (!ExpenseType.ST_ACTIVE.equals(t.getStatus())) {
            throw new ServiceException(422, "费用类型已停用，不可用于新报销：" + typeCode);
        }
        if (t.getAccountCode() == null || t.getAccountCode().isBlank()) {
            throw new ServiceException(422, "费用类型科目映射缺失，请先补录：" + typeCode);
        }
        return t.getAccountCode();
    }

    private Map<String, Object> row(ExpenseType t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("typeCode", t.getTypeCode());
        m.put("typeName", t.getTypeName());
        m.put("accountCode", t.getAccountCode());
        m.put("status", t.getStatus());
        m.put("remark", t.getRemark());
        return m;
    }

    private void requireFinance(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean ok = false;
        for (var a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equalsIgnoreCase(r) || "ROLE_FIN_MGR".equalsIgnoreCase(r)
                    || "ROLE_FIN_ASSET".equalsIgnoreCase(r)) {
                ok = true;
                break;
            }
        }
        if (!ok) {
            throw new ServiceException(403, "无权" + action);
        }
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String currentUser() {
        try {
            return SecurityUtils.getCurrentUserId();
        } catch (RuntimeException e) {
            return "system";
        }
    }
}
