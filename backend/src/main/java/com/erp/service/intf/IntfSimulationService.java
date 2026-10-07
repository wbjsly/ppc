package com.erp.service.intf;

import java.util.Map;

/**
 * 模拟调用方（spec open-api-gateway 2.8 / design D1）：
 * 以真实签名向 `/api/open/**` 发起本机 HTTP 调用，让 400/401/429/503/熔断在页面上真的发生。
 */
public interface IntfSimulationService {

    String MODE_OK = "OK";
    String MODE_BAD_SIGNATURE = "BAD_SIGNATURE";
    String MODE_EXPIRED_TIMESTAMP = "EXPIRED_TIMESTAMP";
    /** 连续 N 次错误签名 → 制造 5 分钟窗口高失败率，触发熔断（spec S-5.5-03） */
    String MODE_STORM = "STORM";
    /** 高频合法请求 → 突破令牌桶触发 429 与连续窗口降档（spec S-5.5-06） */
    String MODE_RATE_STORM = "RATE_STORM";

    Map<String, Object> run(String partnerCode, String mode, int count);

    /** 入口③：一键模拟伙伴推送 —— 按伙伴密钥签名后经 /api/open/edi/{type} 真实投递 */
    Map<String, Object> pushMessage(String partnerCode, String msgType, String rawJson);
}
