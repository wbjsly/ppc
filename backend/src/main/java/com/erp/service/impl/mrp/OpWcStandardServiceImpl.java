package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpOpWcStandardDao;
import com.erp.dao.mrp.MrpRoutingDao;
import com.erp.dao.mrp.MrpRoutingOpDao;
import com.erp.entity.mrp.MrpOpWcStandard;
import com.erp.entity.mrp.MrpRouting;
import com.erp.entity.mrp.MrpRoutingOp;
import com.erp.service.mrp.OpWcStandardService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工序×工作中心工时定额实现（change add-routing-management，spec routing-management
 * 「定额矩阵表达工序与工作中心适配」）。
 * 组合唯一（重复 422，双保险 DB UK_MRP_STD_OPWC）；四类工时 ≥ 0；
 * 删除保护：被 PUBLISHED 路线行引用 → 422（草稿引用由路线保存卡控兜底，design D3）。
 */
@Slf4j
@Service
public class OpWcStandardServiceImpl implements OpWcStandardService {

    private final MrpOpWcStandardDao standardDao;
    private final MrpRoutingDao routingDao;
    private final MrpRoutingOpDao routingOpDao;
    private final ObjectMapper objectMapper;

    public OpWcStandardServiceImpl(MrpOpWcStandardDao standardDao, MrpRoutingDao routingDao,
                                   MrpRoutingOpDao routingOpDao, ObjectMapper objectMapper) {
        this.standardDao = standardDao;
        this.routingDao = routingDao;
        this.routingOpDao = routingOpDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> query(String opCode, String wcCode) {
        // 注意：LambdaQueryWrapper#eq 的参数在条件判断前求值——null 先归一，避免 NPE
        String op = isNotBlank(opCode) ? opCode.trim() : null;
        String wc = isNotBlank(wcCode) ? wcCode.trim() : null;
        LambdaQueryWrapper<MrpOpWcStandard> w = new LambdaQueryWrapper<>();
        w.eq(op != null, MrpOpWcStandard::getOpCode, op);
        w.eq(wc != null, MrpOpWcStandard::getWcCode, wc);
        w.orderByAsc(MrpOpWcStandard::getOpCode).orderByAsc(MrpOpWcStandard::getWcCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpOpWcStandard s : standardDao.selectList(w)) {
            out.add(toMap(s));
        }
        return out;
    }

    @Override
    @Transactional
    public MrpOpWcStandard create(MrpOpWcStandard in) {
        requireAny("新增工时定额");
        if (in == null || !isNotBlank(in.getOpCode()) || !isNotBlank(in.getWcCode())) {
            throw new ServiceException(422, "工序编码与工作中心编码必填");
        }
        String opCode = in.getOpCode().trim();
        String wcCode = in.getWcCode().trim();
        if (standardDao.selectCount(new LambdaQueryWrapper<MrpOpWcStandard>()
                .eq(MrpOpWcStandard::getOpCode, opCode)
                .eq(MrpOpWcStandard::getWcCode, wcCode)) > 0) {
            throw new ServiceException(422, "（" + opCode + " × " + wcCode + "）的工时定额已存在，请直接编辑");
        }
        MrpOpWcStandard row = new MrpOpWcStandard();
        row.setOpCode(opCode);
        row.setWcCode(wcCode);
        applyHours(row, in);
        standardDao.insert(row);
        log.info("工时定额创建：{} × {}", opCode, wcCode);
        return row;
    }

    @Override
    @Transactional
    public MrpOpWcStandard update(MrpOpWcStandard in) {
        requireAny("修改工时定额");
        if (in == null || !isNotBlank(in.getId())) {
            throw new ServiceException(422, "定额 ID 必填");
        }
        MrpOpWcStandard row = standardDao.selectById(in.getId());
        if (row == null) {
            throw new ServiceException(404, "工时定额不存在");
        }
        if (isNotBlank(in.getOpCode()) && !in.getOpCode().trim().equals(row.getOpCode())
                || isNotBlank(in.getWcCode()) && !in.getWcCode().trim().equals(row.getWcCode())) {
            throw new ServiceException(422, "工序/工作中心编码不可修改（如需调整组合请删除后重建）");
        }
        applyHours(row, in);
        standardDao.updateById(row);
        return standardDao.selectById(row.getId());
    }

    @Override
    @Transactional
    public void delete(String id) {
        requireAny("删除工时定额");
        MrpOpWcStandard row = standardDao.selectById(id);
        if (row == null) {
            throw new ServiceException(404, "工时定额不存在");
        }
        // 引用保护：被已发布路线行引用 → 422（spec「定额矩阵表达工序与工作中心适配」）
        List<MrpRoutingOp> refs = routingOpDao.selectList(new LambdaQueryWrapper<MrpRoutingOp>()
                .eq(MrpRoutingOp::getOpCode, row.getOpCode())
                .eq(MrpRoutingOp::getWcCode, row.getWcCode()));
        if (!refs.isEmpty()) {
            Set<String> routingIds = new HashSet<>();
            for (MrpRoutingOp ref : refs) {
                routingIds.add(ref.getRoutingId());
            }
            List<MrpRouting> published = routingDao.selectList(new LambdaQueryWrapper<MrpRouting>()
                    .in(MrpRouting::getId, routingIds)
                    .eq(MrpRouting::getStatus, MrpRouting.ST_PUBLISHED));
            if (!published.isEmpty()) {
                throw new ServiceException(422,
                        "该工时定额被已发布路线引用（" + published.get(0).getItemCode()
                                + " V" + published.get(0).getVersionLabel() + "），请先变更相关路线");
            }
        }
        standardDao.deleteById(row.getId());
        log.info("工时定额删除：{} × {}", row.getOpCode(), row.getWcCode());
    }

    // ---------- 私有辅助 ----------

    private void applyHours(MrpOpWcStandard row, MrpOpWcStandard in) {
        BigDecimal setup = in.getSetupHours() == null ? BigDecimal.ZERO : in.getSetupHours();
        BigDecimal run = in.getRunHours() == null ? BigDecimal.ZERO : in.getRunHours();
        BigDecimal wait = in.getWaitHours() == null ? BigDecimal.ZERO : in.getWaitHours();
        BigDecimal move = in.getMoveHours() == null ? BigDecimal.ZERO : in.getMoveHours();
        requireNonNegative(setup, "准备工时");
        requireNonNegative(run, "标准工时");
        requireNonNegative(wait, "等待工时");
        requireNonNegative(move, "移动工时");
        row.setSetupHours(setup);
        row.setRunHours(run);
        row.setWaitHours(wait);
        row.setMoveHours(move);
    }

    private void requireNonNegative(BigDecimal v, String label) {
        if (v.signum() < 0) {
            throw new ServiceException(422, label + "不得为负数");
        }
    }

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
