package com.erp.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.qms.Ncr;
import com.erp.entity.qms.Scar;
import com.erp.entity.qms.ScarDeduction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * SCAR 供应商质量索赔（spec supplier-quality-claim，tasks 10.1~10.5，6.8）。
 *
 * 触发草稿（BR-4.12-43）：NCR 退货/挑选处置、3 月 3 次重复不合格、停线投诉
 *   → 自动带出检验数据与索赔明细；重大 SCAR（索赔 ≥1 万）质量经理审批后 SENT。
 * 加严联动（BR-4.12-44）：SENT 起抽样上调一档（推导加严，currentStrictness 读 SCAR 状态）；
 *   回复时限 scar-reply-days=5 工作日，超期升级采购经理 + 每 3 天提醒 + 响应及时性扣分标记；
 *   SQE 代录 8D 回复（D7 偏差），浅层根因回复退回（REJECT_COUNT 累计）。
 * 扣款单：PENDING_FINANCE → CONFIRMED → TO_DEDUCT（货款抵扣 TODO-NOTIFY 桩 D3）/ DISPUTED。
 */
public interface ScarService {

    /** NCR 处置（退货/挑选）自动带出草稿（tasks 10.1，幂等） */
    Scar createFromNcr(Ncr ncr, String triggerType);

    /** 手工发起（triggerType: NCR_RETURN / REPEAT / STOPPAGE；可关联 NCR） */
    Scar createDraft(Map<String, Object> body);

    /** 发出：重大（索赔 ≥10000）走质量经理审批，其余直接 SENT + 5 工作日回复时限 */
    Scar submit(String id);

    /** SQE 代录供应商 8D 回复（浅层根因 422 退回 + REJECT_COUNT 累计，tasks 10.4） */
    Scar recordReply(String id, String replyText);

    /** 验证：PASS → CLOSED；FAIL → 退回 SENT（时限重置、回复清空） */
    Scar verify(String id, boolean pass, String conclusion);

    /** 回复超期扫描（tasks 10.3，幂等）：超期升采购经理 + 每 3 天提醒 + 扣分标记 */
    int sweepReplyOverdue();

    // ================= 扣款单（tasks 10.5） =================

    /** 生成扣款单（PENDING_FINANCE；金额 ≤ 索赔额） */
    ScarDeduction createDeduction(String scarId, BigDecimal amount, String remark);

    /** 财务确认（底座 ScarFinance 单签 ADMIN 代）→ CONFIRMED */
    ScarDeduction submitFinance(String deductionId);

    /** 推送待抵扣（CONFIRMED → TO_DEDUCT + 应付抵扣 TODO-NOTIFY 桩 D3） */
    ScarDeduction markToDeduct(String deductionId);

    /** 争议暂挂 / 解除争议 */
    ScarDeduction dispute(String deductionId, String reason);

    /** 供应商维度扣款汇总（评分数据源，tasks 10.5） */
    Map<String, Object> deductionSummary(String supplierId);

    Page<Map<String, Object>> page(long current, long size, String keyword, String status,
                                   String supplierId);

    /** 详情：SCAR + 关联 NCR + 扣款单 + 审批 */
    Map<String, Object> detail(String id);

    List<ScarDeduction> deductions(String scarId);
}
