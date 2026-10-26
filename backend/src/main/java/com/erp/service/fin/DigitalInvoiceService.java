package com.erp.service.fin;

import com.erp.entity.fin.DecodedRequest;
import com.erp.entity.fin.DigitalQuota;

import java.util.List;
import java.util.Map;

/**
 * 数电发票管理（M9-2-3，spec invoice-management，FR-4.14-6，离线模式）。
 * 授信额度台账 + 开票请求队列（额度 L1/幂等 422）+ 手动补开（超限拆分）+ 月末逐票勾稽。
 */
public interface DigitalInvoiceService {

    /** 额度登记（upsert：一期一行） */
    DigitalQuota saveQuota(Map<String, Object> payload);

    /** 某期额度（含可用余额） */
    Map<String, Object> quota(String period);

    /**
     * 提交开票请求（BR-4.14-36 额度不足 422；BR-4.14-37 幂等键已 ISSUED 422）
     */
    DecodedRequest submitRequest(Map<String, Object> payload);

    /** 请求队列（筛选：状态） */
    List<DecodedRequest> listRequests(String status);

    /**
     * 补开（按提交时序人工选择目标）：单张发票号；超 SysParam 限额按行金额降序
     * 拆分 N 张（BR-4.14-15，标注同一原始请求号）；扣额度；自动建交付 PENDING 行。
     */
    Map<String, Object> issue(String requestId);

    /**
     * 月末逐票勾稽（FR-4.14-6-4）：漏开/重开（同幂等键多 ISSUED）/金额差异三类清单。
     */
    Map<String, Object> reconcile(String period);
}
