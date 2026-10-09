package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmItemDictDao;
import com.erp.entity.mdm.MdmItemDict;
import com.erp.service.inv.InvDictService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 仓库域字典维护（4.1.3，spec warehouse-attribute-config，design D5）：
 * DICT_TYPE 白名单防跨域误插；复用 erp_mdm_item_dict 表（53 号迁移 CUSTOMER_CHANNEL 先例）；
 * 写权限 ROLE_WAREHOUSE，独立于 MDM 字典写入面。
 */
@Slf4j
@Service
public class InvDictServiceImpl implements InvDictService {

    /** 本菜单可维护的五类字典（design D5，白名单） */
    static final Set<String> ALLOWED_TYPES = Set.of(
            "WAREHOUSE_TYPE", "BIN_TYPE", "TEMP_LEVEL", "HAZARD_LEVEL", "CLEAN_LEVEL");

    private static final String ST_ENABLED = "1";
    private static final String ST_DISABLED = "0";

    private final MdmItemDictDao dictDao;

    public InvDictServiceImpl(MdmItemDictDao dictDao) {
        this.dictDao = dictDao;
    }

    @Override
    public List<MdmItemDict> list(String dictType, String status) {
        requireAllowedType(dictType);
        LambdaQueryWrapper<MdmItemDict> qw = new LambdaQueryWrapper<MdmItemDict>()
                .eq(MdmItemDict::getDictType, dictType)
                .eq(status != null && !status.isEmpty(), MdmItemDict::getStatus, status)
                .orderByAsc(MdmItemDict::getSortOrder)
                .orderByAsc(MdmItemDict::getDictCode);
        return dictDao.selectList(qw);
    }

    @Override
    public List<MdmItemDict> listActive(String dictType) {
        requireAllowedType(dictType);
        return dictDao.selectByType(dictType);
    }

    @Override
    @Transactional
    public MdmItemDict create(MdmItemDict item) {
        requireRole("维护仓库属性字典", "ROLE_WAREHOUSE");
        if (item == null || isBlank(item.getDictType()) || isBlank(item.getDictCode())
                || isBlank(item.getDictName())) {
            throw new ServiceException(422, "字典类型、编码与名称必填");
        }
        requireAllowedType(item.getDictType());
        Long exists = dictDao.selectCount(new LambdaQueryWrapper<MdmItemDict>()
                .eq(MdmItemDict::getDictType, item.getDictType())
                .eq(MdmItemDict::getDictCode, item.getDictCode().trim()));
        if (exists != null && exists > 0) {
            throw new ServiceException(409, "字典编码在该类型内已存在：" + item.getDictCode());
        }
        MdmItemDict entity = new MdmItemDict();
        entity.setDictType(item.getDictType());
        entity.setDictCode(item.getDictCode().trim());
        entity.setDictName(item.getDictName().trim());
        entity.setSortOrder(item.getSortOrder() == null ? 0 : item.getSortOrder());
        entity.setStatus(ST_ENABLED);
        try {
            dictDao.insert(entity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ServiceException(409, "字典编码在该类型内已存在：" + entity.getDictCode());
        }
        return entity;
    }

    @Override
    @Transactional
    public MdmItemDict update(MdmItemDict item) {
        requireRole("维护仓库属性字典", "ROLE_WAREHOUSE");
        if (item == null || isBlank(item.getId())) {
            throw new ServiceException(422, "字典条目 ID 必填");
        }
        MdmItemDict stored = dictDao.selectById(item.getId());
        if (stored == null) {
            throw new ServiceException(404, "字典条目不存在：" + item.getId());
        }
        requireAllowedType(stored.getDictType());
        // 编码创建后不可改（spec「字典条目维护」scenario）
        if (!isBlank(item.getDictCode()) && !stored.getDictCode().equals(item.getDictCode())) {
            throw new ServiceException(422, "字典编码创建后不可修改：" + stored.getDictCode());
        }
        if (isBlank(item.getDictName())) {
            throw new ServiceException(422, "字典名称必填");
        }
        stored.setDictName(item.getDictName().trim());
        if (item.getSortOrder() != null) {
            stored.setSortOrder(item.getSortOrder());
        }
        dictDao.updateById(stored);
        return stored;
    }

    @Override
    @Transactional
    public void enable(String id) {
        requireRole("维护仓库属性字典", "ROLE_WAREHOUSE");
        MdmItemDict stored = requireEntry(id);
        if (ST_ENABLED.equals(stored.getStatus())) {
            return;
        }
        stored.setStatus(ST_ENABLED);
        dictDao.updateById(stored);
    }

    @Override
    @Transactional
    public void disable(String id) {
        requireRole("维护仓库属性字典", "ROLE_WAREHOUSE");
        MdmItemDict stored = requireEntry(id);
        if (ST_DISABLED.equals(stored.getStatus())) {
            return;
        }
        // 停用即退出新数据可选范围；既有引用（区域/仓位上的 code）不受影响（spec scenario）
        stored.setStatus(ST_DISABLED);
        dictDao.updateById(stored);
    }

    private MdmItemDict requireEntry(String id) {
        MdmItemDict stored = dictDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(404, "字典条目不存在：" + id);
        }
        requireAllowedType(stored.getDictType());
        return stored;
    }

    private void requireAllowedType(String dictType) {
        if (!ALLOWED_TYPES.contains(dictType)) {
            throw new ServiceException(422, "非仓库域字典类型，禁止操作：" + dictType);
        }
    }

    private void requireRole(String action, String... allowed) {
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

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
