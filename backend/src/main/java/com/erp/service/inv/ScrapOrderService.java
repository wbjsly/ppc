package com.erp.service.inv;

import com.erp.entity.inv.InvScrapOrder;
import com.erp.entity.inv.InvScrapOrderLine;

import java.util.List;
import java.util.Map;

/**
 * 报废出库单（4.5.4，spec scrap-order，design D5/S1 按原因分门槛）。
 */
public interface ScrapOrderService {

    /** 创建（DRAFT）：原因枚举、QUALITY 必填 NCR、行超在手 422、库龄快照固化 */
    Map<String, Object> create(InvScrapOrder head, List<InvScrapOrderLine> lines);

    /** 编辑（仅 DRAFT，整单行重建） */
    Map<String, Object> update(String id, InvScrapOrder head, List<InvScrapOrderLine> lines);

    /** 作废（仅 DRAFT，原因必填） */
    void cancel(String id, String reason);

    /** 提交三方会签（仅 STALE：技术/质量/财务一 SEQ×3 JOINT，C-4.4-14） */
    Map<String, Object> submitForApproval(String id);

    /** 简化批准（QUALITY/DAMAGE/OTHER 直批，限 WAREHOUSE/ADMIN；STALE 422 须会签） */
    Map<String, Object> approve(String id);

    /** 出库过账：会签/NCR 处置校验 + 引擎 SCRAP_OUT + 凭证一（借1901/贷1403）→ POSTED */
    Map<String, Object> post(String id);

    /** 处置核销：凭证二（借6711/贷1901）→ DISPOSED（仅 POSTED，重复 422） */
    Map<String, Object> dispose(String id);

    Map<String, Object> detail(String id);

    Map<String, Object> page(long current, long size, String status, String keyword);
}
