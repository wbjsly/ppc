package com.erp.service.system;

import com.erp.entity.system.SysNotice;

import java.util.List;

/**
 * 站内通知（按角色投递，spec opportunity-management FR-4.8-1-5 场景「推送一条通知记录」）。
 * 首个用例：商机预期金额 >100 万自动通知销售经理。
 */
public interface NoticeService {

    /** 推送一条通知（同 BIZ_TYPE + BIZ_ID 已存在未读记录时不重复推） */
    SysNotice push(String targetRole, String targetUser, String title, String content,
                   String bizType, String bizId);

    /** 当前用户可见通知（按其角色聚合，未读在前、时间倒序，最多 100 条） */
    List<SysNotice> myNotices();

    /** 当前用户未读数（页头角标） */
    long unreadCount();

    /** 标记已读（仅可标记自己可见的通知） */
    void markRead(String id);
}
