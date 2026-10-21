package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamMaintPlan;

import java.util.Map;

/**
 * 维护计划服务（add-asset-maintenance，design D2，FR-4.13-3-1/2）。
 * 计划 CRUD（关键设备须主管审批）+ 手动批量生成计划工单（幂等/顺延/备件不足预警）。
 */
public interface EamMaintPlanService {

    /** 计划分页（卡片号/关键标志可筛） */
    Page<EamMaintPlan> page(long current, long size, String cardNo, String criticalFlag);

    /** 创建/修改计划（无审批立即生效；关键设备置待审批） */
    Map<String, Object> savePlan(EamMaintPlan plan);

    /** 关键设备计划提交审批（ROLE_EQUIP_MGR 单签） */
    Map<String, Object> submitApproval(String planId);

    /** 审批通过回调：置 APPROVED */
    Map<String, Object> approveFromApproval(String planId);

    /** 审批驳回回调：置 REJECTED 留因 */
    Map<String, Object> rejectFromApproval(String planId, String reason);

    /** 停用/删除计划（软删） */
    Map<String, Object> disable(String planId);

    /**
     * 批量生成计划工单（design D2）：窗口 = 今日 ~ 今日+MAINT_ORDER_LEAD_DAYS；
     * 幂等键 = 计划ID+计划日（重复执行不重建）；关键设备未审批 422；
     * 计划日该卡有 ACTIVE 挂锁 → 顺延一日并留痕；备件库存不足收集 L4 预警返回。
     */
    Map<String, Object> generatePlanOrders();
}
