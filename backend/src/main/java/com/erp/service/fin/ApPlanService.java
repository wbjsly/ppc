package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.ApPlan;

import java.util.Map;

/** 付款计划闭环（8.3.3，spec ap-management：计划单+审批+超计划阻断+执行累计回写） */
public interface ApPlanService {

    /** 创建付款计划（PERIOD+LEGAL_ENTITY 唯一；planLines 分供应商计划行 JSON） */
    Map<String, Object> create(ApPlan req);

    /** 提交审批 */
    Map<String, Object> submit(String id);

    /** 审批回调锚（ApprovalCallback 用）：通过→APPROVED / 驳回→DRAFT 留因 */
    void onApprovalResult(String id, boolean approved, String reason);

    /** 关闭计划（期末归档） */
    Map<String, Object> close(String id);

    /** 计划分页 */
    Page<ApPlan> page(long current, long size, String period, String status);

    /** 计划详情（分供应商行 JSON 解析） */
    Map<String, Object> detail(String id);

    /**
     * 超计划校验（PaymentService.execute 前调用，design D4）：
     * 当期 APPROVED 计划存在且 EXECUTED_TOTAL + amount > PLAN_TOTAL → 422 阻断。
     */
    void assertWithinPlan(String period, java.math.BigDecimal amount);

    /** 执行累计回写（付款成功后调用，同事务） */
    void addExecuted(String period, java.math.BigDecimal amount);
}
