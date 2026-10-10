package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpOperationDao;
import com.erp.entity.mrp.MrpOperation;
import com.erp.service.mrp.OperationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工序字典实现（change add-routing-management，spec routing-management「工序字典维护」）。
 * 编码创建后不可改（update 比对 422）；无删除接口（禁止硬删，仅停用/逻辑删除）。
 */
@Slf4j
@Service
public class OperationServiceImpl implements OperationService {

    private final MrpOperationDao operationDao;
    private final ObjectMapper objectMapper;

    public OperationServiceImpl(MrpOperationDao operationDao, ObjectMapper objectMapper) {
        this.operationDao = operationDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> query(String keyword, String status) {
        LambdaQueryWrapper<MrpOperation> w = new LambdaQueryWrapper<>();
        w.eq(isNotBlank(status), MrpOperation::getStatus, status);
        if (isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            w.and(q -> q.like(MrpOperation::getOpCode, like)
                    .or().like(MrpOperation::getOpName, like));
        }
        w.orderByAsc(MrpOperation::getOpCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpOperation op : operationDao.selectList(w)) {
            out.add(toMap(op));
        }
        return out;
    }

    @Override
    @Transactional
    public MrpOperation create(MrpOperation in) {
        requireAny("新增工序");
        if (in == null || !isNotBlank(in.getOpCode()) || !isNotBlank(in.getOpName())) {
            throw new ServiceException(422, "工序编码与名称必填");
        }
        String code = in.getOpCode().trim();
        if (operationDao.selectCount(new LambdaQueryWrapper<MrpOperation>()
                .eq(MrpOperation::getOpCode, code)) > 0) {
            throw new ServiceException(422, "工序编码已存在：" + code);
        }
        MrpOperation op = new MrpOperation();
        op.setOpCode(code);
        op.setOpName(in.getOpName().trim());
        op.setSkillReq(in.getSkillReq());
        op.setStatus(isNotBlank(in.getStatus()) ? in.getStatus() : MrpOperation.ST_ACTIVE);
        operationDao.insert(op);
        log.info("工序创建：{} {}", op.getOpCode(), op.getOpName());
        return op;
    }

    @Override
    @Transactional
    public MrpOperation update(MrpOperation in) {
        requireAny("修改工序");
        if (in == null || !isNotBlank(in.getId())) {
            throw new ServiceException(422, "工序 ID 必填");
        }
        MrpOperation op = operationDao.selectById(in.getId());
        if (op == null) {
            throw new ServiceException(404, "工序不存在");
        }
        if (isNotBlank(in.getOpCode()) && !in.getOpCode().trim().equals(op.getOpCode())) {
            throw new ServiceException(422, "工序编码创建后不可修改（编码：" + op.getOpCode() + "）");
        }
        if (!isNotBlank(in.getOpName())) {
            throw new ServiceException(422, "工序名称必填");
        }
        op.setOpName(in.getOpName().trim());
        op.setSkillReq(in.getSkillReq());
        if (isNotBlank(in.getStatus())) {
            op.setStatus(in.getStatus());
        }
        operationDao.updateById(op);
        return operationDao.selectById(op.getId());
    }

    @Override
    @Transactional
    public MrpOperation setStatus(String id, String status) {
        requireAny("切换工序状态");
        if (!MrpOperation.ST_ACTIVE.equals(status) && !MrpOperation.ST_INACTIVE.equals(status)) {
            throw new ServiceException(422, "状态取值非法（1 启用 / 0 停用）");
        }
        MrpOperation op = operationDao.selectById(id);
        if (op == null) {
            throw new ServiceException(404, "工序不存在");
        }
        op.setStatus(status);
        operationDao.updateById(op);
        log.info("工序状态切换：{} → {}", op.getOpCode(), status);
        return operationDao.selectById(id);
    }

    // ---------- 私有辅助（freeze requireAny 同范式） ----------

    private void requireAny(String action) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getName() == null) {
            throw new ServiceException(401, action + "需要登录");
        }
        for (String r : new String[]{"ROLE_PROCESS_ENG", "ROLE_PROCESS_MGR", "ROLE_ADMIN"}) {
            if (auth.getAuthorities().contains(new SimpleGrantedAuthority(r))) {
                return;
            }
        }
        throw new ServiceException(403, "无权限：" + action);
    }

    private Map<String, Object> toMap(Object bean) {
        return objectMapper.convertValue(bean, new TypeReference<Map<String, Object>>() {
        });
    }

    private static boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
