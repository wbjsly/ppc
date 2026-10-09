package com.erp.service.impl.system;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.SysNoticeDao;
import com.erp.entity.system.SysNotice;
import com.erp.service.system.NoticeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 站内通知：按角色投递（TARGET_ROLE）或指定用户（TARGET_USER）。
 * 已读标记为通知行级（同角色共享已读态，规模小、语义够用——见 design 记录）。
 */
@Slf4j
@Service
public class NoticeServiceImpl implements NoticeService {

    private static final int PAGE_LIMIT = 100;

    private final SysNoticeDao noticeDao;

    public NoticeServiceImpl(SysNoticeDao noticeDao) {
        this.noticeDao = noticeDao;
    }

    @Override
    public SysNotice push(String targetRole, String targetUser, String title, String content,
                          String bizType, String bizId) {
        if (isBlank(title)) {
            throw new ServiceException(422, "通知标题必填");
        }
        // 同一业务单据 + 类型已有未读通知 → 不重复推（幂等）
        if (!isBlank(bizType) && !isBlank(bizId)) {
            Long dup = noticeDao.selectCount(new LambdaQueryWrapper<SysNotice>()
                    .eq(SysNotice::getBizType, bizType)
                    .eq(SysNotice::getBizId, bizId)
                    .eq(SysNotice::getReadFlag, "0"));
            if (dup != null && dup > 0) {
                log.info("notice already pending for {} {}, skip", bizType, bizId);
                return null;
            }
        }
        SysNotice n = new SysNotice();
        n.setTargetRole(targetRole);
        n.setTargetUser(targetUser);
        n.setTitle(title);
        n.setContent(content);
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setReadFlag("0");
        noticeDao.insert(n);
        log.info("notice pushed: role={} user={} title={}", targetRole, targetUser, title);
        return n;
    }

    @Override
    public List<SysNotice> myNotices() {
        List<String> roles = currentRoles();
        String me = currentUser();
        LambdaQueryWrapper<SysNotice> qw = new LambdaQueryWrapper<SysNotice>()
                .and(w -> {
                    if (!roles.isEmpty()) {
                        w.in(SysNotice::getTargetRole, roles);
                    }
                    w.or().eq(SysNotice::getTargetUser, me);
                })
                .orderByAsc(SysNotice::getReadFlag)
                .orderByDesc(SysNotice::getCreateDate)
                .last("LIMIT " + PAGE_LIMIT);
        return noticeDao.selectList(qw);
    }

    @Override
    public long unreadCount() {
        List<String> roles = currentRoles();
        String me = currentUser();
        Long cnt = noticeDao.selectCount(new LambdaQueryWrapper<SysNotice>()
                .eq(SysNotice::getReadFlag, "0")
                .and(w -> {
                    if (!roles.isEmpty()) {
                        w.in(SysNotice::getTargetRole, roles);
                    }
                    w.or().eq(SysNotice::getTargetUser, me);
                }));
        return cnt == null ? 0 : cnt;
    }

    @Override
    public void markRead(String id) {
        SysNotice n = noticeDao.selectById(id);
        if (n == null) {
            throw new ServiceException(404, "通知不存在");
        }
        List<String> roles = currentRoles();
        String me = currentUser();
        boolean visible = me.equals(n.getTargetUser())
                || (n.getTargetRole() != null && roles.stream().anyMatch(r -> r.equalsIgnoreCase(n.getTargetRole())))
                || roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r));
        if (!visible) {
            throw new ServiceException(403, "无权操作该通知");
        }
        if ("1".equals(n.getReadFlag())) {
            return; // 幂等
        }
        n.setReadFlag("1");
        n.setReadAt(LocalDateTime.now());
        noticeDao.updateById(n);
    }

    // ---------- helpers ----------

    private List<String> currentRoles() {
        List<String> roles = new ArrayList<>();
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null) {
            auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        }
        return roles;
    }

    private String currentUser() {
        String id = SecurityUtils.getCurrentUserId();
        return id == null ? "system" : id;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
