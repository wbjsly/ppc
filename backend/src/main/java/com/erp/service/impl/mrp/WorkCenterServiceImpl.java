package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpWorkCenterDao;
import com.erp.entity.mrp.MrpWorkCenter;
import com.erp.service.mrp.WorkCenterService;
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
import java.util.List;
import java.util.Map;

/**
 * 工作中心台账实现（change add-routing-management，spec routing-management「工作中心台账」）。
 * 外协缺供应商 L1（C-4.5-05 数据源头）；日历工时 > 0；设备/人员可用率 0~100；编码建后不可改。
 */
@Slf4j
@Service
public class WorkCenterServiceImpl implements WorkCenterService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final MrpWorkCenterDao workCenterDao;
    private final ObjectMapper objectMapper;

    public WorkCenterServiceImpl(MrpWorkCenterDao workCenterDao, ObjectMapper objectMapper) {
        this.workCenterDao = workCenterDao;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<Map<String, Object>> query(String keyword, String status) {
        LambdaQueryWrapper<MrpWorkCenter> w = new LambdaQueryWrapper<>();
        w.eq(isNotBlank(status), MrpWorkCenter::getStatus, status);
        if (isNotBlank(keyword)) {
            String like = "%" + keyword.trim() + "%";
            w.and(q -> q.like(MrpWorkCenter::getWcCode, like)
                    .or().like(MrpWorkCenter::getWcName, like));
        }
        w.orderByAsc(MrpWorkCenter::getWcCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (MrpWorkCenter wc : workCenterDao.selectList(w)) {
            out.add(toMap(wc));
        }
        return out;
    }

    @Override
    @Transactional
    public MrpWorkCenter create(MrpWorkCenter in) {
        requireAny("新增工作中心");
        if (in == null || !isNotBlank(in.getWcCode()) || !isNotBlank(in.getWcName())) {
            throw new ServiceException(422, "工作中心编码与名称必填");
        }
        String code = in.getWcCode().trim();
        if (workCenterDao.selectCount(new LambdaQueryWrapper<MrpWorkCenter>()
                .eq(MrpWorkCenter::getWcCode, code)) > 0) {
            throw new ServiceException(422, "工作中心编码已存在：" + code);
        }
        MrpWorkCenter wc = normalize(in);
        wc.setWcCode(code);
        validate(wc);
        workCenterDao.insert(wc);
        log.info("工作中心创建：{} {}（{}）", wc.getWcCode(), wc.getWcName(), wc.getWcType());
        return wc;
    }

    @Override
    @Transactional
    public MrpWorkCenter update(MrpWorkCenter in) {
        requireAny("修改工作中心");
        if (in == null || !isNotBlank(in.getId())) {
            throw new ServiceException(422, "工作中心 ID 必填");
        }
        MrpWorkCenter wc = workCenterDao.selectById(in.getId());
        if (wc == null) {
            throw new ServiceException(404, "工作中心不存在");
        }
        if (isNotBlank(in.getWcCode()) && !in.getWcCode().trim().equals(wc.getWcCode())) {
            throw new ServiceException(422, "工作中心编码创建后不可修改（编码：" + wc.getWcCode() + "）");
        }
        MrpWorkCenter merged = normalize(in);
        merged.setId(wc.getId());
        merged.setWcCode(wc.getWcCode());
        if (!isNotBlank(merged.getWcName())) {
            merged.setWcName(wc.getWcName());
        }
        if (!isNotBlank(in.getStatus())) {
            merged.setStatus(wc.getStatus());
        }
        validate(merged);
        workCenterDao.updateById(merged);
        return workCenterDao.selectById(wc.getId());
    }

    @Override
    @Transactional
    public MrpWorkCenter setStatus(String id, String status) {
        requireAny("切换工作中心状态");
        if (!MrpWorkCenter.ST_ACTIVE.equals(status) && !MrpWorkCenter.ST_INACTIVE.equals(status)) {
            throw new ServiceException(422, "状态取值非法（1 启用 / 0 停用）");
        }
        MrpWorkCenter wc = workCenterDao.selectById(id);
        if (wc == null) {
            throw new ServiceException(404, "工作中心不存在");
        }
        wc.setStatus(status);
        workCenterDao.updateById(wc);
        log.info("工作中心状态切换：{} → {}", wc.getWcCode(), status);
        return workCenterDao.selectById(id);
    }

    // ---------- 私有辅助 ----------

    /** 入参归一：类型默认 INTERNAL；产能缺省取 100 / 日历工时缺省 8（与表默认一致） */
    private MrpWorkCenter normalize(MrpWorkCenter in) {
        MrpWorkCenter wc = new MrpWorkCenter();
        wc.setWcName(in.getWcName() == null ? null : in.getWcName().trim());
        wc.setWcType(isNotBlank(in.getWcType()) ? in.getWcType().trim() : MrpWorkCenter.TYPE_INTERNAL);
        wc.setSupplierCode(in.getSupplierCode());
        wc.setCalHours(in.getCalHours() == null ? new BigDecimal("8") : in.getCalHours());
        wc.setEquipAvail(in.getEquipAvail() == null ? HUNDRED : in.getEquipAvail());
        wc.setLaborAvail(in.getLaborAvail() == null ? HUNDRED : in.getLaborAvail());
        wc.setStatus(isNotBlank(in.getStatus()) ? in.getStatus() : MrpWorkCenter.ST_ACTIVE);
        return wc;
    }

    /** 业务校验：类型/供应商 L1、产能三要素合法性（spec「工作中心台账」） */
    private void validate(MrpWorkCenter wc) {
        if (!isNotBlank(wc.getWcName())) {
            throw new ServiceException(422, "工作中心名称必填");
        }
        if (!MrpWorkCenter.TYPE_INTERNAL.equals(wc.getWcType())
                && !MrpWorkCenter.TYPE_OUTSOURCED.equals(wc.getWcType())) {
            throw new ServiceException(422, "工作中心类型取值非法（INTERNAL/OUTSOURCED）");
        }
        if (MrpWorkCenter.TYPE_OUTSOURCED.equals(wc.getWcType()) && !isNotBlank(wc.getSupplierCode())) {
            throw new ServiceException(422, "外协工作中心必须维护外协供应商信息（C-4.5-05）");
        }
        if (wc.getCalHours() == null || wc.getCalHours().signum() <= 0) {
            throw new ServiceException(422, "日历工时必须大于 0");
        }
        requirePercent(wc.getEquipAvail(), "设备可用率");
        requirePercent(wc.getLaborAvail(), "人员出勤率");
    }

    private void requirePercent(BigDecimal v, String label) {
        if (v == null || v.signum() < 0 || v.compareTo(HUNDRED) > 0) {
            throw new ServiceException(422, label + "须在 0~100 区间");
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
