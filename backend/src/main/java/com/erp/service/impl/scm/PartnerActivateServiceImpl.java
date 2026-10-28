package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.scm.PartnerActivateTokenDao;
import com.erp.dao.scm.PartnerApplyDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.scm.PartnerActivateToken;
import com.erp.entity.scm.PartnerApply;
import com.erp.entity.system.SysUser;
import com.erp.service.scm.PartnerActivateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 门户账号激活实现（add-partner-onboarding design D4）。
 * token 语义同 11.10 so_confirm_token：一次性/过期/重发旧链立即失效。
 * 激活即首登设置密码（无存量密码，无二段强制改密）。
 */
@Slf4j
@Service
public class PartnerActivateServiceImpl implements PartnerActivateService {

    /** 密码复杂度：≥8 位含小写+大写+数字+特殊字符（FR-4.9-1-4） */
    private static final Pattern COMPLEX = Pattern.compile(
            "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[^\\w\\s]).{8,}$");

    private final PartnerActivateTokenDao tokenDao;
    private final PartnerApplyDao applyDao;
    private final SysUserDao userDao;
    private final MdmSupplierDao supplierDao;
    private final PasswordEncoder passwordEncoder;

    public PartnerActivateServiceImpl(PartnerActivateTokenDao tokenDao,
                                      PartnerApplyDao applyDao,
                                      SysUserDao userDao,
                                      MdmSupplierDao supplierDao,
                                      PasswordEncoder passwordEncoder) {
        this.tokenDao = tokenDao;
        this.applyDao = applyDao;
        this.userDao = userDao;
        this.supplierDao = supplierDao;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public Map<String, Object> verify(String token) {
        PartnerActivateToken t = requireUsable(token);
        MdmSupplier sup = supplierDao.selectById(t.getSupplierId());
        Map<String, Object> out = new LinkedHashMap<>();
        // 脱敏回显：企业名保留首尾字，邮箱仅域
        String name = sup == null ? "贵公司" : sup.getSupplierName();
        out.put("supplierNameMasked", maskName(name));
        out.put("emailDomain", emailDomainOfApply(t.getSupplierId()));
        out.put("expireAt", t.getExpireAt());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> activate(String token, String password) {
        PartnerActivateToken t = requireUsable(token);
        if (!StringUtils.hasText(password) || !COMPLEX.matcher(password).matches()) {
            throw new ServiceException(422, "密码须 ≥8 位且含大小写字母、数字与特殊字符");
        }
        SysUser u = userDao.selectById(t.getAccountId());
        if (u == null) {
            throw new ServiceException(404, "账号不存在");
        }
        u.setPassword(passwordEncoder.encode(password));
        u.setStatus("1");
        userDao.updateById(u);

        t.setUsedAt(LocalDateTime.now());
        t.setUpdateDate(LocalDateTime.now());
        tokenDao.updateById(t);

        log.info("partner account activated account={} supplier={}", u.getUsername(),
                t.getSupplierId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("username", u.getUsername());
        out.put("activated", true);
        return out;
    }

    @Override
    public Map<String, Object> activateLink(String applyId) {
        requireProcurement("查看激活链接");
        PartnerApply a = requireApply(applyId);
        if (!StringUtils.hasText(a.getAccountId())) {
            throw new ServiceException(422, "该申请单尚未生成门户账号（未审核通过）");
        }
        PartnerActivateToken latest = latestToken(a.getAccountId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applyId", applyId);
        out.put("accountId", a.getAccountId());
        out.put("token", latest == null ? null : latest.getToken());
        out.put("expireAt", latest == null ? null : latest.getExpireAt());
        out.put("usedAt", latest == null ? null : latest.getUsedAt());
        out.put("invalidFlag", latest == null ? null : latest.getInvalidFlag());
        out.put("status", tokenStatus(latest));
        // 完整链接由前端拼接（匿名端点路径）
        out.put("activatePath", "/api/public/partner-activate/" +
                (latest == null ? "" : latest.getToken()));
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> regenerate(String applyId) {
        requireProcurement("重新生成激活链接");
        PartnerApply a = requireApply(applyId);
        if (!StringUtils.hasText(a.getAccountId())) {
            throw new ServiceException(422, "该申请单尚未生成门户账号");
        }
        // 旧链立即失效（BR-4.9-11）
        tokenDao.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<PartnerActivateToken>()
                .eq(PartnerActivateToken::getAccountId, a.getAccountId())
                .eq(PartnerActivateToken::getInvalidFlag, "0")
                .set(PartnerActivateToken::getInvalidFlag, "1")
                .set(PartnerActivateToken::getUpdateDate, LocalDateTime.now()));
        PartnerActivateToken t = newToken(a.getAccountId(), a.getSupplierId());
        tokenDao.insert(t);
        log.info("partner activate link regenerated apply={} account={}", applyId, a.getAccountId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", t.getToken());
        out.put("expireAt", t.getExpireAt());
        out.put("activatePath", "/api/public/partner-activate/" + t.getToken());
        out.put("status", "ACTIVE");
        return out;
    }

    @Override
    public int sweepExpired() {
        int affected = tokenDao.update(null, new com.baomidou.mybatisplus.core.conditions.update
                .LambdaUpdateWrapper<PartnerActivateToken>()
                .eq(PartnerActivateToken::getInvalidFlag, "0")
                .isNull(PartnerActivateToken::getUsedAt)
                .lt(PartnerActivateToken::getExpireAt, LocalDateTime.now())
                .set(PartnerActivateToken::getInvalidFlag, "1")
                .set(PartnerActivateToken::getUpdateDate, LocalDateTime.now()));
        if (affected > 0) {
            log.info("partner activate tokens expired swept: {}", affected);
        }
        return affected;
    }

    // ================================================================
    // 内部工具
    // ================================================================

    private PartnerActivateToken requireUsable(String token) {
        if (!StringUtils.hasText(token)) {
            throw new ServiceException(422, "token 必填");
        }
        PartnerActivateToken t = tokenDao.selectOne(new LambdaQueryWrapper<PartnerActivateToken>()
                .eq(PartnerActivateToken::getToken, token.trim())
                .eq(PartnerActivateToken::getDelFlag, "0")
                .last("LIMIT 1"));
        if (t == null) {
            throw new ServiceException(404, "链接无效，请联系管理员重新发送");
        }
        if ("1".equals(t.getInvalidFlag())) {
            throw new ServiceException(410, "链接已失效，请联系管理员重新发送");
        }
        if (t.getUsedAt() != null) {
            throw new ServiceException(410, "链接已使用（一次性），如需协助请联系管理员");
        }
        if (t.getExpireAt() == null || t.getExpireAt().isBefore(LocalDateTime.now())) {
            t.setInvalidFlag("1");
            tokenDao.updateById(t);
            throw new ServiceException(410, "链接已过期（7 天有效期），请联系管理员重新发送");
        }
        return t;
    }

    private PartnerActivateToken newToken(String accountId, String supplierId) {
        PartnerActivateToken t = new PartnerActivateToken();
        t.setId(UUID.randomUUID().toString().replace("-", ""));
        t.setAccountId(accountId);
        t.setSupplierId(supplierId);
        t.setToken(PartnerAdmissionCallback.randomToken());
        t.setExpireAt(LocalDateTime.now().plusDays(PartnerActivateToken.EXPIRE_DAYS));
        t.setInvalidFlag("0");
        t.setCreateBy(currentUser());
        t.setCreateDate(LocalDateTime.now());
        return t;
    }

    private PartnerActivateToken latestToken(String accountId) {
        return tokenDao.selectOne(new LambdaQueryWrapper<PartnerActivateToken>()
                .eq(PartnerActivateToken::getAccountId, accountId)
                .eq(PartnerActivateToken::getDelFlag, "0")
                .orderByDesc(PartnerActivateToken::getCreateDate)
                .last("LIMIT 1"));
    }

    private String tokenStatus(PartnerActivateToken t) {
        if (t == null) {
            return "NONE";
        }
        if ("1".equals(t.getInvalidFlag())) {
            return "INVALID";
        }
        if (t.getUsedAt() != null) {
            return "USED";
        }
        if (t.getExpireAt() != null && t.getExpireAt().isBefore(LocalDateTime.now())) {
            return "EXPIRED";
        }
        return "ACTIVE";
    }

    private String emailDomainOfApply(String supplierId) {
        // 申请单联系人邮箱域（激活链接送达邮箱）
        PartnerApply a = applyDao.selectOne(new LambdaQueryWrapper<PartnerApply>()
                .eq(PartnerApply::getSupplierId, supplierId)
                .eq(PartnerApply::getDelFlag, "0")
                .orderByDesc(PartnerApply::getCreateDate)
                .last("LIMIT 1"));
        if (a == null || !StringUtils.hasText(a.getContactEmail())
                || !a.getContactEmail().contains("@")) {
            return null;
        }
        return "@" + a.getContactEmail().substring(a.getContactEmail().indexOf('@') + 1);
    }

    private static String maskName(String name) {
        if (name == null || name.length() < 2) {
            return name;
        }
        return name.charAt(0) + "**" + name.charAt(name.length() - 1);
    }

    private PartnerApply requireApply(String applyId) {
        PartnerApply a = applyDao.selectById(applyId);
        if (a == null || "1".equals(a.getDelFlag())) {
            throw new ServiceException(404, "申请单不存在");
        }
        return a;
    }

    private void requireProcurement(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean ok = auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equalsIgnoreCase(g.getAuthority())
                        || "ROLE_PURCHASER".equalsIgnoreCase(g.getAuthority()));
        if (!ok) {
            throw new ServiceException(403, "无权限" + action + "（需 PURCHASER/ADMIN）");
        }
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }
}
