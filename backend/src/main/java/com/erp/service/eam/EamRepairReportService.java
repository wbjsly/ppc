package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamRepairReport;

import java.util.Map;

/**
 * 报修单服务（add-asset-maintenance，design D2，FR-4.13-3-7/8）。
 * 全员可提；安全风险自动挂锁；工程师确认转 REPAIR 工单或驳回。
 */
public interface EamRepairReportService {

    /** 报修分页（状态/卡片号可筛） */
    Page<EamRepairReport> page(long current, long size, String cardNo, String status);

    /** 提交报修（任何登录用户；SAFETY_RISK=1 时自动挂锁） */
    Map<String, Object> submit(EamRepairReport report);

    /**
     * 工程师确认并转维修工单（ASSIGNED）：填写方案 SELF/OUTSOURCE、预计修复时间、指派维修人。
     * 安全停机未解除 → 422 阻断（BR-4.13-16）。
     */
    Map<String, Object> convertToWorkOrder(String reportId, String assignee,
                                           String resolvePlan, String outsourceVendor);

    /** 驳回报修（留因） */
    Map<String, Object> reject(String reportId, String reason);
}
