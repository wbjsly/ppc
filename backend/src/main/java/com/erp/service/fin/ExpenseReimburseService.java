package com.erp.service.fin;

import java.util.List;
import java.util.Map;

/**
 * 费用报销（M8-8-1，spec expense-reimbursement，FR-4.6-7-3/4 + C-4.6-13）。
 * 审批链：部门主管 → 财务审核（两节点串行，决策④）；
 * 审批通过 Callback 落账（凭证 + FIFO 冲销，决策⑤账务自动）；
 * 差额补付/退款为出纳手动动作（决策⑤资金手动）。
 */
public interface ExpenseReimburseService {

    /**
     * 提交报销单（头+行+审批一体）：行合计=总额校验；每行费用类型映射校验
     * （缺失/停用 422 提示补录，FR-4.6-7-5）；发票号历史重复 L1 阻断并展示原单号（C-4.6-13）。
     */
    Map<String, Object> submit(Map<String, Object> payload);

    /** 报销单列表（状态/员工过滤） */
    List<Map<String, Object>> list(String status, String employeeId);

    /** 详情（行 + 冲销明细 + 结算记录） */
    Map<String, Object> detail(String id);

    /**
     * 出纳补付差额（FR-4.6-7-7，决策⑤）：走统一收付中心机制件（账户条件扣减 + 凭证 借 2241/贷 1002），
     * 关联回报销单；仅 APPROVED 且存在待补差额时可执行，余额不足 422。
     */
    Map<String, Object> payDifference(String id, Map<String, Object> payload);

    /**
     * 出纳登记员工退款（报销<借款的剩余借款回收，FR-4.6-7-7）：收付中心 IN
     * （借 1002/贷 1299）+ 回写借款单未核销余额清零。
     */
    Map<String, Object> refund(String id, Map<String, Object> payload);

    /**
     * 待结算差额计算（审批后 Callback 调用）：
     * 差额 = 报销额 − 冲销合计；>0 待补付，<0 对应剩余借款待收。
     */
    Map<String, Object> settleInfo(String id);
}
