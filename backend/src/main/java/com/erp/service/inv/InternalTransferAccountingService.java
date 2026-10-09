package com.erp.service.inv;

import com.erp.entity.inv.InvTransferOrder;

import java.util.Map;

/**
 * 跨法人调拨内部核算（F1 凭证 + F2 往来 + F3 内部发票，spec internal-transfer-accounting）。
 * 单法人（CROSS_LE=0）调用全部为幂等空操作；调用方（TransferOrderService）在同事务内按
 * 税检 → 引擎 → 凭证 → 发票顺序接入（design D4）。
 */
public interface InternalTransferAccountingService {

    /** L1 税务资质校验：双方 USCC + LOCAL_TAX_NO 非空，缺失 422（须在引擎过账前调用） */
    void checkTaxQualified(InvTransferOrder order);

    /** 出库段：内部销售凭证（借 1461 调拨在途 / 贷 1403）+ 内部销售发票（OUT） */
    void onOutPosted(InvTransferOrder order);

    /** 入库段：内部采购凭证（借 1403 / 贷 1461）+ 内部采购发票（IN）+ 自动配对核销 */
    void onInPosted(InvTransferOrder order);

    /** 关闭前核销校验：在途凭证成对 + 票对均 SETTLED；返回 {settled, pending:[...]} */
    Map<String, Object> settlementCheck(InvTransferOrder order);

    /** F2 内部往来视图：ISSUED 未核销按法人对+方向汇总（行含 outLe/inLe/direction/issuedCount/issuedAmount） */
    Map<String, Object> receivableView();

    /** F2 逐单下钻：某法人对+方向的内部发票明细（按调拨单号核销留痕） */
    Map<String, Object> receivableDetail(String outLe, String inLe, String direction, String status);
}
