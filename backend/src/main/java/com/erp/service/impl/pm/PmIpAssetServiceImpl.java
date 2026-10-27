package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmIpAssetDao;
import com.erp.dao.pm.PmIpAssetStatusLogDao;
import com.erp.entity.pm.PmIpAsset;
import com.erp.entity.pm.PmIpAssetStatusLog;
import com.erp.entity.pm.PmAchievement;
import com.erp.service.pm.PmIpAssetService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * IP 资产建档实现（add-ip-management，design D5）。
 * 只增不改（BR-4.8-26）：档案行生成后不可修改权属快照；申请状态更新走独立入口 + 历史留痕。
 * 保护期：专利 +20 年 / 软著 +50 年 / 技术秘密与技术文档不设期限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmIpAssetServiceImpl implements PmIpAssetService {

    private final PmIpAssetDao assetDao;
    private final PmIpAssetStatusLogDao statusLogDao;

    @Override
    @Transactional
    public Map<String, Object> generateFromAchievement(PmAchievement achievement) {
        // UK achieve_id 幂等：已存在直接返回
        PmIpAsset exist = assetDao.selectOne(new LambdaQueryWrapper<PmIpAsset>()
                .eq(PmIpAsset::getAchieveId, achievement.getId())
                .last("LIMIT 1"));
        if (exist != null) {
            return toMap(exist);
        }
        String me = SecurityUtils.getCurrentUserId();
        PmIpAsset asset = new PmIpAsset();
        asset.setId(uuid());
        asset.setAchieveId(achievement.getId());
        asset.setAchieveNo(achievement.getAchieveNo());
        asset.setName(achievement.getName());
        asset.setType(achievement.getType());
        asset.setProjectId(achievement.getProjectId());
        asset.setOwnershipResult(achievement.getOwnershipResult());
        asset.setCompleteDate(achievement.getCompleteDate());
        asset.setProtectExpireDate(protectExpire(achievement.getType(), achievement.getCompleteDate()));
        asset.setApplyStatus(PmIpAsset.APPLY_NOT_APPLIED);
        asset.setCreateBy(me);
        asset.setCreateDate(LocalDateTime.now());
        asset.setUpdateBy(me);
        asset.setUpdateDate(LocalDateTime.now());
        assetDao.insert(asset);

        // 初始状态历史
        writeLog(asset.getId(), null, PmIpAsset.APPLY_NOT_APPLIED, me, "建档自动生成");
        return toMap(asset);
    }

    @Override
    @Transactional
    public Map<String, Object> applyStatus(String assetId, Map<String, Object> payload) {
        PmIpAsset asset = assetDao.selectById(assetId);
        if (asset == null || "1".equals(asset.getDelFlag())) {
            throw new ServiceException(404, "档案不存在");
        }
        requireRole("RD_LEAD", "LEGAL", "ADMIN");
        String to = str(payload.get("applyStatus"));
        if (!PmIpAsset.APPLY_NOT_APPLIED.equals(to) && !PmIpAsset.APPLY_SUBMITTED.equals(to)
                && !PmIpAsset.APPLY_GRANTED.equals(to) && !PmIpAsset.APPLY_REJECTED.equals(to)) {
            throw new ServiceException(422, "申请状态必须为未申请/已递交/已授权/已驳回四选一");
        }
        String me = SecurityUtils.getCurrentUserId();
        String from = asset.getApplyStatus();
        asset.setApplyStatus(to);
        asset.setApplyUpdatedAt(LocalDateTime.now());
        asset.setUpdateBy(me);
        asset.setUpdateDate(LocalDateTime.now());
        assetDao.updateById(asset);
        writeLog(assetId, from, to, me, str(payload.get("remark")));
        return toMap(asset);
    }

    @Override
    public Map<String, Object> list(Map<String, Object> query) {
        LambdaQueryWrapper<PmIpAsset> w = new LambdaQueryWrapper<PmIpAsset>()
                .eq(PmIpAsset::getDelFlag, "0")
                .orderByDesc(PmIpAsset::getCreateDate);
        String type = str(query.get("type"));
        String applyStatus = str(query.get("applyStatus"));
        String projectId = str(query.get("projectId"));
        String expireBefore = str(query.get("expireBefore"));
        String expireAfter = str(query.get("expireAfter"));
        if (type != null && !type.isBlank()) {
            w.eq(PmIpAsset::getType, type);
        }
        if (applyStatus != null && !applyStatus.isBlank()) {
            w.eq(PmIpAsset::getApplyStatus, applyStatus);
        }
        if (projectId != null && !projectId.isBlank()) {
            w.eq(PmIpAsset::getProjectId, projectId);
        }
        if (expireBefore != null && !expireBefore.isBlank()) {
            w.le(PmIpAsset::getProtectExpireDate, LocalDate.parse(expireBefore));
        }
        if (expireAfter != null && !expireAfter.isBlank()) {
            w.ge(PmIpAsset::getProtectExpireDate, LocalDate.parse(expireAfter));
        }
        List<PmIpAsset> rows = assetDao.selectList(w);
        List<Map<String, Object>> items = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (PmIpAsset a : rows) {
            Map<String, Object> m = toMap(a);
            if (a.getProtectExpireDate() != null) {
                m.put("expireDaysLeft", java.time.temporal.ChronoUnit.DAYS.between(today, a.getProtectExpireDate()));
            }
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", items.size());
        out.put("items", items);
        return out;
    }

    @Override
    public Object statusLog(String assetId) {
        List<PmIpAssetStatusLog> logs = statusLogDao.selectList(new LambdaQueryWrapper<PmIpAssetStatusLog>()
                .eq(PmIpAssetStatusLog::getAssetId, assetId)
                .eq(PmIpAssetStatusLog::getDelFlag, "0")
                .orderByAsc(PmIpAssetStatusLog::getOperateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmIpAssetStatusLog l : logs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fromStatus", l.getFromStatus());
            m.put("toStatus", l.getToStatus());
            m.put("operator", l.getOperator());
            m.put("operateDate", l.getOperateDate() == null ? null : l.getOperateDate().toString());
            m.put("remark", l.getRemark());
            out.add(m);
        }
        return out;
    }

    // ================================================================
    // 工具
    // ================================================================

    /** 保护期：专利 +20 年 / 软著 +50 年 / 其余不设期限（NULL） */
    private LocalDate protectExpire(String type, LocalDate completeDate) {
        if (completeDate == null) {
            return null;
        }
        if (PmAchievement.TYPE_PATENT.equals(type)) {
            return completeDate.plusYears(20);
        }
        if (PmAchievement.TYPE_SOFT_CP.equals(type)) {
            return completeDate.plusYears(50);
        }
        return null;
    }

    private void writeLog(String assetId, String from, String to, String operator, String remark) {
        PmIpAssetStatusLog l = new PmIpAssetStatusLog();
        l.setId(uuid());
        l.setAssetId(assetId);
        l.setFromStatus(from);
        l.setToStatus(to);
        l.setOperator(operator);
        l.setOperateDate(LocalDateTime.now());
        l.setRemark(remark);
        l.setCreateBy(operator);
        l.setCreateDate(LocalDateTime.now());
        statusLogDao.insert(l);
    }

    private void requireRole(String... wanted) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(403, "未认证");
        }
        List<String> roles = new ArrayList<>();
        auth.getAuthorities().forEach(r -> roles.add(r.getAuthority()));
        for (String w : wanted) {
            for (String r : roles) {
                if (("ROLE_" + w).equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前用户无权执行该操作");
    }

    private Map<String, Object> toMap(PmIpAsset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("achieveId", a.getAchieveId());
        m.put("achieveNo", a.getAchieveNo());
        m.put("name", a.getName());
        m.put("type", a.getType());
        m.put("projectId", a.getProjectId());
        m.put("ownershipResult", a.getOwnershipResult());
        m.put("completeDate", a.getCompleteDate() == null ? null : a.getCompleteDate().toString());
        m.put("protectExpireDate", a.getProtectExpireDate() == null ? null : a.getProtectExpireDate().toString());
        m.put("applyStatus", a.getApplyStatus());
        m.put("applyUpdatedAt", a.getApplyUpdatedAt() == null ? null : a.getApplyUpdatedAt().toString());
        return m;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
