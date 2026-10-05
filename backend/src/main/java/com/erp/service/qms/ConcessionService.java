package com.erp.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.qms.Concession;
import com.erp.entity.qms.ConcessionWriteoff;

import java.util.List;
import java.util.Map;

/**
 * 让步接收（spec concession-acceptance，tasks 7.1~7.6，2.5.2 / 6.4.2）。
 *
 * 链路：NCR 评审选 CONCESSION → 申请（要素必填）→ 提交双签（质量经理 + 技术负责人并行）
 *      → APPROVED → GR 行 QC_STATUS=CONCESSION（过账放行，让步量入 QC_QTY + 限制 JSON 快照）
 *      → 核销放行（有效期/累计量/使用范围逐次校验，越界 422 留 REJECTED 记录）
 *      驳回 → NCR 回评审（BR-4.12-01 双签缺一不生效）。
 */
public interface ConcessionService {

    /**
     * 申请（tasks 7.1）：原因/技术评估/风险评估/限制条件（有效期/上限/使用范围）必填，
     * 缺失 422 逐条列出；仅限本批（qty ≤ NCR/批次量）。
     * NCR 来源：关联 NCR 且该 NCR 处置=CONCESSION；安全/法规 CTQ 已在评审环节阻断。
     */
    Concession create(Map<String, Object> body);

    /** 提交双签（tasks 7.2）：底座并行双签（质量经理 + 技术负责人），DRAFT/REJECTED 可重提 */
    Concession submit(String id);

    /** 作废（仅 DRAFT，记录永久禁硬删 → CANCELLED 留痕） */
    Concession cancel(String id, String reason);

    Page<Map<String, Object>> page(long current, long size, String keyword, String status);

    /** 详情：让步单 + 审批进度 + 核销记录与剩余额度 */
    Map<String, Object> detail(String id);

    /**
     * 核销放行（tasks 7.4）：逐次校验有效期 / 累计量上限 / 使用范围，越界 422
     * 并落 REJECTED 记录（推送质量经理）；通过则扣减额度并留痕（人/时间/剩余额度）。
     */
    Map<String, Object> writeOff(Map<String, Object> body);

    /** 核销记录（含 REJECTED 越界尝试） */
    List<ConcessionWriteoff> writeoffs(String concessionId);
}
