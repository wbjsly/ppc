package com.erp.service.qms;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.qms.CopqEntry;

import java.util.Map;

/**
 * 质量成本 COPQ（spec quality-cost，tasks 9.6~9.8，6.7）。
 *
 * 归集：四类（PREVENTION 预防 / APPRAISAL 鉴定 / INTERNAL_FAILURE 内部失败 / EXTERNAL_FAILURE 外部失败），
 *   来源 NCR 处置、退货出库、SCAR、CAPA、手工；同事务写入、来源可反查。
 * 异常（BR-4.12-39）：单笔 ≥10× 近 3 月月均 → ANOMALY_HOLD 阻断入正式报表 + 核实待办。
 * 财务确认（BR-4.12-40）：底座 CopqFinance 节点（ADMIN 代），仅 CONFIRMED 进正式报表，
 *   未确认以草稿单列。报表（BR-4.12-41/42）：外部失败占比 >30% 红标改善建议、供应商维度汇总。
 */
public interface CopqService {

    /**
     * 归集一笔成本（tasks 9.6）：category ∈ 四枚举、amount > 0；
     * 自动异常检测（近 3 月月均 ×10）。幂等保护：同 sourceType+sourceId+category 已存在则返回既有。
     */
    CopqEntry record(Map<String, Object> body);

    /** 手工录入（来源 MANUAL 或补录） */
    CopqEntry createManual(Map<String, Object> body);

    /** 提交财务确认（底座 CopqFinance 单签 ADMIN 代）；ANOMALY_HOLD 阻断 422 */
    CopqEntry submitFinance(String id);

    /** 异常核实（tasks 9.7）：pass → 回 PENDING_FINANCE 可确认；fail → 保持并记原因 */
    CopqEntry resolveAnomaly(String id, boolean pass, String remark);

    Page<Map<String, Object>> page(long current, long size, String keyword, String category, String status);

    /**
     * 报表（tasks 9.8）：正式（CONFIRMED）按四类汇总、草稿（未确认）单列、
     * 外部失败占比 >30% 红标 + 改善建议、供应商维度汇总（含未归因）。
     */
    Map<String, Object> report(String month);

    /** 按来源反查（NCR/RETURN/SCAR/CAPA） */
    java.util.List<CopqEntry> bySource(String sourceType, String sourceId);
}
