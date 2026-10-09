package com.erp.service.intf;

import com.erp.entity.intf.IntfCredential;

import javax.servlet.http.HttpServletRequest;

/**
 * 开放调用入口网关（spec open-api-gateway，design D1/D2）。
 * evaluate 覆盖：审计预写入 → 时间戳 → 凭证 → 签名 → 限流 → 熔断；
 * complete 在业务执行后回写审计并推进熔断/半开探测状态机。
 */
public interface OpenApiGatewayService {

    /** 顺序校验并返回决策；decision.ok=false 时 filter 必须短路 */
    GatewayDecision evaluate(HttpServletRequest request, String path, byte[] body);

    /** 业务执行完成后的回写（审计响应码、熔断统计、半开探测、凭证最后使用时间） */
    void complete(GatewayDecision decision, int respCode, String errCode, long costMs);

    /** 签名串 = METHOD \n PATH \n HEX(SHA256(body))（design D1，JUnit 可测） */
    String signatureString(String method, String path, byte[] body);

    /** HMAC-SHA256 十六进制摘要（JUnit 可测） */
    String hmacHex(String signKey, String plain);

    /** 换 token 端点的 Basic 认证校验（api-credential-management 2.2） */
    boolean verifyBasic(IntfCredential credential, String authorizationHeader);

    /** 解析 X-Request-Id：缺失由系统生成（BR-4.9-3-2） */
    String resolveRequestId(HttpServletRequest request);

    /** 5 分钟窗口唯一键告警（BR-4.9-27 聚合去重；同键同窗口重复插入即静默跳过） */
    void raiseAlert(String alertKey, String metricKey, String level, String message, String partnerCode);

    /** 解除熔断（spec S-5.5-06 运维判定后恢复原限流档位） */
    void resetCircuit(com.erp.entity.intf.IntfChannel channel, String reason);

    /** 档位 → 每分钟限流值 */
    int limitForTier(String tier);
}
