package com.erp.service;

import com.erp.entity.system.PermRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 权限申请（add-permission-approval-flow spec：SOP-5.6-B 全流程）。
 * 提交 = 四字段校验 + 风险标注 + 强制 SoD 复检快照 + 风险路由链构建 + 审批发起。
 */
public interface PermRequestService {

    /**
     * 提交常规申请（KIND=NORMAL）。
     * 校验：数据范围必填 / 理由≥10字 / 有效期 1-12 月 / 特权须附工单号 /
     *       同一目标用户已有 PENDING 申请 422。
     */
    Map<String, Object> submitNormal(String targetUserId, List<String> roleCodes, String dataScope,
                                     String reason, String ticketNo, Integer validMonths,
                                     String activationMode, LocalDateTime activateAt);

    /**
     * 提交超基线例外（KIND=EXCEPTION，吸收 RoleGrantException，D7）：
     * 目标角色全集落单（审批通过后全集生效），有效期默认 12 个月。
     */
    Map<String, Object> submitException(String targetUserId, List<String> targetRoles,
                                        List<String> beyondRoles, String reason);

    /** 申请单列表（status/keyword/outWindow 过滤，带目标用户名；outWindow=true 仅窗口外升级单） */
    List<Map<String, Object>> list(String status, String keyword, String outWindow);

    /** 详情：申请单 + 预检快照（解析） + 审批日志 */
    Map<String, Object> detail(String id);

    /**
     * 窗口外变更复核台账（add-change-window-console spec：16.4.3 只读证据面）。
     * 每笔窗口外申请一行：申请字段 + CCB 三方签署明细（签署人/时间/意见/结果）。
     */
    List<Map<String, Object>> outWindowLedger();
}
