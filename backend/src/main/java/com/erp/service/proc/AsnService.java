package com.erp.service.proc;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.proc.Asn;
import com.erp.entity.proc.GoodsReceipt;
import com.erp.entity.proc.GoodsReceiptLine;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * ASN 发货通知（spec asn-collaboration）：
 * 按已确认交期的 PO 创建、防超发与容差拆分、超收审批（C-4.9-07）、到货核销。
 */
public interface AsnService {

    /** 按 PO 创建 ASN（门户/代录共用；交期未确认 422、防超发 422、超容差部分触发超收审批） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 补货确认联动创建（SOURCE=REPLENISH，PO 可空，design D9） */
    Asn createReplenish(String supplierId, String supplierName, String itemCode,
                        String itemName, String unit, BigDecimal qty);

    /** 分页（内部三角色/门户按 supplierId 过滤） */
    Page<Map<String, Object>> page(long current, long size, String status,
                                   String supplierId, String keyword);

    /** 头 + 行明细（含超收与核销状态） */
    Map<String, Object> detail(String id);

    /** 过账前置校验：超收未放行/超出 ASN 可收量 → 422（posting 早期调用，纯校验不写库） */
    void assertReceiptWithin(GoodsReceipt gr, List<GoodsReceiptLine> pendingLines);

    /** 过账成功后核销：回写 RECEIVED_QTY、全收置 CLOSED、承诺-到货偏差留痕（BR-4.9-01） */
    void settle(GoodsReceipt gr, List<GoodsReceiptLine> postedLines);
}
