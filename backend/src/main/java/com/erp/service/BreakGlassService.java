package com.erp.service;

import com.erp.entity.system.BgRequest;

import java.util.List;
import java.util.Map;

/**
 * 紧急权限（Break-Glass，add-break-glass-console spec：SOP-5.6-C 全链）。
 * 工单处理中校验（BR-5.6-05）→ 双签（值班经理+安全管理员）→ 台账授予（豁免窗口）
 * → 到期回收（豁免基线守卫）→ 事后审计签署与逾期告警（BR-5.6-08）。
 */
public interface BreakGlassService {

    // ---------- 故障工单 ----------

    /** 工单列表（status 过滤；BREAK_GLASS 申请侧仅取 PROCESSING） */
    List<Map<String, Object>> incidentList(String status);

    /** 新建/更新工单（id 空 = 新建；INC_NO 创建后不可改） */
    Map<String, Object> incidentSave(Map<String, Object> body);

    /** 工单状态流转（PROCESSING → RESOLVED/CLOSED） */
    void incidentTransition(String id, String status);

    // ---------- 权限模板 ----------

    /** 模板列表（含停用；申请侧仅取启用） */
    List<Map<String, Object>> templateList();

    /** 新建/更新模板（角色集合禁特权角色 422） */
    Map<String, Object> templateSave(Map<String, Object> body);

    // ---------- 申请与审批 ----------

    /**
     * 提交 Break-Glass 申请：工单处理中 422 / 模板启用 / 时长 1..BREAK_GLASS_MAX_HOURS
     * （默认 2）/ 处置说明 ≥10 字 / 录屏声明必选；同目标用户 PENDING 去重 422。
     */
    Map<String, Object> submit(String targetUserId, String templateId, String incidentId,
                               Integer durationHours, String screenDeclare, String reason);

    /** 申请单列表（status 过滤，带目标用户名/工单号/模板名） */
    List<Map<String, Object>> requestList(String status);

    /** 详情：申请单 + 审批日志 */
    Map<String, Object> detail(String id);

    /** 提前归还（幂等；回收同台账回收入口，豁免基线守卫） */
    Map<String, Object> reclaim(String id);

    /** 台账到期回收 BG 行的统一入口（GrantSweep 与提前归还共用） */
    void reclaimLedger(String ledgerId);

    // ---------- 事后审计 ----------

    /** 回收时刻自动生成报告（LEDGER_ID 幂等）；含日志条数汇总与专项评审标记 */
    void generateAudit(BgRequest req, String ledgerId);

    /** 审计报告列表（含申请/目标用户名） */
    List<Map<String, Object>> auditList();

    /** 签署结论（NO_VIOLATION/VIOLATION，签署人+时间） */
    void signAudit(String id, String conclusion);

    /** 逾期告警扫描：回收超 24h 未签 → 每 12h 站内通知（幂等） */
    Map<String, Object> sweepAuditOverdue();
}
