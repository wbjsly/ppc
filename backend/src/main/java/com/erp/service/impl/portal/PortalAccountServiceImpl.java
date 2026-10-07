package com.erp.service.impl.portal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmSupplierDao;
import com.erp.dao.system.SysUserDao;
import com.erp.dao.system.SysUserRoleDao;
import com.erp.entity.mdm.MdmSupplier;
import com.erp.entity.system.SysUser;
import com.erp.entity.system.SysUserRole;
import com.erp.service.portal.PortalAccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 门户账号实现（spec supplier-portal-account，design D1：复用 admin_user + SUPPLIER_ID 列）。
 */
@Slf4j
@Service
public class PortalAccountServiceImpl implements PortalAccountService {

    /** 种子角色 ID（049 迁移），账号创建时挂接 */
    static final String ROLE_SUPPLIER_ID = "role-supplier";
    /** 默认初始密码与种子一致（线下交付，Open Question 已记 design） */
    private static final String DEFAULT_PASSWORD_HASH =
            "$2a$10$KvoqroXik9qiqRA8MLTZTe4XrU1QurZ3dLZVEg9CJNfImhr1AGD3W";

    private final SysUserDao userDao;
    private final SysUserRoleDao userRoleDao;
    private final MdmSupplierDao supplierDao;
    private final PasswordEncoder passwordEncoder;

    public PortalAccountServiceImpl(SysUserDao userDao,
                                    SysUserRoleDao userRoleDao,
                                    MdmSupplierDao supplierDao,
                                    PasswordEncoder passwordEncoder) {
        this.userDao = userDao;
        this.userRoleDao = userRoleDao;
        this.supplierDao = supplierDao;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public Map<String, Object> create(Map<String, Object> payload) {
        String supplierId = str(payload.get("supplierId"));
        String username = str(payload.get("username"));
        if (!StringUtils.hasText(supplierId)) {
            throw new ServiceException(422, "supplierId 必填");
        }
        if (!StringUtils.hasText(username)) {
            throw new ServiceException(422, "username 必填");
        }
        MdmSupplier sup = supplierDao.selectById(supplierId.trim());
        if (sup == null) {
            throw new ServiceException(422, "供应商不存在：" + supplierId);
        }
        // spec：为「合格」供应商开通（BR-4.2-18 同口径）
        if (!"QUALIFIED".equals(sup.getStatus())) {
            throw new ServiceException(422, "供应商状态 " + sup.getStatus() + " 不可开通门户账号");
        }
        Long dup = userDao.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, username.trim()));
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "用户名已存在：" + username);
        }

        SysUser u = new SysUser();
        u.setUsername(username.trim());
        String raw = str(payload.get("password"));
        u.setPassword(StringUtils.hasText(raw) ? passwordEncoder.encode(raw) : DEFAULT_PASSWORD_HASH);
        u.setNickName(str(payload.get("nickName")) != null ? str(payload.get("nickName"))
                : sup.getSupplierName() + "门户");
        u.setStatus("1");
        u.setSupplierId(supplierId.trim());
        try {
            userDao.insert(u);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "用户名并发冲突，请重试");
        }

        SysUserRole ur = new SysUserRole();
        ur.setId("ur-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        ur.setUserId(u.getId());
        ur.setRoleId(ROLE_SUPPLIER_ID);
        userRoleDao.insert(ur);

        log.info("门户账号创建 username={} supplier={} ", u.getUsername(), supplierId);
        return row(u, sup.getSupplierName());
    }

    @Override
    @Transactional
    public Map<String, Object> disable(String id) {
        SysUser u = require(id);
        u.setStatus("0");
        userDao.updateById(u);
        log.info("门户账号停用 id={} username={}", id, u.getUsername());
        return row(u, supplierName(u.getSupplierId()));
    }

    @Override
    @Transactional
    public Map<String, Object> enable(String id) {
        SysUser u = require(id);
        u.setStatus("1");
        userDao.updateById(u);
        return row(u, supplierName(u.getSupplierId()));
    }

    @Override
    public List<Map<String, Object>> list(String supplierId) {
        LambdaQueryWrapper<SysUser> qw = new LambdaQueryWrapper<SysUser>()
                .isNotNull(SysUser::getSupplierId)
                .orderByDesc(SysUser::getCreateDate);
        if (StringUtils.hasText(supplierId)) {
            qw.eq(SysUser::getSupplierId, supplierId.trim());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (SysUser u : userDao.selectList(qw)) {
            out.add(row(u, supplierName(u.getSupplierId())));
        }
        return out;
    }

    private SysUser require(String id) {
        SysUser u = userDao.selectById(id);
        if (u == null || !StringUtils.hasText(u.getSupplierId())) {
            throw new ServiceException(404, "门户账号不存在");
        }
        return u;
    }

    private String supplierName(String supplierId) {
        if (!StringUtils.hasText(supplierId)) {
            return null;
        }
        MdmSupplier s = supplierDao.selectById(supplierId);
        return s == null ? null : s.getSupplierName();
    }

    private Map<String, Object> row(SysUser u, String supplierName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.getId());
        m.put("username", u.getUsername());
        m.put("nickName", u.getNickName());
        m.put("status", u.getStatus());
        m.put("supplierId", u.getSupplierId());
        m.put("supplierName", supplierName);
        return m;
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }
}
