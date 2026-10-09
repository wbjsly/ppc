package com.erp.service.fin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.fin.RedInvoice;
import com.erp.entity.fin.SalesInvoice;
import com.erp.entity.sd.InvoiceApply;
import com.erp.entity.sd.Shipment;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 销售开票（tasks 10.2~10.5，spec sales-invoicing-receivable，design D9 按次开票）。
 *
 * 链路：发货确认自动生成申请 + 同步生成应收 → 提交（税务资质 C-4.3-06 + 容差校验）
 *      → 财务审核 → 开具（外部开票系统桩 + 本地台账）→ 回写应收与 SO
 *      → 客户确认 / 异议 → 红字发票与应收红冲（D14）。
 */
public interface InvoiceService {

    /**
     * 发货确认触发（task 10.2）：同发货单一一对应生成开票申请（金额 = 该次发货额）
     * 并同步生成一笔应收与应收明细行；幂等（同发货单已生成则跳过）。
     *
     * @return 生成的申请单（已存在则返回既有单）
     */
    InvoiceApply onShipmentConfirmed(Shipment ship);

    // ---------- 3.8.1 开票触发（申请列表与状态） ----------

    Page<InvoiceApply> page(long current, long size, String status,
                            String keyword, String customerId, String soNo);

    /** 详情：申请头 + 发货明细行 + 关联发票/应收 */
    Map<String, Object> detail(String applyId);

    /** 手工调整开票金额（仅 DRAFT/RETURNED；提交时按 TOLERANCE_DEFAULT 校验差异） */
    InvoiceApply updateAmount(String applyId, BigDecimal applyAmount, String remark);

    /** 提交审核：税务资质 L1 校验（C-4.3-06）+ 金额容差校验（C-0-02/BR-4.14-13 口径） */
    InvoiceApply submit(String applyId);

    /** 财务审核：信息不一致退回修改（7.2），通过 → APPROVED */
    InvoiceApply audit(String applyId, boolean pass, String opinion);

    /**
     * 发票开具（7.3，外部开票系统按桩口径）：生成发票号与本地台账，
     * 回写应收与 SO（发票号 + 行 INVOICED_QTY + SO 状态），发布 INV.ISSUED 事件。
     */
    Map<String, Object> issue(String applyId);

    /** 客户确认（7.4）：记录确认人与时间 */
    InvoiceApply confirm(String applyId);

    /** 客户异议（7.4 异常分支）：记录异议说明，支持协商或转红字 */
    InvoiceApply dispute(String applyId, String note);

    /**
     * 红字发票与应收红冲（task 10.5，D14）：生成红字发票台账（关联原票与退货单），
     * 同步冲减原应收余额（AMOUNT − RED − PAID），余额进入核销匹配池。
     */
    RedInvoice redInvoice(String originInvoiceId, String returnId, String returnNo,
                          BigDecimal amount, String reason);

    // ---------- 销项发票台账 ----------

    Page<SalesInvoice> invoicePage(long current, long size, String status,
                                   String keyword, String customerId);

    SalesInvoice getInvoice(String id);
}
