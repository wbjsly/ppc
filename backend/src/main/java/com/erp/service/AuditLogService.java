package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 审计日志检索（16.7.1，只读 + 脱敏展示）与防篡改尝试记录（BR-5.6-13）。
 */
public interface AuditLogService {

    /**
     * 组合检索（操作人/动作/对象/关键词/时间范围，分页倒序）；
     * OLD_VALUE/NEW_VALUE/REASON 经脱敏规则掩码后返回。
     */
    Map<String, Object> search(String changeBy, String fieldName, String empId,
                               String keyword, String from, String to,
                               Integer page, Integer size);

    /** 防篡改尝试记录列表（倒序；新尝试行出现时驱动站内告警，幂等） */
    List<Map<String, Object>> tamperList(Integer limit);

    /** 告警联动扫描：未通知的尝试行 → 站内通知 ROLE_UAR_AUDITOR 持有者（幂等） */
    Map<String, Object> sweepTamperNotices();
}
