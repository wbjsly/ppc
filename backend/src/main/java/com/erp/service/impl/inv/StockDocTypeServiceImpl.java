package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.InvDocTypeDao;
import com.erp.entity.inv.InvDocType;
import com.erp.service.inv.StockDocTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 出入库业务类型配置实现（spec stock-doc-type）。
 * 写操作限 ROLE_ADMIN；TYPE_CODE 唯一且创建后锁定；DIRECTION/DEFAULT_ALLOC 枚举 422。
 */
@Slf4j
@Service
public class StockDocTypeServiceImpl implements StockDocTypeService {

    private final InvDocTypeDao docTypeDao;

    public StockDocTypeServiceImpl(InvDocTypeDao docTypeDao) {
        this.docTypeDao = docTypeDao;
    }

    @Override
    public List<InvDocType> list() {
        return docTypeDao.selectList(new LambdaQueryWrapper<InvDocType>()
                .orderByAsc(InvDocType::getDirection).orderByAsc(InvDocType::getTypeCode));
    }

    @Override
    public InvDocType getByCode(String typeCode) {
        if (isBlank(typeCode)) {
            return null;
        }
        return docTypeDao.selectByCode(typeCode);
    }

    @Override
    @Transactional
    public InvDocType create(InvDocType req) {
        requireAdmin("新增出入库类型");
        if (req == null || isBlank(req.getTypeCode())) {
            throw new ServiceException(422, "类型码必填");
        }
        String code = req.getTypeCode().trim().toUpperCase();
        if (!code.matches("[A-Z][A-Z0-9_]{1,31}")) {
            throw new ServiceException(422, "类型码仅支持大写字母/数字/下划线（2~32位）");
        }
        validate(req);
        if (docTypeDao.selectByCode(code) != null) {
            throw new ServiceException(409, "类型码已存在：" + code);
        }
        InvDocType t = new InvDocType();
        t.setTypeCode(code);
        t.setDirection(req.getDirection());
        t.setTypeName(req.getTypeName().trim());
        t.setFlowPrefix(isBlank(req.getFlowPrefix()) ? "TX" : req.getFlowPrefix().trim());
        t.setDefaultAlloc(req.getDefaultAlloc());
        t.setNeedBatch(req.getNeedBatch() == null ? 1 : req.getNeedBatch());
        t.setNeedSerial(req.getNeedSerial() == null ? 0 : req.getNeedSerial());
        t.setEnabled(1);
        t.setRemark(req.getRemark());
        docTypeDao.insert(t);
        log.info("doc type created: {} {}", code, req.getDirection());
        return t;
    }

    @Override
    @Transactional
    public InvDocType update(InvDocType req) {
        requireAdmin("修改出入库类型");
        if (req == null || isBlank(req.getId())) {
            throw new ServiceException(422, "类型 ID 必填");
        }
        InvDocType stored = docTypeDao.selectById(req.getId());
        if (stored == null) {
            throw new ServiceException(422, "类型不存在");
        }
        if (req.getTypeCode() != null && !stored.getTypeCode().equals(req.getTypeCode().trim())) {
            throw new ServiceException(422, "类型码不可修改");   // TYPE_CODE 创建后锁定
        }
        validate(req);
        stored.setTypeName(isBlank(req.getTypeName()) ? stored.getTypeName() : req.getTypeName().trim());
        stored.setDirection(req.getDirection() == null ? stored.getDirection() : req.getDirection());
        stored.setFlowPrefix(isBlank(req.getFlowPrefix()) ? stored.getFlowPrefix() : req.getFlowPrefix().trim());
        stored.setDefaultAlloc(req.getDefaultAlloc() == null ? stored.getDefaultAlloc() : req.getDefaultAlloc());
        if (req.getNeedBatch() != null) {
            stored.setNeedBatch(req.getNeedBatch());
        }
        if (req.getNeedSerial() != null) {
            stored.setNeedSerial(req.getNeedSerial());
        }
        stored.setRemark(req.getRemark() == null ? stored.getRemark() : req.getRemark());
        if (docTypeDao.updateById(stored) == 0) {
            throw new ServiceException(409, "类型状态更新冲突");
        }
        return stored;
    }

    @Override
    @Transactional
    public InvDocType setEnabled(String id, boolean enabled) {
        requireAdmin(enabled ? "启用出入库类型" : "停用出入库类型");
        InvDocType stored = docTypeDao.selectById(id);
        if (stored == null) {
            throw new ServiceException(422, "类型不存在");
        }
        stored.setEnabled(enabled ? 1 : 0);
        if (docTypeDao.updateById(stored) == 0) {
            throw new ServiceException(409, "类型状态更新冲突");
        }
        log.info("doc type {} {}", stored.getTypeCode(), enabled ? "enabled" : "disabled");
        return stored;
    }

    /** 字段/取值域校验（spec 场景：字段缺失或取值域外 422） */
    private void validate(InvDocType req) {
        if (!InvDocType.DIR_IN.equals(req.getDirection())
                && !InvDocType.DIR_OUT.equals(req.getDirection())) {
            throw new ServiceException(422, "方向仅支持 IN/OUT");
        }
        if (isBlank(req.getTypeName())) {
            throw new ServiceException(422, "类型名称必填");
        }
        if (!InvDocType.ALLOC_AUTO.equals(req.getDefaultAlloc())
                && !InvDocType.ALLOC_MANUAL.equals(req.getDefaultAlloc())) {
            throw new ServiceException(422, "默认分配方式仅支持 AUTO_FIFO/MANUAL");
        }
    }

    private void requireAdmin(String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        if (!auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"))) {
            throw new ServiceException(403, "无权限：" + action + "（仅系统管理员）");
        }
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
