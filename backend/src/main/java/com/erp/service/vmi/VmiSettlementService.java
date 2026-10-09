package com.erp.service.vmi;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.util.Map;

/**
 * VMI 周期结算（FR-4.2-8-4 / BR-4.2-38，spec vmi-consignment，design D7/D8）。
 * 结算单 = Σ(领用量 × 领用时点协议价)；期末价试算差率超容差 → ON_HOLD 双确认
 * （采购员 + 供应商线下登记，两签顺序不限）→ CONFIRMED；发票登记成功回写 INVOICED。
 */
public interface VmiSettlementService {

    /** 生成结算单（按供应商+期间聚合 POSTED 的 VMI 领料行；一领用行仅可归属一张结算单） */
    Map<String, Object> create(Map<String, Object> payload);

    /** 分页（供应商/状态筛选） */
    Page<Map<String, Object>> page(long current, long size, String supplierId, String status);

    /** 详情（头 + 领用明细行，供应商异议对账视图） */
    Map<String, Object> detail(String id);

    /**
     * 确认签署（design D7）：side=pm 采购员签（当前登录人）；side=supplier 供应商签
     * （body 须带 supplierConfirmBy 确认人 + way 确认方式，线下确认登记）。
     * 容差内：PM 签即 CONFIRMED；超容差（ON_HOLD）：两签齐备才 CONFIRMED。
     */
    Map<String, Object> sign(String id, Map<String, Object> body);

    /**
     * 发票登记成功回写（MODIFIED three-way-match 调用）：CONFIRMED → INVOICED。
     * supplierId 非空时校验结算单供应商一致，否则 422。
     */
    void markInvoiced(String settleId, String supplierId);
}
