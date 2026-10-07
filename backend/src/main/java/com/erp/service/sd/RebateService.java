package com.erp.service.sd;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.sd.RebateBudget;
import com.erp.entity.sd.RebatePolicy;
import com.erp.entity.sd.RebateSettlement;
import com.erp.entity.sd.RebateTarget;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 返利结算（tasks 11.2~11.8，spec sales-rebate，FR-4.3-8-1~6）。
 *
 * 口径：
 *  - 三配置（季度目标 / 政策阶梯 / 年度预算按季分解）入口在 3.9.1 页内 Tab，不新增菜单；
 *  - 达成率基数 = 季度开票确认额 − 退货退款额（红字冲减），数据不完整延期不出结果；
 *  - 返利按「超额累进」分段计算（各阶梯仅对落入该阶梯的超额部分计返利）；
 *  - 返利 > 季度预算余额 → C-4.3-05 自动升级销售总监 L2 + 超预算说明/平衡方案必填；
 *  - 执行两方式：应付冲抵（FIFO 冲抵未清应收）/ 现金兑现，客户确认方式留痕；
 *  - 执行完成生成结算凭证（借 6601 销售费用 / 贷 1122 或 1002）并更新客户应收余额。
 */
public interface RebateService {

    // ---------- 三配置（11.2，3.9.1 页内 Tab） ----------

    Page<RebateTarget> targetPage(long current, long size, String customerId, String quarter);

    RebateTarget saveTarget(RebateTarget target);

    void removeTarget(String id);

    List<RebatePolicy> policyList(String customerId);

    RebatePolicy savePolicy(RebatePolicy policy);

    void removePolicy(String id);

    RebateBudget getBudget(int year);

    /** 保存年度预算：仅填总额时按季均分（25%×4）自动分解 */
    RebateBudget saveBudget(RebateBudget budget);

    /** 额度/消耗/余额：{year, quarter, budget, consumed, remain} */
    Map<String, Object> budgetSummary(int year, String quarter);

    // ---------- 计算与结算（11.3~11.8） ----------

    /**
     * 季度返利计算（11.3/11.4/11.5）：政策未维护/目标未维护/预算未维护 → 422 阻断；
     * 存在未完成退货 → 置 pendingData 不出结果；否则按超额累进分段计算并做预算校验，
     * 生成/刷新 DRAFT 结算单并返回分段明细。
     */
    Map<String, Object> calculate(String customerId, String quarter);

    Page<RebateSettlement> page(long current, long size, String customerId,
                                String quarter, String status);

    /** 结算单详情（含分段明细与审批日志） */
    Map<String, Object> detail(String id);

    /** 提交审批（11.6）：正常 → 销售主管单节点；超预算 → + 销售总监（说明与平衡方案必填） */
    RebateSettlement submit(String id, String overReason, String balancePlan);

    /**
     * 执行（11.7/11.8）：OFFSET = FIFO 冲抵未清应收（余额不足 422）；CASH = 现金兑现登记。
     * 完成后生成结算凭证并回写凭证号与执行留痕。
     */
    Map<String, Object> execute(String id, String execType, LocalDate execDate,
                                String confirmBy, String confirmNote);
}
