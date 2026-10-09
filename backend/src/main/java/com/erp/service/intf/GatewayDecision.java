package com.erp.service.intf;

import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfCredential;
import lombok.Getter;
import lombok.Setter;

/**
 * 开放入口网关决策（spec open-api-gateway）。
 * ok=false 时 filter 直接写 httpStatus + 7.1.4 统一错误体并短路，不进入业务。
 */
@Getter
@Setter
public class GatewayDecision {

    private boolean ok;
    private int httpStatus;
    /** INVALID_ARGUMENT / UNAUTHENTICATED / PERMISSION_DENIED / RATE_LIMITED / CIRCUIT_OPEN / AUDIT_UNAVAILABLE */
    private String code;
    private String message;
    private String violatedRule;
    private String requestId;
    private String caller;
    private IntfCredential credential;
    private IntfChannel channel;
    /** 审计行 ID（预写入，fail-closed 落点） */
    private String auditId;
    private boolean rateHit;
    private boolean circuitHit;
    /** 本次是否为半开探测放行的请求 */
    private boolean probe;
    /** 429 时的 Retry-After 秒数 */
    private Integer retryAfter;
    private long startMillis;

    public static GatewayDecision deny(int status, String code, String message, String requestId) {
        GatewayDecision d = new GatewayDecision();
        d.ok = false;
        d.httpStatus = status;
        d.code = code;
        d.message = message;
        d.requestId = requestId;
        return d;
    }
}
