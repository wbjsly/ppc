package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.FinSupplierStatement;

import java.util.Map;

/**
 * 供应商对账单与付款冻结（2.7.3，spec supplier-statement-reconciliation，C-4.2-15）。
 * 登记即与 OPEN 暂估余额比对：容差内 ACCEPTED，超容差 EXCEPTION（冻结付款）→
 * PM + 财务双签（审批底座两节点）→ CLOSED 解冻。
 */
public interface SupplierStatementService {

    /** 登记对账单（供应商、对账日期、对账金额、备注）并自动比对落状态 */
    FinSupplierStatement register(Map<String, Object> payload);

    /** 分页（供应商 / 状态 / 对账日期区间） */
    Page<Map<String, Object>> page(long current, long size, String supplierId, String status,
                                   String dateFrom, String dateTo);

    /** 详情（含比对基准、差异与双签记录） */
    Map<String, Object> detail(String id);

    /**
     * 系统匹配（spec supplier-statement-reconciliation ADDED，S-4.9-06）：
     * ERP 侧 OPEN 暂估构成（入库/领用来源）与供应商对账明细逐笔匹配，差异标红落 MATCH_RESULT。
     * payload.lines 可选：[{desc, amount}]（供应商侧明细，缺省以对账单合计作单行）。
     */
    Map<String, Object> match(String id, Map<String, Object> payload);

    /**
     * 发起双签确认（仅 EXCEPTION 需双签；审批底座 ROLE_PM → ROLE_FINANCE_MGR 两节点）。
     * 通过 → CLOSED 解冻（回调落双签字段）；驳回 → 维持 EXCEPTION 继续冻结。
     */
    Map<String, Object> confirm(String id);

    /** 冻结校验：存在未关闭差异对账单 → 422（付款/预付申请前置调用） */
    void assertNotFrozen(String supplierId);
}
