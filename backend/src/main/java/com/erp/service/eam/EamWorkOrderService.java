package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamWorkOrder;

import java.util.Map;

/**
 * 统一工单服务（add-asset-maintenance，design D2/D3，FR-4.13-3-3/9/10）。
 * 状态机 PENDING→ASSIGNED→EXECUTING→PENDING_ACCEPT→CLOSED（+CANCELLED）单点校验防跳序；
 * 派工统一过 assertNoActiveLock（BR-16）；REPAIR 双验收（操作员+工程师），PREVENTIVE 工程师确认。
 */
public interface EamWorkOrderService {

    /** 工单分页（类型/状态/卡片号可筛） */
    Page<EamWorkOrder> page(long current, long size, String woType, String status, String cardNo);

    /** 工单详情（含领料/工时/成本明细） */
    Map<String, Object> detail(String woNo);

    /**
     * 派工（PENDING→ASSIGNED）：指派维修人；安全停机未解除 → 422 阻断并展示锁信息（BR-16）。
     */
    Map<String, Object> assign(String woNo, String assignee);

    /** 开始执行（ASSIGNED→EXECUTING） */
    Map<String, Object> startExecution(String woNo);

    /** 执行记录（EXECUTING：内容/问题/更换部件，可多次更新） */
    Map<String, Object> recordExecution(String woNo, String execDesc, String issueDesc, String partsChanged);

    /** 创建子工单（EXECUTING 中发现新问题，扩展工单范围，FR-4.13-3-9） */
    Map<String, Object> createSubWorkOrder(String parentWoNo, String desc, String faultSymptom);

    /** 提交验收（EXECUTING→PENDING_ACCEPT） */
    Map<String, Object> submitForAcceptance(String woNo);

    /**
     * 验收确认：who=OP 操作员 / ENG 设备工程师。
     * REPAIR 须 OP+ENG 双确认齐；PREVENTIVE 仅 ENG；齐后置验收时间与结果评价。
     */
    Map<String, Object> accept(String woNo, String who, String resultEval);

    /** 取消工单（非终态，留因） */
    Map<String, Object> cancel(String woNo, String reason);

    /**
     * 关闭工单（PENDING_ACCEPT→CLOSED）：BR-20 四前置校验 + 成本归集（design D6）。
     */
    Map<String, Object> close(String woNo);

    /**
     * BR-4.13-19 冻结校验：同卡同现象存在未归档大修评估 → 422，禁止创建同类维修工单。
     * 报修转工单/手工建单前调用。
     */
    void assertNoOverhaulFreeze(String cardId, String faultSymptom);

    /** 评估审批回调：结论归档（APPROVED/REJECTED）→ 解冻同类工单创建 */
    Map<String, Object> completeOverhaulAssess(String woId, String assessStatus);

    /**
     * 备件领用（FR-4.13-3-4 / BR-4.13-17）：EXECUTING 工单发起，超工单备件清单且无原因 → 422 L1；
     * 通过后生成 MAINT 领料单（DRAFT，走库存 FIFO 配批与出库过账，移动平均价过账回写）。
     * lines: [{itemCode, itemName?, qty}]；overReason: 超领说明（不超领可空）。
     */
    Map<String, Object> requestSpares(String woNo, java.util.List<Map<String, Object>> lines, String overReason);

    /**
     * 回填工时（EXECUTING/PENDING_ACCEPT；BR-20 关闭前置的工时来源）。
     * POST_TYPE=ENGR/TECH/OTHER（费率匹配键）。
     */
    Map<String, Object> recordTime(String woNo, String worker, String postType,
                                   java.math.BigDecimal workHours, String workDate);
}
