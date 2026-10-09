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

    /** 分页（状态/严重度/关键字筛选） */
    Page<Map<String, Object>> page(long current, long size, String keyword, String status, String severity);

    /** 详情：NCR + 检验批快照 + 检验项 + CAPA + 关联退货/让步单号 */
    Map<String, Object> detail(String id);

    /**
     * 评审（tasks 6.2）：必须选定处置（留「待定」/空 422）；
     * 安全/法规 CTQ 禁让步（BR-4.12-26，422）。
     */
    Ncr review(String id, String disposition, String opinion);

    /**
     * 处置方案录入（tasks 6.3）：挑选/返工方案 + 质量工程师确认。
     */
    Ncr planDisposition(String id, String plan);

    /**
     * 处置执行确认（tasks 6.4）：按处置方式校验凭证齐全（缺失 422）→ DISPOSED。
     * REWORK/SORT 完成后同事务生成复检批（sourceType=RECHECK）。
     */
    Ncr confirmDisposed(String id, String result);

    /** 处置确认 + COPQ 内部失败归集（costAmount 非空时同事务归集，tasks 9.6） */
    Ncr confirmDisposed(String id, String result, java.math.BigDecimal costAmount);

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
}
