package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.qms.InspectionLotDao;
import com.erp.dao.qms.SamplingRecordDao;
import com.erp.dao.system.SysUserDao;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.qms.SamplingRecord;
import com.erp.entity.system.SysUser;
import com.erp.service.qms.SamplingRecordService;
import com.erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 抽样执行记录实现（6.3.1，spec inspection-lot 抽样执行记录）。
 * 写入点唯一：插入记录后同事务更新 lot.SAMPLED_FLAG（design D1 冗余标记）。
 */
@Service
public class SamplingRecordServiceImpl implements SamplingRecordService {

    private static final Set<String> METHODS = Set.of("RANDOM", "NINE_POINT", "FULL", "OTHER");
    private static final Set<String> REASONS = Set.of("INITIAL", "ESCALATE", "RESAMPLE");

    private final SamplingRecordDao recordDao;
    private final InspectionLotDao lotDao;
    private final SysUserDao userDao;

    public SamplingRecordServiceImpl(SamplingRecordDao recordDao,
                                     InspectionLotDao lotDao,
                                     SysUserDao userDao) {
        this.recordDao = recordDao;
        this.lotDao = lotDao;
        this.userDao = userDao;
    }

    @Override
    public List<Map<String, Object>> listByLot(String lotId) {
        List<SamplingRecord> rows = recordDao.selectList(new LambdaQueryWrapper<SamplingRecord>()
                .eq(SamplingRecord::getLotId, lotId)
                .eq(SamplingRecord::getDelFlag, "0")
                .orderByAsc(SamplingRecord::getSeq));
        List<Map<String, Object>> out = new ArrayList<>();
        for (SamplingRecord r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("seq", r.getSeq());
            m.put("lotId", r.getLotId());
            m.put("actualSampleQty", r.getActualSampleQty());
            m.put("sampleMethod", r.getSampleMethod());
            m.put("samplerId", r.getSamplerId());
            m.put("samplerName", r.getSamplerName());
            m.put("sampledTime", r.getSampledTime());
            m.put("triggerReason", r.getTriggerReason());
            m.put("remark", r.getRemark());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> create(String lotId, Map<String, Object> body) {
        requireRole("写抽样记录", "ROLE_INSPECTOR", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        InspectionLot lot = lotDao.selectById(lotId);
        if (lot == null) {
            throw new ServiceException(404, "检验批不存在");
        }
        String method = str(body.get("sampleMethod"));
        if (!METHODS.contains(method)) {
            throw new ServiceException(422, "抽样方法无效（RANDOM/NINE_POINT/FULL/OTHER）");
        }
        String reason = str(body.get("triggerReason"));
        if (reason == null || reason.isEmpty()) {
            reason = "INITIAL";
        }
        if (!REASONS.contains(reason)) {
            throw new ServiceException(422, "触发原因无效（INITIAL/ESCALATE/RESAMPLE）");
        }
        BigDecimal qty = dec(body.get("actualSampleQty"));
        if (qty == null || qty.signum() <= 0) {
            throw new ServiceException(422, "实际抽样数量必须大于 0");
        }

        // SEQ = 批内已有条数 + 1（同批多条并存不覆盖）
        Long prior = recordDao.selectCount(new LambdaQueryWrapper<SamplingRecord>()
                .eq(SamplingRecord::getLotId, lotId)
                .eq(SamplingRecord::getDelFlag, "0"));

        SamplingRecord r = new SamplingRecord();
        r.setLotId(lotId);
        r.setSeq((int) (prior == null ? 0L : prior) + 1);
        r.setActualSampleQty(qty);
        r.setSampleMethod(method);
        r.setTriggerReason(reason);
        r.setRemark(str(body.get("remark")));

        // 抽样人：body 可覆盖（双人核对场景），默认当前登录
        String samplerId = str(body.get("samplerId"));
        if (samplerId == null || samplerId.isEmpty()) {
            samplerId = SecurityUtils.getCurrentUserId();
        }
        r.setSamplerId(samplerId);
        String name = resolveName(samplerId, str(body.get("samplerName")));
        if (name == null || name.isEmpty()) {
            // 用户表查无此人（如内部调用）→ 回退登录名，避免空姓名
            var auth = org.springframework.security.core.context.SecurityContextHolder
                    .getContext().getAuthentication();
            name = auth != null ? auth.getName() : samplerId;
        }
        r.setSamplerName(name);

        LocalDateTime t = time(body.get("sampledTime"));
        r.setSampledTime(t == null ? LocalDateTime.now() : t);

        recordDao.insert(r);

        // 同事务更新冗余标记（写入点唯一）
        if (!"1".equals(lot.getSampledFlag())) {
            lot.setSampledFlag("1");
            lotDao.updateById(lot);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("seq", r.getSeq());
        out.put("lotId", lotId);
        out.put("actualSampleQty", qty);
        out.put("sampleMethod", method);
        out.put("samplerId", r.getSamplerId());
        out.put("samplerName", r.getSamplerName());
        out.put("sampledTime", r.getSampledTime());
        out.put("triggerReason", reason);
        out.put("remark", r.getRemark());
        return out;
    }

    /** 权限防线（纵深，SecurityConfig 已有 HTTP 规则；spec 401/403 场景可测） */
    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private String resolveName(String userId, String fallback) {
        if (userId != null) {
            SysUser u = userDao.selectById(userId);
            if (u != null && u.getNickName() != null && !u.getNickName().isEmpty()) {
                return u.getNickName();
            }
        }
        return fallback;
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static BigDecimal dec(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal d) return d;
        try {
            return new BigDecimal(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime time(Object v) {
        if (v == null) return null;
        try {
            return LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
        } catch (Exception e) {
            return null;
        }
    }
}
