package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamSafetyLockDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamSafetyLock;
import com.erp.service.eam.EamSafetyLockService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 安全停机挂锁（add-asset-maintenance，design D4，BR-4.13-16 L1）。
 */
@Slf4j
@Service
public class EamSafetyLockServiceImpl implements EamSafetyLockService {

    private final EamSafetyLockDao lockDao;
    private final EamAssetCardDao cardDao;

    public EamSafetyLockServiceImpl(EamSafetyLockDao lockDao, EamAssetCardDao cardDao) {
        this.lockDao = lockDao;
        this.cardDao = cardDao;
    }

    @Override
    public Page<EamSafetyLock> page(long current, long size, String cardNo, String status) {
        LambdaQueryWrapper<EamSafetyLock> qw = new LambdaQueryWrapper<EamSafetyLock>()
                .like(cardNo != null && !cardNo.isBlank(), EamSafetyLock::getCardNo, cardNo)
                .eq(status != null && !status.isBlank(), EamSafetyLock::getStatus, status)
                .orderByDesc(EamSafetyLock::getLockTime);
        return lockDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public Map<String, Object> lock(String cardId, String reason, String sourceWoNo) {
        if (reason == null || reason.isBlank()) {
            throw new ServiceException(422, "挂锁原因必填");
        }
        EamAssetCard card = cardDao.selectById(cardId);
        if (card == null) {
            throw new ServiceException(422, "资产卡片不存在：" + cardId);
        }
        List<EamSafetyLock> active = lockDao.selectActiveByCard(cardId);
        if (!active.isEmpty()) {
            EamSafetyLock exist = active.get(0);
            throw new ServiceException(409, "该资产已存在未解除挂锁 " + exist.getLockNo()
                    + "（挂锁人 " + exist.getLockBy() + "，" + exist.getLockTime() + "），请先解锁");
        }
        EamSafetyLock lock = new EamSafetyLock();
        lock.setLockNo(nextLockNo());
        lock.setCardId(cardId);
        lock.setCardNo(card.getCardNo());
        lock.setReason(reason);
        lock.setLockBy(SecurityUtils.getCurrentUserId());
        lock.setLockTime(LocalDateTime.now());
        lock.setStatus("ACTIVE");
        lock.setSourceWoNo(sourceWoNo);
        lock.setCreateBy(SecurityUtils.getCurrentUserId());
        lockDao.insert(lock);
        log.info("安全停机挂锁：{} 卡片 {}（来源工单 {}）", lock.getLockNo(), card.getCardNo(), sourceWoNo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lock", lock);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> release(String lockId, String releaseReason) {
        requireMgr("解除安全锁");
        EamSafetyLock lock = lockDao.selectById(lockId);
        if (lock == null) {
            throw new ServiceException(422, "挂锁记录不存在：" + lockId);
        }
        if (!"ACTIVE".equals(lock.getStatus())) {
            throw new ServiceException(409, "挂锁已解除，无需重复操作");
        }
        lock.setStatus("RELEASED");
        lock.setReleaseBy(SecurityUtils.getCurrentUserId());
        lock.setReleaseTime(LocalDateTime.now());
        lock.setReleaseReason(releaseReason);
        lock.setUpdateBy(SecurityUtils.getCurrentUserId());
        lockDao.updateById(lock);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lock", lock);
        return out;
    }

    @Override
    public void assertNoActiveLock(String cardId) {
        List<EamSafetyLock> active = lockDao.selectActiveByCard(cardId);
        if (!active.isEmpty()) {
            EamSafetyLock lock = active.get(0);
            throw new ServiceException(422, "资产处于安全停机状态，操作被阻断（BR-4.13-16）：安全锁编号 "
                    + lock.getLockNo() + "，挂锁人 " + lock.getLockBy() + "，挂锁时间 " + lock.getLockTime()
                    + "，原因：" + lock.getReason());
        }
    }

    /** 锁编号 LOCK + yyyyMMdd + - + 4 位流水（唯一索引兜底并发） */
    private String nextLockNo() {
        String prefix = "LOCK" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + "-";
        Integer max = lockDao.selectMaxSeq(prefix);
        return prefix + String.format("%04d", (max == null ? 0 : max) + 1);
    }

    /** 解锁限设备主管（design D1 角色） */
    private void requireMgr(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        for (var a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equalsIgnoreCase(r) || "ROLE_EQUIP_MGR".equalsIgnoreCase(r)) {
                return;
            }
        }
        throw new ServiceException(403, "当前角色无权" + action + "（须设备主管）");
    }
}
