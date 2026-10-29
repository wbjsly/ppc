package com.erp.service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 到期回收与提醒扫描（add-permission-approval-flow design D6，SOP-5.6-B step6）：
 * 到期行回收（经代理保证逐笔事务）+ 到期前 7 日续期提醒（REMIND_AT 幂等）。
 */
public interface GrantSweepService {

    /** 扫描：{reclaimed, reminded, failed}；单笔失败不阻断其余 */
    Map<String, Object> sweep(LocalDateTime now);
}
