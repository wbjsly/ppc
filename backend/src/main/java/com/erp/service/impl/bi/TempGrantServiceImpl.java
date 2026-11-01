package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiTempGrantDao;
import com.erp.entity.bi.BiTempGrant;
import com.erp.service.approval.ApprovalEngine;
import com.erp.service.approval.ApprovalNodeSpec;
import com.erp.service.bi.TempGrantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时授权实现（add-perm-control-console，spec bi-temp-grant）。
 * 申请 → ROLE_DATA_GOV 单签 → ACTIVE（≤24h）→ 到期自动收回；
 * maskIfHit 经 hasActive 放行明文（单请求内 memo 降频）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TempGrantServiceImpl implements TempGrantService {

    private final BiTempGrantDao grantDao;
    private final ApprovalEngine approvalEngine;
    private final JdbcTemplate jdbc;

    /** hasActive 单请求内 memo（design D4：降 maskIfHit 高频路径开销） */
    private final ThreadLocal<Map<String, Boolean>> memo = ThreadLocal.withInitial(HashMap::new);

    @Override
    @Transactional
    public Map<String, Object> apply(Map<String, Object> payload) {
        String fieldKey = str(payload.get("fieldKey"));
        String purpose = str(payload.get("purpose"));
        if (!StringUtils.hasText(fieldKey)) {
            throw new ServiceException(422, "申请字段必填");
        }
        if (!StringUtils.hasText(purpose) || purpose.trim().length() < 5) {
            throw new ServiceException(422, "用途说明必填（至少 5 字）");
        }
        String userName = str(payload.get("userName"));
        if (!StringUtils.hasText(userName)) {
            userName = operator();
        }
        Integer hours = payload.get("hours") == null ? BiTempGrant.MAX_HOURS
                : Integer.parseInt(String.valueOf(payload.get("hours")));
        if (hours == null || hours <= 0 || hours > BiTempGrant.MAX_HOURS) {
            // spec：超 24h 上限 422
            throw new ServiceException(422, "有效期超过上限：仅可 ≤" + BiTempGrant.MAX_HOURS + " 小时");
        }
        BiTempGrant g = new BiTempGrant();
        g.setId(uuid());
        g.setFieldKey(fieldKey.trim());
        g.setUserName(userName);
        g.setPurpose(purpose.trim());
        g.setStatus(BiTempGrant.ST_PENDING);
        g.setApplyAt(LocalDateTime.now());
        g.setExpireAt(g.getApplyAt().plusHours(hours));
        g.setRemark(str(payload.get("remark")));
        g.setCreateBy(operator());
        grantDao.insert(g);
        List<List<ApprovalNodeSpec>> chain = List.of(
                List.of(ApprovalNodeSpec.sign("ROLE_DATA_GOV", "数据治理专员-临时授权")));
        var inst = approvalEngine.submit("BiTempGrant", g.getId(),
                "临时授权申请：" + userName + " 查看 " + fieldKey + "（" + hours + "h）",
                "ROLE_DATA_GOV", chain);
        g.setApprId(inst.getId());
        grantDao.updateById(g);
        log.info("临时授权申请 {} {} by {} hours={}", g.getId(), fieldKey, userName, hours);
        return Map.of("id", g.getId(), "status", g.getStatus(),
                "expireAt", String.valueOf(g.getExpireAt()), "approvalInstanceId", inst.getId());
    }

    @Override
    public List<Map<String, Object>> listMine(String userName) {
        String u = StringUtils.hasText(userName) ? userName : operator();
        return views(grantDao.selectList(new LambdaQueryWrapper<BiTempGrant>()
                .eq(BiTempGrant::getUserName, u)
                .orderByDesc(BiTempGrant::getCreateDate)));
    }

    @Override
    public List<Map<String, Object>> listAll(String status) {
        LambdaQueryWrapper<BiTempGrant> qw = new LambdaQueryWrapper<BiTempGrant>()
                .orderByDesc(BiTempGrant::getCreateDate);
        if (StringUtils.hasText(status)) {
            qw.eq(BiTempGrant::getStatus, status);
        }
        return views(grantDao.selectList(qw));
    }

    @Override
    @Transactional
    public Map<String, Object> revoke(String id) {
        BiTempGrant g = require(id);
        if (!BiTempGrant.ST_ACTIVE.equals(g.getStatus())) {
            throw new ServiceException(422, "仅生效中的授权可收回（当前：" + g.getStatus() + "）");
        }
        g.setStatus(BiTempGrant.ST_EXPIRED);
        g.setRecallAt(LocalDateTime.now());
        g.setUpdateBy(operator());
        grantDao.updateById(g);
        memo.get().clear();
        return Map.of("id", id, "status", g.getStatus(), "recallAt", String.valueOf(g.getRecallAt()));
    }

    @Override
    public boolean hasActive(String userName, String fieldKey) {
        if (!StringUtils.hasText(userName) || !StringUtils.hasText(fieldKey)) {
            return false;
        }
        String key = userName + "|" + fieldKey;
        Map<String, Boolean> m = memo.get();
        Boolean cached = m.get(key);
        if (cached != null) {
            return cached;
        }
        Long n = grantDao.selectCount(new LambdaQueryWrapper<BiTempGrant>()
                .eq(BiTempGrant::getUserName, userName)
                .eq(BiTempGrant::getFieldKey, fieldKey)
                .eq(BiTempGrant::getStatus, BiTempGrant.ST_ACTIVE)
                .gt(BiTempGrant::getExpireAt, LocalDateTime.now()));
        boolean hit = n != null && n > 0;
        m.put(key, hit);
        return hit;
    }

    /** cron：每 10 分钟扫描过期 ACTIVE → EXPIRED（design D4） */
    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void expireOverdueCron() {
        int n = expireOverdue();
        if (n > 0) {
            log.info("临时授权到期自动收回 {} 条", n);
        }
    }

    @Override
    @Transactional
    public int expireOverdue() {
        List<BiTempGrant> overdue = grantDao.selectList(new LambdaQueryWrapper<BiTempGrant>()
                .eq(BiTempGrant::getStatus, BiTempGrant.ST_ACTIVE)
                .le(BiTempGrant::getExpireAt, LocalDateTime.now()));
        for (BiTempGrant g : overdue) {
            g.setStatus(BiTempGrant.ST_EXPIRED);
            g.setRecallAt(LocalDateTime.now());
            g.setUpdateBy("system");
            grantDao.updateById(g);
        }
        if (!overdue.isEmpty()) {
            memo.get().clear();
        }
        return overdue.size();
    }

    // ---- 回调入口 ----

    /** 审批通过回调：PENDING → ACTIVE（审批引擎回调内） */
    @Transactional
    public void activateOnApproved(String id) {
        BiTempGrant g = require(id);
        if (BiTempGrant.ST_ACTIVE.equals(g.getStatus())) {
            return; // decide 已处理
        }
        LocalDateTime cap = g.getApplyAt().plusHours(BiTempGrant.MAX_HOURS);
        if (g.getExpireAt().isAfter(cap)) {
            g.setExpireAt(cap);
        }
        g.setStatus(BiTempGrant.ST_ACTIVE);
        g.setUpdateBy(operator());
        grantDao.updateById(g);
        memo.get().clear();
        log.info("临时授权审批通过生效 {} {} 至 {}", id, g.getFieldKey(), g.getExpireAt());
    }

    /** 审批驳回回调 */
    @Transactional
    public void rejectOnCallback(String id) {
        BiTempGrant g = require(id);
        g.setStatus(BiTempGrant.ST_REJECTED);
        g.setUpdateBy(operator());
        grantDao.updateById(g);
    }

    // ---- helpers ----

    private List<Map<String, Object>> views(List<BiTempGrant> list) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BiTempGrant g : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("fieldKey", g.getFieldKey());
            m.put("userName", g.getUserName());
            m.put("purpose", g.getPurpose());
            m.put("status", g.getStatus());
            m.put("applyAt", g.getApplyAt());
            m.put("expireAt", g.getExpireAt());
            m.put("recallAt", g.getRecallAt());
            m.put("apprId", g.getApprId());
            out.add(m);
        }
        return out;
    }

    private BiTempGrant require(String id) {
        BiTempGrant g = grantDao.selectById(id);
        if (g == null) {
            throw new ServiceException(404, "临时授权不存在：" + id);
        }
        return g;
    }

    private String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String operator() {
        try {
            String u = com.erp.util.SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}
