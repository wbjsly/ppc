package com.erp.service.impl.intf;

import com.erp.dao.intf.IntfCallLogDao;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.dao.intf.IntfCredentialDao;
import com.erp.dao.intf.IntfRateStateDao;
import com.erp.dao.intf.IntfSlaAlertDao;
import com.erp.entity.intf.IntfCallLog;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfCredential;
import com.erp.entity.intf.IntfRateState;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.service.intf.GatewayDecision;
import com.erp.util.IntfCrypto;
import com.erp.service.intf.OpenApiGatewayService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

/**
 * 开放入口网关实现（spec open-api-gateway / api-credential-management，design D1/D2）。
 *
 * 顺序（design D1）：审计预写入(fail-closed) → 时间戳窗口 → 凭证与环境 → HMAC 签名 → 令牌桶限流 → 熔断状态机。
 * 全部拒绝路径都会回写审计行，保证 400/401/429/503 也进入审计与熔断统计（BR-4.9-21 / C-5.5-09）。
 */
@Slf4j
@Service
public class OpenApiGatewayServiceImpl implements OpenApiGatewayService {

    public static final String H_SIGN = "X-Signature";
    public static final String H_KEY = "X-API-Key";
    public static final String H_TS = "X-Timestamp";
    public static final String H_REQ = "X-Request-Id";
    public static final String H_ENV = "X-Env";

    /** 半开探测放行比例：每 20 次放行 1 次 ≈ 5%（C-5.5-09） */
    private static final int PROBE_EVERY = 20;
    /** 半开探测连续成功笔数达标即恢复 */
    private static final int PROBE_OK_NEEDED = 10;
    private static final long TIMESTAMP_SKEW_MS = 5L * 60 * 1000;

    private static final DateTimeFormatter WINDOW_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final IntfChannelDao channelDao;
    private final IntfCredentialDao credentialDao;
    private final IntfCallLogDao callLogDao;
    private final IntfRateStateDao rateStateDao;
    private final IntfSlaAlertDao alertDao;

    @Value("${app.intf.rate-limit-strategic:500}") private int rateStrategic;
    @Value("${app.intf.rate-limit-normal:100}") private int rateNormal;
    @Value("${app.intf.rate-limit-new:30}") private int rateNew;
    @Value("${app.intf.circuit-break-error-rate:0.50}") private double circuitErrorRate;
    @Value("${app.intf.circuit-break-window-minutes:5}") private int circuitWindowMinutes;
    @Value("${app.intf.circuit-recover-minutes:30}") private int circuitRecoverMinutes;
    @Value("${app.intf.audit-fail-closed:true}") private boolean auditFailClosed;

    public OpenApiGatewayServiceImpl(IntfChannelDao channelDao, IntfCredentialDao credentialDao,
                                     IntfCallLogDao callLogDao, IntfRateStateDao rateStateDao,
                                     IntfSlaAlertDao alertDao) {
        this.channelDao = channelDao;
        this.credentialDao = credentialDao;
        this.callLogDao = callLogDao;
        this.rateStateDao = rateStateDao;
        this.alertDao = alertDao;
    }

    // ------------------------------------------------------------------ evaluate

    @Override
    public GatewayDecision evaluate(HttpServletRequest request, String path, byte[] body) {
        LocalDateTime now = LocalDateTime.now();
        String requestId = resolveRequestId(request);
        String method = request.getMethod();
        String apiKey = header(request, H_KEY);
        String env = header(request, H_ENV) == null ? IntfCredential.ENV_PROD : header(request, H_ENV);

        GatewayDecision d = new GatewayDecision();
        d.setRequestId(requestId);
        d.setStartMillis(System.currentTimeMillis());
        d.setCaller(apiKey == null || apiKey.trim().isEmpty() ? "anonymous" : apiKey.trim());

        // 1) 审计预写入 —— fail-closed 落点（C-5.5-03）
        String auditId = preAudit(d, method, path, env, request, body);
        if (auditId == null && auditFailClosed) {
            return deny(d, 503, "AUDIT_UNAVAILABLE",
                    "审计服务不可用，按 fail-closed 策略阻断本次调用", null);
        }
        d.setAuditId(auditId);

        // 2) 时间戳防重放（BR-4.9-17 / BR-5.5-04）
        String ts = header(request, H_TS);
        if (ts == null || ts.trim().isEmpty()) {
            return deny(d, 400, "INVALID_ARGUMENT", "请求缺少 X-Timestamp", null);
        }
        Long tsMs = parseTimestamp(ts.trim());
        if (tsMs == null) {
            return deny(d, 400, "INVALID_ARGUMENT", "请求时间戳格式非法", null);
        }
        if (Math.abs(System.currentTimeMillis() - tsMs) > TIMESTAMP_SKEW_MS) {
            d.setMessage("请求时间戳超出允许偏差");
            return deny(d, 400, "INVALID_ARGUMENT",
                    "请求时间戳超出允许偏差，服务端时间=" + LocalDateTime.now(), null);
        }

        // 3) 凭证解析与状态校验（BR-4.9-20：401 不降级放行）
        if (apiKey == null || apiKey.trim().isEmpty()) {
            String basicKey = basicUser(header(request, "Authorization"));
            apiKey = basicKey;
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return deny(d, 401, "UNAUTHENTICATED", "缺少 API Key", null);
        }
        d.setCaller(apiKey.trim());
        IntfCredential cred = credentialDao.selectByApiKey(apiKey.trim());
        if (cred == null) {
            return deny(d, 401, "UNAUTHENTICATED", "API Key 不存在", null);
        }
        String credStatus = cred.getStatus();
        if (IntfCredential.ST_REVOKED.equals(credStatus)) {
            return deny(d, 401, "UNAUTHENTICATED", "凭证已吊销", null);
        }
        if (IntfCredential.ST_EXPIRED.equals(credStatus)) {
            return deny(d, 401, "UNAUTHENTICATED", "凭证已过期", null);
        }
        if (IntfCredential.ST_PENDING.equals(credStatus)) {
            return deny(d, 401, "UNAUTHENTICATED", "凭证未激活", null);
        }
        if (IntfCredential.ST_DEPRECATED.equals(credStatus)
                && cred.getDeprecateUntil() != null && now.isAfter(cred.getDeprecateUntil())) {
            return deny(d, 401, "UNAUTHENTICATED", "旧凭证已过回滚窗口并自动吊销", null);
        }
        if (!cred.getEnv().equals(env)) {
            return deny(d, 401, "UNAUTHENTICATED", "凭证环境不匹配（沙箱与生产隔离）", null);
        }
        d.setCredential(cred);

        IntfChannel channel = cred.getChannelId() == null ? null : channelDao.selectById(cred.getChannelId());
        d.setChannel(channel);
        // 限流/熔断/审计统一按**伙伴**计（同一伙伴多套凭证灰度期共享配额，SOP-5.5-B 步骤4）
        d.setCaller(cred.getPartnerCode());

        // 4) 认证与签名（换 token 走 Basic 口令，其余走 HMAC，C-5.5-05）
        boolean isTokenPath = path.startsWith("/api/open/token");
        if (isTokenPath) {
            if (!verifyBasic(cred, header(request, "Authorization"))) {
                securityEvent(d, "凭证口令错误");
                return deny(d, 401, "UNAUTHENTICATED", "API Key 或 Secret 错误", null);
            }
        } else {
            String sign = header(request, H_SIGN);
            if (sign == null || sign.trim().isEmpty()) {
                securityEvent(d, "缺失报文签名");
                return deny(d, 401, "UNAUTHENTICATED", "缺少 X-Signature 报文签名", null);
            }
            String expected = hmacHex(cred.getSignKey(), signatureString(method, path, body));
            if (!expected.equalsIgnoreCase(sign.trim())) {
                securityEvent(d, "HMAC 签名不匹配");
                return deny(d, 401, "UNAUTHENTICATED", "报文签名校验失败", null);
            }
        }

        // 5) 令牌桶限流（C-4.9-01 / BR-4.9-18）
        int limit = cred.getLimitPerMin() == null ? rateNormal : cred.getLimitPerMin();
        int capacity = (int) Math.ceil(limit * 1.5);
        String window = now.format(WINDOW_FMT);
        try {
            rateStateDao.bump(UUID.randomUUID().toString(), d.getCaller(), window, 1, limit, capacity);
            IntfRateState st = rateStateDao.selectWindow(d.getCaller(), window);
            if (st != null && st.getHitCount() != null && st.getHitCount() > st.getBucketCapacity()) {
                rateStateDao.markOver(d.getCaller(), window);
                d.setRateHit(true);
                Integer overWindows = rateStateDao.countOverWindows(d.getCaller(), window,
                        now.minusMinutes(1).format(WINDOW_FMT), now.minusMinutes(2).format(WINDOW_FMT));
                if (overWindows != null && overWindows >= 3) {
                    downgrade(cred, channel, d);
                }
                int retryAfter = 60 - now.getSecond();
                d.setRetryAfter(retryAfter <= 0 ? 1 : retryAfter);
                return deny(d, 429, "RATE_LIMITED",
                        "超过限流档位 " + limit + " 次/分钟（桶容量 " + capacity + "）", null);
            }
        } catch (Exception ex) {
            log.warn("rate state update failed: {}", ex.getMessage());
        }

        // 6) 熔断状态机（BR-5.5-07 / C-5.5-09）
        if (channel != null) {
            String state = channel.getCircuitState();
            if (IntfChannel.CIRCUIT_OPEN.equals(state)) {
                LocalDateTime recoverAt = channel.getCircuitRecoverAt();
                if (recoverAt != null && !now.isBefore(recoverAt)) {
                    channel.setCircuitState(IntfChannel.CIRCUIT_HALF_OPEN);
                    channel.setProbeHits(0);
                    channel.setCircuitProbeOk(0);
                    channelDao.updateById(channel);
                    state = IntfChannel.CIRCUIT_HALF_OPEN;
                } else {
                    d.setCircuitHit(true);
                    return deny(d, 503, "CIRCUIT_OPEN",
                            "通道半熔断中，" + (recoverAt == null ? "等待恢复" : recoverAt + " 后进入半开探测"), null);
                }
            }
            if (IntfChannel.CIRCUIT_HALF_OPEN.equals(state)) {
                int hits = (channel.getProbeHits() == null ? 0 : channel.getProbeHits()) + 1;
                channel.setProbeHits(hits);
                channelDao.updateById(channel);
                if (hits % PROBE_EVERY != 0) {
                    d.setCircuitHit(true);
                    return deny(d, 503, "CIRCUIT_OPEN", "半开探测中，按 5% 比例放行", null);
                }
                d.setProbe(true);
            }
        }

        d.setOk(true);
        return d;
    }

    // ------------------------------------------------------------------ complete

    @Override
    public void complete(GatewayDecision d, int respCode, String errCode, long costMs) {
        LocalDateTime now = LocalDateTime.now();
        // 审计回写（回写失败仅告警：响应已产生，不可撤回）
        if (d.getAuditId() != null) {
            try {
                IntfCallLog row = callLogDao.selectById(d.getAuditId());
                if (row != null) {
                    row.setCaller(d.getCaller());
                    row.setRespCode(respCode);
                    row.setErrCode(errCode);
                    row.setCostMs((int) costMs);
                    row.setRateHit(d.isRateHit());
                    row.setCircuitHit(d.isCircuitHit());
                    callLogDao.updateById(row);
                }
            } catch (Exception ex) {
                log.warn("audit post-write failed: {}", ex.getMessage());
                raiseAlert("AUDIT_WRITE_FAIL", "AUDIT", IntfSlaAlert.LVL_WARNING,
                        "审计日志回写失败：" + ex.getMessage(), d.getCredential() == null ? null : d.getCredential().getPartnerCode());
            }
        }
        if (d.getCredential() != null) {
            try {
                IntfCredential c = credentialDao.selectById(d.getCredential().getId());
                if (c != null) {
                    c.setLastUsedAt(now);
                    credentialDao.updateById(c);
                }
            } catch (Exception ignored) {
                // 最后使用时间非关键路径
            }
        }

        IntfChannel ch = d.getChannel();
        if (ch == null) {
            return;
        }
        ch = channelDao.selectById(ch.getId());
        if (ch == null) {
            return;
        }

        if (d.isProbe()) {
            if (respCode < 400) {
                int ok = (ch.getCircuitProbeOk() == null ? 0 : ch.getCircuitProbeOk()) + 1;
                ch.setCircuitProbeOk(ok);
                if (ok >= PROBE_OK_NEEDED) {
                    resetCircuit(ch, "半开探测连续 " + PROBE_OK_NEEDED + " 笔成功，恢复 CLOSED");
                } else {
                    channelDao.updateById(ch);
                }
            } else {
                trip(ch, "半开探测失败，重新熔断");
            }
            return;
        }

        if (respCode >= 400 && IntfChannel.CIRCUIT_CLOSED.equals(ch.getCircuitState())) {
            try {
                // 解封后的窗口只统计解封之后的调用，避免被此前故障窗口立刻重新熔断
                LocalDateTime from = now.minusMinutes(circuitWindowMinutes);
                if (ch.getCircuitSince() != null && ch.getCircuitSince().isAfter(from)) {
                    from = ch.getCircuitSince();
                }
                String caller = d.getCaller();
                int total = callLogDao.countFrom(caller, from);
                int failed = callLogDao.countFailedFrom(caller, from);
                if (total >= 100 && failed > total * circuitErrorRate) {
                    trip(ch, "5 分钟窗口失败率 " + failed + "/" + total + " 超过阈值 " + circuitErrorRate);
                }
            } catch (Exception ex) {
                log.warn("circuit stat failed: {}", ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 熔断与降档

    /** 触发熔断：OPEN + 30 分钟后进入半开（C-5.5-09） */
    private void trip(IntfChannel ch, String reason) {
        LocalDateTime now = LocalDateTime.now();
        ch.setCircuitState(IntfChannel.CIRCUIT_OPEN);
        ch.setCircuitSince(now);
        ch.setCircuitRecoverAt(now.plusMinutes(circuitRecoverMinutes));
        ch.setCircuitProbeOk(0);
        ch.setProbeHits(0);
        if (ch.getCircuitTripDay() == null || !ch.getCircuitTripDay().equals(now.toLocalDate())) {
            ch.setCircuitTripDay(now.toLocalDate());
            ch.setCircuitTripCount(1);
        } else {
            ch.setCircuitTripCount((ch.getCircuitTripCount() == null ? 0 : ch.getCircuitTripCount()) + 1);
        }
        channelDao.updateById(ch);
        log.warn("channel {} tripped: {}", ch.getPartnerCode(), reason);
        raiseAlert("CIRCUIT:" + ch.getPartnerCode(), "CIRCUIT_BREAK", IntfSlaAlert.LVL_CRITICAL,
                reason, ch.getPartnerCode());
        if (ch.getCircuitTripCount() != null && ch.getCircuitTripCount() >= 3) {
            raiseAlert("CIRCUIT_ESCALATE:" + ch.getPartnerCode(), "CIRCUIT_BREAK", IntfSlaAlert.LVL_EMERGENCY,
                    "同一接口 24 小时内熔断 " + ch.getCircuitTripCount() + " 次，升级至架构委员会", ch.getPartnerCode());
        }
    }

    /** 半开探测恢复正常 */
    public void resetCircuit(IntfChannel ch, String reason) {
        ch.setCircuitState(IntfChannel.CIRCUIT_CLOSED);
        ch.setCircuitRecoverAt(null);
        ch.setCircuitProbeOk(0);
        ch.setProbeHits(0);
        channelDao.updateById(ch);
        // 显式置 NULL 并记解封时刻（updateById 忽略 null）
        channelDao.clearCircuit(ch.getId(), LocalDateTime.now());
        log.info("channel {} circuit closed: {}", ch.getPartnerCode(), reason);
    }

    /** 连续 3 个统计窗口均触发 429 → 自动降档至最低档（BR-4.9-19 / BR-5.5-06） */
    private void downgrade(IntfCredential cred, IntfChannel channel, GatewayDecision d) {
        if (IntfChannel.TIER_LOWEST.equals(cred.getRateTier())) {
            return;
        }
        String from = cred.getRateTier();
        cred.setRateTier(IntfChannel.TIER_LOWEST);
        cred.setLimitPerMin(rateNew);
        credentialDao.updateById(cred);
        if (channel != null) {
            channel.setRateTier(IntfChannel.TIER_LOWEST);
            channel.setLimitPerMin(rateNew);
            channel.setDowngradeAt(LocalDateTime.now());
            channel.setDowngradeFrom(from);
            channelDao.updateById(channel);
        }
        log.warn("caller {} downgraded {} -> LOWEST ({} 次/分钟)", d.getCaller(), from, rateNew);
        raiseAlert("RATE_DOWNGRADE:" + d.getCaller(), "RATE_LIMIT", IntfSlaAlert.LVL_WARNING,
                "连续 3 个统计窗口触发 429，限流档位由 " + from + " 自动降档至 LOWEST（" + rateNew + " 次/分钟）",
                channel == null ? null : channel.getPartnerCode());
    }

    // ------------------------------------------------------------------ 审计与告警

    /** 审计预写入；返回 null 表示写入失败（fail-closed 时调用方须 503） */
    private String preAudit(GatewayDecision d, String method, String path, String env,
                            HttpServletRequest request, byte[] body) {
        try {
            IntfCallLog row = new IntfCallLog();
            row.setCaller(d.getCaller());
            row.setApiPath(path);
            row.setHttpMethod(method);
            row.setReqId(d.getRequestId());
            row.setRespCode(0);
            row.setCostMs(0);
            row.setRateHit(false);
            row.setCircuitHit(false);
            row.setEnv(env);
            row.setIp(clientIp(request));
            row.setUserAgent(truncate(header(request, "User-Agent"), 255));
            row.setParamSummary(digest(body));
            row.setCallAt(LocalDateTime.now());
            callLogDao.insert(row);
            return row.getId();
        } catch (Exception ex) {
            log.error("audit pre-insert failed (fail-closed): {}", ex.getMessage());
            return null;
        }
    }

    /** 安全事件（签名/口令失败）记入审计错误码 + 告警（C-5.5-05） */
    private void securityEvent(GatewayDecision d, String reason) {
        log.warn("security event caller={} : {}", d.getCaller(), reason);
        if (d.getAuditId() != null) {
            try {
                IntfCallLog row = callLogDao.selectById(d.getAuditId());
                if (row != null) {
                    row.setErrCode("SECURITY_EVENT:" + reason);
                    callLogDao.updateById(row);
                }
            } catch (Exception ignored) {
                // 安全事件留痕失败不阻断拒绝动作
            }
        }
        raiseAlert("SECURITY:" + d.getCaller(), "SECURITY", IntfSlaAlert.LVL_WARNING,
                reason + "（调用方 " + d.getCaller() + "）", null);
    }

    /** 5 分钟窗口唯一键告警（BR-4.9-27 聚合去重） */
    public void raiseAlert(String alertKey, String metricKey, String level, String message, String partnerCode) {
        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime window = now.withSecond(0).withNano(0).withMinute((now.getMinute() / 5) * 5);
            IntfSlaAlert exists = new IntfSlaAlert();
            exists.setAlertKey(alertKey);
            exists.setAlertWindow(window);
            exists.setMetricKey(metricKey);
            exists.setPartnerCode(partnerCode);
            exists.setLevel(level);
            exists.setMessage(message);
            exists.setStatus(IntfSlaAlert.ST_OPEN);
            exists.setEscalationLevel(1);
            exists.setAlertAt(now);
            alertDao.insert(exists);
        } catch (Exception ex) {
            // 唯一键命中 = 同窗口已告警，聚合去重成功
        }
    }

    // ------------------------------------------------------------------ 工具

    private GatewayDecision deny(GatewayDecision d, int status, String code, String message, String rule) {
        d.setOk(false);
        d.setHttpStatus(status);
        d.setCode(code);
        d.setMessage(message);
        d.setViolatedRule(rule);
        return d;
    }

    @Override
    public String resolveRequestId(HttpServletRequest request) {
        String v = header(request, H_REQ);
        if (v == null || v.trim().isEmpty()) {
            return UUID.randomUUID().toString();
        }
        return v.trim();
    }

    @Override
    public String signatureString(String method, String path, byte[] body) {
        return IntfCrypto.signatureString(method, path, body);
    }

    @Override
    public String hmacHex(String signKey, String plain) {
        return IntfCrypto.hmacHex(signKey, plain);
    }

    @Override
    public boolean verifyBasic(IntfCredential credential, String authorizationHeader) {
        String plain = basicPass(authorizationHeader);
        if (plain == null) {
            return false;
        }
        String user = basicUser(authorizationHeader);
        if (user == null || !user.equals(credential.getApiKey())) {
            return false;
        }
        return IntfCrypto.hashSecret(credential.getSecretSalt(), plain)
                .equalsIgnoreCase(credential.getSecretHash());
    }

    /** 口令哈希（签发与校验共用，SHA-256 加盐） */
    public static String hashSecret(String salt, String secret) {
        return IntfCrypto.hashSecret(salt, secret);
    }

    private static String basicUser(String authorization) {
        String[] pair = decodeBasic(authorization);
        return pair == null ? null : pair[0];
    }

    private static String basicPass(String authorization) {
        String[] pair = decodeBasic(authorization);
        return pair == null ? null : pair[1];
    }

    private static String[] decodeBasic(String authorization) {
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8);
            int idx = decoded.indexOf(':');
            if (idx < 0) {
                return null;
            }
            return new String[]{decoded.substring(0, idx), decoded.substring(idx + 1)};
        } catch (Exception e) {
            return null;
        }
    }

    private static Long parseTimestamp(String ts) {
        try {
            if (ts.matches("\\d{10,16}")) {
                long v = Long.parseLong(ts);
                return ts.length() <= 10 ? v * 1000 : v;
            }
            return java.time.OffsetDateTime.parse(ts).toInstant().toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    private static String header(HttpServletRequest request, String name) {
        return request.getHeader(name);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** 参数摘要：仅存 body 的 SHA-256 摘要与长度，避免把密钥/敏感载荷写入审计（BR-4.9-21 脱敏） */
    private static String digest(byte[] body) {
        byte[] b = body == null ? new byte[0] : body;
        String h = sha256Hex(b);
        return "sha256=" + (h.length() > 32 ? h.substring(0, 32) + "…" : h) + " len=" + b.length;
    }

    private static String sha256Hex(byte[] data) {
        return IntfCrypto.sha256Hex(data);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** 供 SLA 计算使用：返回限流档位对应的默认值 */
    public int limitForTier(String tier) {
        if (IntfChannel.TIER_STRATEGIC.equals(tier)) {
            return rateStrategic;
        }
        if (IntfChannel.TIER_NEW.equals(tier) || IntfChannel.TIER_LOWEST.equals(tier)) {
            return rateNew;
        }
        return rateNormal;
    }

    /** 告警实际值/阈值写入用 */
    public static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v);
    }
}
