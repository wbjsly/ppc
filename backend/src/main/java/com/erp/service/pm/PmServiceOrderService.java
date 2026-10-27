package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/**
 * 服务工单（add-service-order，M11.10，spec service-order）。
 * 登记/查重标注、自动定级（映射可配、人工改留痕）、负载派单/改派、
 * 处理记录（仅追加）、根因/NCR 卡控、挂起恢复、确认关闭双通道、回访与改进任务。
 */
public interface PmServiceOrderService {

    /** 投诉登记（必填校验 L1、自动定级建议、重复投诉提示候选） */
    Map<String, Object> register(Map<String, Object> payload);

    /** 分页列表（客户/等级/状态/工程师/关键字筛选；duplicate 标注带出） */
    Map<String, Object> page(Map<String, Object> query);

    /** 详情（头 + 处理记录时间线 + 回访 + 确认 token 状态） */
    Map<String, Object> detail(String id);

    /** 关联为重复投诉（duplicate_of 指向前单，SLA 独立计时） */
    Map<String, Object> markDuplicate(String id, Map<String, Object> payload);

    /** 人工调整定级（理由必填，L5 留痕：Before/After 写处理记录+审计） */
    Map<String, Object> adjustLevel(String id, Map<String, Object> payload);

    /** 派单（默认负载最少推荐；可改派；留痕） */
    Map<String, Object> dispatch(String id, Map<String, Object> payload);

    /** 负载推荐：当前在手工单最少的服务工程师 */
    Map<String, Object> recommendEngineer();

    /** 受理（工程师接单，响应计时终点，状态 DISPATCHED→PROCESSING） */
    Map<String, Object> accept(String id);

    /** 追加处理记录（BR-4.8-35 仅追加） */
    Map<String, Object> appendProcess(String id, Map<String, Object> payload);

    /** 保存根因分类（未填不可提交关闭；设计缺陷抄送研发负责人） */
    Map<String, Object> saveRootCause(String id, Map<String, Object> payload);

    /** 关联 NCR（存在性校验，只读查 qms） */
    Map<String, Object> linkNcr(String id, Map<String, Object> payload);

    /** 标记已根治（质量类无 NCR → 422，BR-4.8-36） */
    Map<String, Object> markCured(String id);

    /** 挂起「待客户信息」（SLA 暂停计时留痕） */
    Map<String, Object> suspend(String id, Map<String, Object> payload);

    /** 恢复处理（累计挂起时长，顺延解决截止） */
    Map<String, Object> resume(String id);

    /** 提交客户确认（生成免密 token；根因未填 422） */
    Map<String, Object> submitConfirm(String id);

    /** 代录确认/驳回（confirmed_via=AGENT；驳回重计时，≥2 次升级主管） */
    Map<String, Object> agentConfirm(String id, Map<String, Object> payload);

    /** 生成客户确认链接（token 一次性，5 工作日过期） */
    Map<String, Object> generateConfirmLink(String id);

    /** 匿名端点：token 取工单摘要（确认页） */
    Map<String, Object> tokenView(String token);

    /** 匿名端点：token 确认/驳回（confirmed_via=LINK） */
    Map<String, Object> tokenConfirm(String token, Map<String, Object> payload);

    /** 满意度回访录入（≤2 分自动生成改进任务） */
    Map<String, Object> saveSurvey(String id, Map<String, Object> payload);

    /** 回访统计三维汇总（客户/产品线/工程师；客户健康度口径） */
    Map<String, Object> surveyStats(Map<String, Object> query);

    /** 改进任务列表 */
    Map<String, Object> improveTasks(Map<String, Object> query);

    /** 改进任务状态流转（PENDING→PROCESSING→CLOSED） */
    Map<String, Object> updateImproveTask(String id, Map<String, Object> payload);

    /** 售后领料过账回调：行金额累加服务成本（AFTER_SALE，spec material-issue） */
    void addMaterialCost(String serviceOrderNo, java.math.BigDecimal amount);

    /** 手动触发每日扫描四段（ADMIN 运维端点，与 PmServiceOrderDailyTask 同一逻辑） */
    Map<String, Object> sweep();
}
