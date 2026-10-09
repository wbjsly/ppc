package com.erp.service.impl.intf;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfCallLogDao;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.dao.intf.IntfCredentialDao;
import com.erp.dao.intf.IntfRateStateDao;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfCredential;
import com.erp.entity.intf.IntfSlaAlert;
import com.erp.service.intf.CredentialService;
import com.erp.service.intf.OpenApiGatewayService;
import com.erp.util.IntfCrypto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API 凭证生命周期（spec api-credential-management，SOP-5.5-B 六步）。
 * 明文口令只在签发响应与一次性链接下载两处出现，之后由 PENDING_SECRET 承载并在确认/超期后清空。
 */
@Slf4j
@Service
public class CredentialServiceImpl implements CredentialService {

    private final IntfCredentialDao credentialDao;
    private final IntfChannelDao channelDao;
    private final IntfRateStateDao rateStateDao;
    private final IntfCallLogDao callLogDao;
    private final OpenApiGatewayService gateway;

    @Value("${app.intf.api-key-rotate-days:90}") private int rotateDays;
    @Value("${app.intf.link-expire-minutes:10}") private int linkMinutes;
    @Value("${app.intf.deprecate-keep-days:7}") private int deprecateDays;
    @Value("${app.intf.gray-observe-hours:24}") private int grayHours;

    public CredentialServiceImpl(IntfCredentialDao credentialDao, IntfChannelDao channelDao,
                                 IntfRateStateDao rateStateDao, IntfCallLogDao callLogDao,
                                 OpenApiGatewayService gateway) {
        this.credentialDao = credentialDao;
        this.channelDao = channelDao;
        this.rateStateDao = rateStateDao;
        this.callLogDao = callLogDao;
        this.gateway = gateway;
    }

    // ---------------------------------------------------------------- 签发

    @Override
    public Map<String, Object> issue(String partnerCode, String scope, String tier, String env,
                                     Integer ttlDays, String remark) {
        IntfChannel channel = channelDao.selectByPartnerCode(partnerCode);
        if (channel == null) {
            throw new ServiceException(422, "该伙伴尚未登记通道，请先在接入治理中登记接口通道");
        }
        int active = 0;
        for (IntfCredential c : credentialDao.selectActiveByPartner(partnerCode)) {
            if (IntfCredential.ST_ACTIVE.equals(c.getStatus())) {
                active++;
            }
        }
        if (active >= 2) {
            // C-5.5-07 / SOP-5.5-B 步骤1：同调用方 ACTIVE 凭证不得超过 2 套
            throw new ServiceException(422, "同一调用方 ACTIVE 凭证不得超过 2 套，请先吊销旧凭证");
        }
        if (scope == null || scope.trim().isEmpty()) {
            throw new ServiceException(400, "Scope 未绑定，要求补全后方可签发");
        }

        String envUse = env == null || env.trim().isEmpty() ? IntfCredential.ENV_SANDBOX : env.trim().toUpperCase();
        if (!IntfCredential.ENV_SANDBOX.equals(envUse) && !IntfCredential.ENV_PROD.equals(envUse)) {
            throw new ServiceException(400, "ENV 只能是 SANDBOX 或 PROD");
        }
        String tierUse = tier == null || tier.trim().isEmpty() ? channel.getRateTier() : tier.trim().toUpperCase();
        int ttl = ttlDays == null || ttlDays <= 0 ? rotateDays : ttlDays;

        String salt = IntfCrypto.newSalt();
        String secret = IntfCrypto.randomSecret();
        String signKey = IntfCrypto.randomToken();

        IntfCredential cred = new IntfCredential();
        cred.setChannelId(channel.getId());
        cred.setPartnerCode(partnerCode);
        cred.setApiKey(IntfCrypto.randomApiKey());
        cred.setSecretHash(IntfCrypto.hashSecret(salt, secret));
        cred.setSecretSalt(salt);
        cred.setSignKey(signKey);
        cred.setScope(scope.trim());
        cred.setEnv(envUse);
        cred.setRateTier(tierUse);
        cred.setLimitPerMin(gateway.limitForTier(tierUse));
        cred.setStatus(IntfCredential.ST_ACTIVE);
        cred.setIssueAt(LocalDateTime.now());
        cred.setExpireAt(LocalDateTime.now().plusDays(ttl));
        cred.setRemind30(false);
        cred.setRemind7(false);
        cred.setRemind1(false);
        cred.setIssuedBy(currentUser());
        cred.setPendingSecret(secret);
        cred.setRemark(remark);
        credentialDao.insert(cred);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", cred.getId());
        result.put("apiKey", cred.getApiKey());
        result.put("secret", secret);           // 仅本次响应返回
        result.put("signKey", signKey);         // 对称签名密钥，仅本次返回
        result.put("scope", cred.getScope());
        result.put("env", cred.getEnv());
        result.put("rateTier", cred.getRateTier());
        result.put("limitPerMin", cred.getLimitPerMin());
        result.put("expireAt", cred.getExpireAt());
        result.put("plainTextOnce", "明文口令仅本次展示，请立即保存");
        return result;
    }

    // ---------------------------------------------------------------- 分发与确认

    @Override
    public Map<String, Object> createLink(String credId) {
        IntfCredential cred = require(credId);
        String token = IntfCrypto.randomToken();
        cred.setDownloadToken(token);
        cred.setDownloadTokenAt(LocalDateTime.now());
        credentialDao.updateById(cred);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("credId", cred.getId());
        result.put("token", token);
        result.put("url", "/credential/download?token=" + token);
        result.put("expireAt", LocalDateTime.now().plusMinutes(linkMinutes));
        result.put("expireMinutes", linkMinutes);
        return result;
    }

    @Override
    public Map<String, Object> download(String token, String ip, String userAgent) {
        IntfCredential cred = findByToken(token);
        cred.setDownloadAt(LocalDateTime.now());
        cred.setDownloadIp(ip);
        cred.setDownloadUa(userAgent);
        credentialDao.updateById(cred);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("apiKey", cred.getApiKey());
        result.put("secret", cred.getPendingSecret());
        result.put("signKey", cred.getSignKey());
        result.put("env", cred.getEnv());
        result.put("scope", cred.getScope());
        return result;
    }

    @Override
    public Map<String, Object> ack(String token) {
        IntfCredential cred = findByToken(token);
        cred.setAckAt(LocalDateTime.now());
        credentialDao.updateById(cred);
        credentialDao.clearSecretAndToken(cred.getId());   // 显式清空（updateById 忽略 null）
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("credId", cred.getId());
        result.put("ackAt", cred.getAckAt());
        result.put("message", "已接收确认，临时明文口令已清除");
        return result;
    }

    // ---------------------------------------------------------------- 轮换

    @Override
    public Map<String, Object> rotateStart(String oldCredId) {
        IntfCredential old = require(oldCredId);
        if (!IntfCredential.ST_ACTIVE.equals(old.getStatus())) {
            throw new ServiceException(422, "仅 ACTIVE 凭证可发起计划内轮换");
        }
        int active = 0;
        for (IntfCredential c : credentialDao.selectActiveByPartner(old.getPartnerCode())) {
            if (IntfCredential.ST_ACTIVE.equals(c.getStatus())) {
                active++;
            }
        }
        if (active >= 2) {
            throw new ServiceException(422, "同一调用方 ACTIVE 凭证不得超过 2 套，请先吊销旧凭证");
        }

        String salt = IntfCrypto.newSalt();
        String secret = IntfCrypto.randomSecret();

        IntfCredential fresh = new IntfCredential();
        fresh.setChannelId(old.getChannelId());
        fresh.setPartnerCode(old.getPartnerCode());
        fresh.setApiKey(IntfCrypto.randomApiKey());
        fresh.setSecretHash(IntfCrypto.hashSecret(salt, secret));
        fresh.setSecretSalt(salt);
        fresh.setSignKey(IntfCrypto.randomToken());
        fresh.setScope(old.getScope());
        fresh.setEnv(old.getEnv());
        fresh.setRateTier(old.getRateTier());
        fresh.setLimitPerMin(old.getLimitPerMin());
        fresh.setStatus(IntfCredential.ST_ACTIVE);
        fresh.setIssueAt(LocalDateTime.now());
        fresh.setExpireAt(LocalDateTime.now().plusDays(rotateDays));
        fresh.setGrayFrom(LocalDateTime.now());          // 灰度观察起点
        fresh.setOldCredId(old.getId());
        fresh.setIssuedBy(currentUser());
        fresh.setPendingSecret(secret);
        credentialDao.insert(fresh);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("newCredId", fresh.getId());
        result.put("apiKey", fresh.getApiKey());
        result.put("secret", secret);
        result.put("signKey", fresh.getSignKey());
        result.put("grayObserveHours", grayHours);
        result.put("deprecateKeepDays", deprecateDays);
        result.put("message", "新凭证已启用并进入灰度观察；观察期满后旧凭证自动置 DEPRECATED，7 天回滚窗口后自动吊销");
        return result;
    }

    // ---------------------------------------------------------------- 吊销与追溯

    @Override
    public void revoke(String credId, String reason) {
        IntfCredential cred = require(credId);
        if (IntfCredential.ST_REVOKED.equals(cred.getStatus())) {
            return;
        }
        cred.setStatus(IntfCredential.ST_REVOKED);
        cred.setRevokeBy(currentUser());
        cred.setRevokeAt(LocalDateTime.now());
        cred.setRevokeReason(reason == null ? "" : reason);
        credentialDao.updateById(cred);
        credentialDao.clearSecretAndToken(cred.getId());
        // 令牌桶清零：3 秒内在途请求全部 401（BR-5.5-15）
        rateStateDao.clearBucket(cred.getPartnerCode());
        gateway.raiseAlert("CRED_REVOKE:" + cred.getApiKey(), "CREDENTIAL", IntfSlaAlert.LVL_CRITICAL,
                "凭证紧急吊销：" + cred.getApiKey() + "，原因=" + reason, cred.getPartnerCode());
        log.warn("credential {} revoked by {}: {}", cred.getApiKey(), currentUser(), reason);
    }

    @Override
    public Map<String, Object> trace(String partnerCode) {
        LocalDateTime from = LocalDateTime.now().minusDays(30);
        List<Map<String, Object>> calls = new ArrayList<>();
        Map<String, Integer> ipCount = new LinkedHashMap<>();
        int total = 0;
        // 近 30 天调用按 IP 归集（SOP-5.5-B 步骤6）
        for (Map<String, Object> row : callLogDao.traceRows(partnerCode, from)) {
            total++;
            String ip = String.valueOf(row.get("ip"));
            ipCount.merge(ip, 1, Integer::sum);
            if (calls.size() < 200) {
                calls.add(row);
            }
        }
        boolean suspicious = ipCount.size() >= 3;
        String conclusion = suspicious ? "存在异常调用" : "不存在异常调用";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("partnerCode", partnerCode);
        result.put("from", from);
        result.put("totalCalls", total);
        result.put("distinctIps", ipCount.size());
        result.put("ipBreakdown", ipCount);
        result.put("conclusion", conclusion);
        result.put("samples", calls);
        if (suspicious) {
            gateway.raiseAlert("CRED_TRACE:" + partnerCode, "SECURITY", IntfSlaAlert.LVL_CRITICAL,
                    "凭证近 30 天出现 " + ipCount.size() + " 个来源 IP，疑似泄露，需吊销重签", partnerCode);
        }
        return result;
    }

    // ---------------------------------------------------------------- 到期治理

    @Override
    public int sweepExpiry() {
        LocalDateTime now = LocalDateTime.now();
        int handled = 0;

        // 1) 到期自动 EXPIRED（BR-5.5-14 / C-5.5-10）
        for (IntfCredential c : credentialDao.selectDue(now)) {
            if (c.getExpireAt() != null && !now.isBefore(c.getExpireAt())
                    && !IntfCredential.ST_REVOKED.equals(c.getStatus())) {
                c.setStatus(IntfCredential.ST_EXPIRED);
                credentialDao.updateById(c);
                credentialDao.clearPendingSecret(c.getId());
                handled++;
            }
        }
        // 2) 30/7/1 日分级提醒
        List<IntfCredential> soon = credentialDao.selectDue(now.plusDays(30));
        for (IntfCredential c : soon) {
            if (c.getExpireAt() == null || IntfCredential.ST_EXPIRED.equals(c.getStatus())
                    || IntfCredential.ST_REVOKED.equals(c.getStatus())) {
                continue;
            }
            long days = java.time.Duration.between(now, c.getExpireAt()).toDays();
            boolean changed = false;
            if (days <= 1 && !Boolean.TRUE.equals(c.getRemind1())) {
                c.setRemind1(true);
                changed = true;
                gateway.raiseAlert("CRED_DUE_1:" + c.getApiKey(), "CREDENTIAL", IntfSlaAlert.LVL_CRITICAL,
                        "凭证 " + c.getApiKey() + " 已到期前 1 日未轮换，升级通知集成架构师", c.getPartnerCode());
            } else if (days <= 7 && !Boolean.TRUE.equals(c.getRemind7())) {
                c.setRemind7(true);
                changed = true;
                gateway.raiseAlert("CRED_DUE_7:" + c.getApiKey(), "CREDENTIAL", IntfSlaAlert.LVL_WARNING,
                        "凭证 " + c.getApiKey() + " 距到期 " + days + " 天，请安排轮换", c.getPartnerCode());
            } else if (days <= 30 && !Boolean.TRUE.equals(c.getRemind30())) {
                c.setRemind30(true);
                changed = true;
                gateway.raiseAlert("CRED_DUE_30:" + c.getApiKey(), "CREDENTIAL", IntfSlaAlert.LVL_WARNING,
                        "凭证 " + c.getApiKey() + " 距到期 " + days + " 天，请安排轮换", c.getPartnerCode());
            }
            if (changed) {
                credentialDao.updateById(c);
                handled++;
            }
        }
        // 3) 轮换观察期满 → 旧凭证 DEPRECATED（保留 7 天可回滚）
        List<IntfCredential> gray = credentialDao.selectGrayObserveReady(now.minusHours(grayHours));
        for (IntfCredential fresh : gray) {
            IntfCredential old = credentialDao.selectById(fresh.getOldCredId());
            if (old != null && IntfCredential.ST_ACTIVE.equals(old.getStatus())) {
                old.setStatus(IntfCredential.ST_DEPRECATED);
                old.setDeprecateUntil(now.plusDays(deprecateDays));
                credentialDao.updateById(old);
                handled++;
            }
        }
        // 4) DEPRECATED 回滚窗口结束 → 自动吊销
        List<IntfCredential> dep = credentialDao.selectDeprecateExpired(now);
        for (IntfCredential c : dep) {
            c.setStatus(IntfCredential.ST_REVOKED);
            c.setRevokeAt(now);
            c.setRevokeReason("DEPRECATED 回滚窗口结束自动吊销");
            credentialDao.updateById(c);
            credentialDao.clearSecretAndToken(c.getId());
            rateStateDao.clearBucket(c.getPartnerCode());
            handled++;
        }
        // 5) 临时明文超期清理（7 天未确认即清除，避免明文长期留库）
        for (IntfCredential c : credentialDao.selectStalePendingSecret(now.minusDays(7))) {
            credentialDao.clearPendingSecret(c.getId());
            handled++;
        }
        return handled;
    }

    @Override
    public Page<IntfCredential> page(long current, long size, String partnerCode, String status) {
        LambdaQueryWrapper<IntfCredential> qw = new LambdaQueryWrapper<>();
        if (partnerCode != null && !partnerCode.trim().isEmpty()) {
            qw.eq(IntfCredential::getPartnerCode, partnerCode.trim());
        }
        if (status != null && !status.trim().isEmpty()) {
            qw.eq(IntfCredential::getStatus, status.trim());
        }
        qw.orderByDesc(IntfCredential::getCreateDate);
        return credentialDao.selectPage(new Page<>(current, size), qw);
    }

    // ---------------------------------------------------------------- helpers

    private IntfCredential require(String id) {
        IntfCredential cred = credentialDao.selectById(id);
        if (cred == null) {
            throw new ServiceException(404, "凭证不存在");
        }
        return cred;
    }

    private IntfCredential findByToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new ServiceException(400, "缺少一次性链接令牌");
        }
        IntfCredential cred = credentialDao.selectByToken(token.trim());
        if (cred == null) {
            throw new ServiceException(404, "链接无效或已被使用");
        }
        if (cred.getDownloadTokenAt() == null
                || cred.getDownloadTokenAt().plusMinutes(linkMinutes).isBefore(LocalDateTime.now())) {
            throw new ServiceException(410, "链接已过期，请联系管理员重新生成");
        }
        return cred;
    }

    private String currentUser() {
        try {
            return com.erp.util.SecurityUtils.getCurrentUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
