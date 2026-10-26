package com.erp.service.fin;

import com.erp.entity.fin.InvoiceDelivery;

import java.util.List;
import java.util.Map;

/**
 * 发票交付管理（M9-2-4，FR-4.14-2-4，D6 双路登记）。
 */
public interface InvoiceDeliveryService {

    /** 销项手工登记交付（SOURCE=SALES） */
    InvoiceDelivery register(Map<String, Object> payload);

    /** 交付列表（筛选：状态/来源；含超期黄标计算） */
    List<Map<String, Object>> list(String status, String source);

    /** 标记已发送（回填时间置 SENT） */
    InvoiceDelivery markSent(String id);

    /** 重发（RETRY_COUNT+1 置 FAILED；超 SysParam DELIVERY_MAX_RETRY 422） */
    InvoiceDelivery resend(String id);
}
