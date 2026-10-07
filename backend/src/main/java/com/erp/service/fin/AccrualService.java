package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.FinAccrual;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 应付暂估（2.7.1，spec ap-accrual）。
 * 生成（BR-4.2-30 / FR-4.2-8-3 预留）、冲回内核（手工+自动共用）、退货冲减、
 * 台账与汇总、供应商合并迁移与财务确认（BR-4.1-28/29）。
 */
public interface AccrualService {

    /**
     * GR 过账生成暂估（BR-4.2-30）：暂估单 + 借存货/贷应付暂估凭证。
     * UNIT_PRICE 为空的行跳过；全部行无单价则不生成（返回 null，仅日志）。
     * 生成失败抛异常 → 过账事务整体回滚。
     */
    FinAccrual createFromGr(GoodsReceipt gr, List<GoodsReceiptLine> postedLines);

    /**
     * VMI 物权转移预留触发点（FR-4.2-8-3，design D3/d）：按协议价生成暂估。
     * 本期 2.8 未建，无 UI 调用方，以服务接口直调（单测）覆盖；
     * `sourceType` 仅接受 VMI_TRANSFER，其余值（含 GR 手工直调、MANUAL）422 拒绝。
     */
    FinAccrual createFromVmiTransfer(String sourceType, String transferDocNo, String supplierId,
                                     String supplierName, String poNo, BigDecimal qty,
                                     BigDecimal agreementPrice);

    /**
     * 冲回内核（手工/自动共用，design D4）：条件更新 OPEN → REVERSED + 六字段留痕 + 红字冲销凭证。
     * 已冲回再次冲回 422（幂等保护）；挂起批次（MIGRATION_CONFIRMED=0）不可冲回。
     */
    FinAccrual reverse(String accrualNo, String invoiceNo, String reason, String source);

    /** 手工冲回入口（仅 ADMIN，3.4）：发票号 + 原因必填，REVERSE_SOURCE=MANUAL */
    FinAccrual reverseManual(String accrualNo, String invoiceNo, String reason);

    /**
     * 退货应付冲减（BR-4.2-34 落地，design D5）：同供应商同 PO 的 OPEN 暂估倒序
     * 累加 OFFSETTED_AMOUNT；挂起批次跳过；不足部分记应付借项凭证（借 应付账款 / 贷 存货）。
     *
     * @return 实际冲减金额（含跳过后的累计冲减值）
     */
    BigDecimal offset(String supplierId, String poNo, BigDecimal amount, String returnNo);

    /** 台账分页（筛选：供应商 / PO / 状态 / 生成日期区间） */
    Page<Map<String, Object>> page(long current, long size, String supplierId, String poNo,
                                   String status, String dateFrom, String dateTo);

    /** 行明细：按 GR_NO 反查 erp_proc_gr_line 关联链（物料/批次/数量/单价/金额/入库凭证号） */
    Map<String, Object> detail(String id);

    /** 汇总卡：暂估余额（Σ OPEN 金额−已冲减）、本月生成、本月冲回 */
    Map<String, Object> summary();

    /** 合并迁移批次财务确认（BR-4.1-29，仅 ADMIN）：置 1 并触发关联 PO/发票重跑三方匹配 */
    int confirmMigration(String batchNo);
}
