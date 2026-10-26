package com.erp.service.eam;

import com.erp.entity.eam.EamDisposalOrder;
import com.erp.entity.eam.EamDisposalLine;
import com.erp.entity.eam.EamDisposalRecycle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 资产处置服务（add-asset-disposal，design D2/D3/D4）。
 *
 * <p>状态机：DRAFT → PENDING_APPROVE → APPROVED（段1 转清理自动）→ EXECUTED
 * ⇄ RECEIVABLE → CLOSED；BR-35 应收未结不得关闭。</p>
 */
public interface EamDisposalService {

    /** 创建处置单（批量一单多卡；D6 前置断言逐项列示） */
    Map<String, Object> create(EamDisposalOrder order, List<EamDisposalLine> lines,
                               List<EamDisposalRecycle> recycles);

    /** 编辑草稿（仅 DRAFT；行整单重建） */
    void update(EamDisposalOrder order, List<EamDisposalLine> lines,
                List<EamDisposalRecycle> recycles);

    /** 提交审批（C-05 残值预警复核 + BR-31 出售报告 + 分级链） */
    Map<String, Object> submit(String id);

    /** 作废（仅 DRAFT） */
    void voidOrder(String id, String reason);

    /** 审批通过回调：段1 转清理凭证 + 状态推进 APPROVED（失败整单回滚，C-4.13-03） */
    Map<String, Object> onApprovalPassed(String id);

    /** 审批驳回回调：REJECTED 留因 */
    void onApprovalRejected(String id, String reason);

    /** 执行确认（BR-33 科目/BR-32 提足/BR-36 附件校验 → 回收入库 → 段2/3 凭证 → 卡片 DISPOSED） */
    Map<String, Object> exec(String id, String proofNo, String envAssessNo, String dataDestroyNo);

    /** 收款登记（BR-35 冲减应收余额） */
    Map<String, Object> receive(String id, BigDecimal amount, String note);

    /** 结案报告提交（应收/入库/卡片条件校验 → 单签审批） */
    Map<String, Object> submitReport(String id);

    /** 报告审批通过回调：CLOSED 归档 */
    void onReportClosed(String id);

    /** 整单冲回（结案前；逆序冲回三段凭证 + 卡片回 IN_USE） */
    Map<String, Object> reverse(String id);
}
