package com.erp.service.fin;

import com.erp.entity.fin.OfflineInvoice;
import com.erp.entity.fin.TaxCert;

import java.util.List;
import java.util.Map;

/**
 * 税控设备/数字证书 + 离线开票管理（tax-risk-monitoring，C-4.14-14）。
 */
public interface TaxRiskService {

    // ---------- 证书台账 ----------

    /** 登记税控设备/数字证书（缺到期日 422） */
    TaxCert registerCert(Map<String, Object> payload);

    /** 证书列表（惰性过期判定 + 距今天数） */
    List<Map<String, Object>> listCerts(String status);

    /** 更新证书（重新发行：新到期日 → 状态回 VALID，自动解阻） */
    TaxCert updateCert(String certId, Map<String, Object> payload);

    /**
     * 证书到期卡控（C-4.14-14，L3）：≤ TAX_CERT_WARNING_DAYS 预警；
     * 已过期返回通道不可用清单。
     */
    Map<String, Object> certWarnings();

    /**
     * 开票通道校验（C-4.14-14 过期阻断）：按绑定通道查证书——
     * 过期/无有效证书 → 422「通道不可用」；临期仅提示。
     */
    void assertChannelUsable(String channel);

    // ---------- 离线开票（48h 补传告警） ----------

    /** 离线开票登记（时间/原因；绑定通道过期 → 422 阻断） */
    OfflineInvoice registerOffline(Map<String, Object> payload);

    /** 补传登记（消除 L4 告警并留痕补传时间） */
    OfflineInvoice uploadOffline(String offlineId);

    /** 离线开票列表（含告警状态） */
    List<OfflineInvoice> listOffline();

    /**
     * 48h 未补传告警扫描（L4）：距 OPEN_TIME 超 OFFLINE_INVOICE_UPLOAD_HOURS
     * 且未补传 → 标记 ALERT_FLAG=1 入看板。
     */
    List<Map<String, Object>> offlineAlerts();
}
