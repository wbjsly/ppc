package com.erp.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 变更窗口（add-permission-approval-flow design D5；16.4.1 窗口维护页复用）：
 * 周期窗口（星期 + 时刻区间）维护与窗口判定；特权权限仅窗口内生效。
 */
public interface ChangeWindowService {

    List<Map<String, Object>> list();

    /** 新建/更新窗口（id 空 = 新建）；校验 1-7 星期与起止时刻合法 */
    void save(String id, String name, Integer dayOfWeek, String startTime, String endTime, String status);

    void delete(String id);

    /** 当前时刻是否落在任一启用窗口内 */
    boolean isInWindow(LocalDateTime now);
}
