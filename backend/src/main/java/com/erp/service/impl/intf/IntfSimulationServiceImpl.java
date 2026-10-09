package com.erp.service.impl.intf;

import com.erp.dao.intf.IntfCredentialDao;
import com.erp.entity.intf.IntfCredential;
import com.erp.service.intf.IntfSimulationService;
import com.erp.service.intf.OpenApiGatewayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 本机 HTTP 自调用，真实经过 OpenApiAuthFilter（签名/时间戳/限流/熔断/审计全链路）。 */
@Slf4j
@Service
public class IntfSimulationServiceImpl implements IntfSimulationService {

    private final IntfCredentialDao credentialDao;
    private final OpenApiGatewayService gateway;

    @Value("${server.port:8090}")
    private int port;

    public IntfSimulationServiceImpl(IntfCredentialDao credentialDao, OpenApiGatewayService gateway) {
        this.credentialDao = credentialDao;
        this.gateway = gateway;
    }

    @Override
    public Map<String, Object> run(String partnerCode, String mode, int count) {
        IntfCredential cred = pickCredential(partnerCode);
        if (cred == null) {
            throw new IllegalArgumentException("该伙伴没有可用凭证，无法模拟调用");
        }
        int n = Math.max(1, Math.min(count, 500));
        String path = "/api/open/ping";
        String body = "{}";

        Map<String, Integer> statuses = new LinkedHashMap<>();
        String sampleBody = null;
        int sampleStatus = 0;

        for (int i = 0; i < n; i++) {
            long ts = System.currentTimeMillis();
            String sign = gateway.hmacHex(cred.getSignKey(), gateway.signatureString("POST", path,
                    body.getBytes(StandardCharsets.UTF_8)));
            if (IntfSimulationService.MODE_BAD_SIGNATURE.equals(mode)
                    || IntfSimulationService.MODE_STORM.equals(mode)) {
                sign = "deadbeef".repeat(8);
            }
            if (IntfSimulationService.MODE_EXPIRED_TIMESTAMP.equals(mode)) {
                ts = System.currentTimeMillis() - 10 * 60 * 1000L;
            }
            int status = post(path, body, cred.getApiKey(), ts, sign, cred.getEnv());
            statuses.merge(String.valueOf(status), 1, Integer::sum);
            if (sampleStatus == 0) {
                sampleStatus = status;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("partnerCode", cred.getPartnerCode());
        result.put("apiKey", cred.getApiKey());
        result.put("mode", mode);
        result.put("count", n);
        result.put("statuses", statuses);
        result.put("sampleStatus", sampleStatus);
        result.put("sampleBody", sampleBody);
        result.put("hint", hint(mode));
        return result;
    }

    @Override
    public Map<String, Object> pushMessage(String partnerCode, String msgType, String rawJson) {
        IntfCredential cred = pickCredential(partnerCode);
        if (cred == null) {
            throw new IllegalArgumentException("该伙伴没有可用凭证，无法模拟推送");
        }
        String path = "/api/open/edi/" + msgType.toUpperCase();
        String payload = "{\"raw\":" + quote(rawJson) + "}";
        byte[] body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sign = gateway.hmacHex(cred.getSignKey(),
                gateway.signatureString("POST", path, body));
        int status = invoke(path, body, cred.getApiKey(), System.currentTimeMillis(), sign, cred.getEnv());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("path", path);
        result.put("partnerCode", cred.getPartnerCode());
        result.put("hint", status >= 400 ? "开放入口返回 " + status + "，请查看报文台账与审计日志"
                : "报文已进入四级校验管线");
        return result;
    }

    private static String quote(String v) {
        if (v == null) {
            return "\"\"";
        }
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String hint(String mode) {
        switch (mode) {
            case MODE_BAD_SIGNATURE:
                return "预期 401 UNAUTHENTICATED（C-5.5-05 签名不匹配）";
            case MODE_EXPIRED_TIMESTAMP:
                return "预期 400 INVALID_ARGUMENT（BR-4.9-17 时间戳超窗）";
            case MODE_STORM:
                return "连续错误签名制造高失败率，5 分钟窗口内失败率>50% 且≥100 笔将触发熔断（C-5.5-09）";
            case MODE_RATE_STORM:
                return "高频合法请求突破令牌桶，预期 429 + Retry-After，连续 3 窗口将自动降档（BR-4.9-19）";
            default:
                return "预期 200（签名、时间戳、限流、熔断全部通过）";
        }
    }

    private int post(String path, String body, String apiKey, long ts, String sign, String env) {
        return invoke(path, body.getBytes(StandardCharsets.UTF_8), apiKey, ts, sign, env);
    }

    /** 真实发起本机 HTTP 调用（字节体可复用，供报文推送签名一致） */
    private int invoke(String path, byte[] body, String apiKey, long ts, String sign, String env) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("X-API-Key", apiKey);
            conn.setRequestProperty("X-Timestamp", String.valueOf(ts));
            conn.setRequestProperty("X-Signature", sign);
            conn.setRequestProperty("X-Request-Id", UUID.randomUUID().toString());
            conn.setRequestProperty("X-Env", env);
            conn.getOutputStream().write(body);
            conn.getOutputStream().flush();
            int status = conn.getResponseCode();
            // 读取并丢弃响应体，保证连接可复用
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                in.readAllBytes();
                in.close();
            }
            return status;
        } catch (Exception e) {
            log.warn("simulate call failed: {}", e.getMessage());
            return -1;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private IntfCredential pickCredential(String partnerCode) {
        if (partnerCode != null && !partnerCode.trim().isEmpty()) {
            for (IntfCredential c : credentialDao.selectActiveByPartner(partnerCode.trim())) {
                if (IntfCredential.ST_ACTIVE.equals(c.getStatus())) {
                    return c;
                }
            }
            return null;
        }
        throw new IllegalArgumentException("必须指定伙伴编码 partnerCode");
    }
}
