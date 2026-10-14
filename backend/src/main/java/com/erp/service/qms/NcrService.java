package com.erp.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.qms.InspectionLot;
import com.erp.entity.qms.Ncr;
import com.erp.entity.qms.NcrLog;
import com.erp.entity.qms.LotItem;

import java.util.List;
import java.util.Map;

/**
 * NCR 不合格品状态机（spec ncr-management，tasks 6.1~6.6，2.5.3 / 6.5）。
 *
 * 状态机：CREATED →（评审选定处置）RETURNING/SORTING/REWORKING/CONCESSION
 *        →（处置执行确认，凭证齐全）DISPOSED →（CAPA 有效性 + 复检合格）CLOSED；
 *        CREATED/REVIEWING 可作废 CANCELLED。
 * 冻结（design D6 两阶段）：未过账冻 GR 行（QC_FROZEN），已入库冻库存（AVAILABLE→QC）。
 * 时限：评审分级 Critical 4h / Major 24h / Minor 48h，超 1 倍升质量经理、2 倍升质量总监；
 *      超 NCR_CLOSE_DAYS=30 天未关闭升级质量总监 + 抄送采购经理 + 冻结供应商绩效发布，
 *      之后每 7 天重复提醒，关闭解除（BR-4.2-27 / BR-4.12-25/30）。
 */
public interface NcrService {

    /**
     * 判不合格同事务生成 NCR（tasks 6.1）：
     * 严重度自动分级（CTQ/安全法规 → CRITICAL）+ 两阶段冻结 + 分级评审时限 + CREATE 日志。
     * 同批已有未作废 NCR 时幂等返回既有单据。
     */
    Ncr createFromLot(InspectionLot lot, int defectCount, int ac, int re, List<LotItem> items);

    /**
     * 报工来源生产 NCR（change add-reporting-management，design D6，ncr-management spec 零改动——
     * 本方法为实现层扩展触发源）：refType=WORKORDER、refId=moId#opSeq#rptNo（良率超差为 #YIELD）、
     * 冻结语义 = {@code freezeScope=WORKORDER}（挡派工/完工，不写库存冻结行），
     * 关闭/作废时经 refId 回调 MoFreezeService.releaseForNcr 解冻。
     * 幂等：同 refId 已有未作废 NCR 返回既有单据。
     *
     * @param ngQty     不合格数量（严重度分级基数）
     * @param disposition 预选处置（SCRAP/REWORK/CONCESSION，可空——评审时再定）
     * @param severity  CRITICAL/MAJOR/MINOR（调用方按上下文定级；良率超差默认 MAJOR）
     */
    Ncr createForWorkOrder(String moId, Integer opSeq, String refId, String itemCode,
                           String itemName, java.math.BigDecimal ngQty, String disposition,
                           String severity, String defectDesc);

    /** 分页（状态/严重度/关键字筛选） */
    Page<Map<String, Object>> page(long current, long size, String keyword, String status, String severity);

    /**
     * 分页扩展（change rework-ncr-identification-disposition）：
     * statuses 逗号分隔多态过滤（6.5.2 在途五态）；frozenOnly=true 仅冻结中且非
     * CANCELLED/CLOSED（6.5.1 隔离清单）；withStage=true 行内附 disposeStage
     * 处置阶段推导（批量反查，实时计算不落库，design D4）。
     */
    Page<Map<String, Object>> page(long current, long size, String keyword, String status,
                                   String statuses, String severity, Boolean frozenOnly, Boolean withStage);

    /**
     * 隔离确认（6.5.1，spec 不合格品隔离确认与标识卡）：
     * 权限 INSPECTOR/QUALITY_ENG/QUALITY_MGR → 写隔离四列 + ISOLATE 日志（货位/贴标人/时间），
     * 不改状态机；重复确认覆盖留痕。
     */
    Ncr isolate(String id, String bin);

    /** 详情：NCR + 检验批快照 + 检验项 + CAPA + 关联退货/让步单号 */
    Map<String, Object> detail(String id);

    /**
     * 评审（tasks 6.2）：必须选定处置（留「待定」/空 422）；
     * 安全/法规 CTQ 禁让步（BR-4.12-26，422）。
     * CTQ 单（ctqFlag=1，rework-ncr-identification-disposition）：提交不流转——
     * 评审暂存 + DISPO_PENDING_FLAG='1' + 提交审批底座（bizType=NcrDisposition，
     * 单节点 ROLE_QUALITY_MGR，可升质量总监），通过回调 {@link #applyDispositionApproval}
     * 才流转；重复提交 422。非 CTQ 走原直接流转。
     */
    Ncr review(String id, String disposition, String opinion);

    /**
     * CTQ 处置审批通过回调（bizType=NcrDisposition）：仅 DISPO_PENDING_FLAG='1' 且
     * CREATED 才执行流转段（含退货单/SCAR 自动带出），日志记审批人；其余幂等跳过。
     */
    Ncr applyDispositionApproval(String ncrId, String approvalOpinion);

    /**
     * CTQ 处置审批驳回回调：清 DISPO_PENDING_FLAG 与处置选择，留驳回意见日志，可改选重提；幂等。
     */
    Ncr rejectDispositionApproval(String ncrId, String reason);

    /**
     * 处置方案录入（tasks 6.3）：挑选/返工方案 + 质量工程师确认。
     * 返工结构化（spec MODIFIED 处置执行确认）：reworkSteps/reworkParams/reworkCheckReq
     * 三字段必填且 ctqConfirmed=true（缺 422）；挑选可只录 plan 叙述。
     */
    Ncr planDisposition(String id, String plan);

    /** 返工结构化方案录入（三字段 + CTQ 全含勾选） */
    Ncr planDisposition(String id, String plan, String reworkSteps, String reworkParams,
                        String reworkCheckReq, Boolean ctqConfirmed);

    /**
     * 处置执行确认（tasks 6.4）：按处置方式校验凭证齐全（缺失 422）→ DISPOSED。
     * REWORK/SORT 完成后同事务生成复检批（sourceType=RECHECK）。
     */
    Ncr confirmDisposed(String id, String result);

    /** 处置确认 + COPQ 内部失败归集（costAmount 非空时同事务归集，tasks 9.6） */
    Ncr confirmDisposed(String id, String result, java.math.BigDecimal costAmount);

    /**
     * 处置确认扩展（spec MODIFIED 处置执行确认，rework-ncr-identification-disposition）：
     * 挑选四字段必填（sortTotal/sortOk/sortNg/sortHours）且 ok+ng=total（不等 422）；
     * 合格率 <50% 返回 warnings 软提示（不阻断，规格 2b「建议」口径）；
     * costAmount 落 DISPOSE_COST_AMOUNT 并归集 COPQ。
     */
    Map<String, Object> confirmDisposed(String id, String result, java.math.BigDecimal costAmount,
                                        java.math.BigDecimal sortTotal, java.math.BigDecimal sortOk,
                                        java.math.BigDecimal sortNg, java.math.BigDecimal sortHours);

    /**
     * 关闭（tasks 6.5）：处置完成 + CAPA 有效性有效（未立项/未验证 422）
     * + 挑选/返工复检已放行 → CLOSED 解冻（库存锁定还原，退货分支保持待出库）、只读归档。
     */
    Ncr close(String id, String opinion);

    /** 作废（仅 CREATED/REVIEWING，填原因；已处置/关闭不可作废）：解冻 + CANCELLED */
    Ncr cancel(String id, String reason);

    /** 操作日志（评审/处置/升级/关闭/解冻全链） */
    List<NcrLog> logs(String ncrId);

    /**
     * 让步接收驳回 → NCR 回评审（tasks 7.2）：
     * 仅 CONCESSION 态回 CREATED 并清处置选择，留驳回原因日志；其余状态幂等跳过。
     */
    Ncr reopenForReview(String ncrId, String reason);

    /** 评审超时扫描（tasks 6.2，幂等）：超 1 倍升质量经理、2 倍升质量总监，返回处理条数 */
    int sweepReviewTimeouts();

    /** 超期扫描（tasks 6.6，幂等）：超 30 天升质量总监 + 抄送采购经理 + 绩效冻结 + 每 7 天提醒 */
    int sweepOverdueEscalation();

    /**
     * 重复 NCR 升级链扫描（rework-ncr-control 4.2，spec C-4.12-16）：
     * 按物料/工序/供应商三维度分组滚动 3 月计数，count≥3 时生成 CAPA 立项待办
     * （关联全部 NCR 单号 + 维度计数明细 + 严重度上调一级建议，通知质量经理，
     * 供应商维度加通知 SQE）；不代填 CAPA 单（立项由质量工程师执行）。
     * 幂等锚：待办按「维度键 + 月窗口」去重；维度存在未关闭 CAPA 时跳过。返回处理维度数。
     */
    int sweepRepeatNcrCapaTodos();

    /**
     * 针对已放行批次创建 NCR（rework-ncr-control 6.1，spec 漏检召回评估入口）：
     * 权限 ENG/MGR/ADMIN；lot 必须 RELEASED（否则 422「请走检验判定自动建单」）；
     * 幂等——该批存在未关闭 RELEASED_LOT 手工 NCR → 422 返回既有单号；
     * 冻结复用 createFromLot 两阶段降级路径（POSTED→库存冻结 / 未过账→GR 行 / 无库存→仅标记）；
     * defectDesc 标记「放行后发现」，CREATE 日志留痕，评审分级时限照常起算。
     *
     * @param ctqFlag 可选（"1"/"0"，缺省 "0"）——漏检 CTQ 场景置 1 走 CTQ 处置审批
     */
    Ncr createForReleasedLot(String lotId, String defectItem, String defectDesc,
                             String severity, String ctqFlag);

    /**
     * 发起召回评估（rework-ncr-control 6.2，spec 6753①）：
     * 仅 refType=RELEASED_LOT 的 NCR 可发起（否则 422）；以批次号为索引调 trace-recall
     * 发起（缺陷描述携带 NCR 号），追溯单回写 SOURCE_NCR_ID 双向关联；命中既有未结案
     * 追溯单不重复建单（返回既有单供跳转）；MUST NOT 代执行冻结等重动作。
     * 返回 {traceId, traceNo, existing}。
     */
    Map<String, Object> recallEvaluation(String ncrId);
}
