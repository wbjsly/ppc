package com.erp.service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 指定时点生效扫描（add-permission-approval-flow design D5）：
 * APPROVED_PENDING + SCHEDULED 且 ACTIVATE_AT 已到的申请单逐笔生效。
 * @Scheduled 注册 enabled 默认 false（账号 sweep/CPFR 先例），并提供手动触发端点。
 */
public interface ActivationSweepService {

    /** 扫描到点申请单逐笔生效；单笔失败不阻断其余，返回 {activated, failed} */
    Map<String, Object> sweepDue(LocalDateTime now);
}
