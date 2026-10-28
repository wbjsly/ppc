package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.intf.IntfChannelDao;
import com.erp.entity.intf.IntfChannel;
import com.erp.entity.intf.IntfTierChangeLog;
import com.erp.service.intf.CredentialService;
import com.erp.service.intf.TierChangeLogService;
import com.erp.service.scm.PartnerChannelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 伙伴通道配置实现（add-partner-onboarding design D5）。
 * 配置态唯一写入口（12.1.3）：写 erp_intf_channel + 复用 CredentialService.issue 签发密钥。
 * 新建通道默认 SANDBOX；PROD 切换走 interface-onboarding 放行会签（不在本服务）。
 * 限流档位默认值对齐 BR-4.9-18：战略 500 / 普通 100 / 新伙伴 30 次/分钟。
 */
@Slf4j
@Service
public class PartnerChannelServiceImpl implements PartnerChannelService {

    private static final Map<String, Integer> TIER_LIMIT = Map.of(
            IntfChannel.TIER_STRATEGIC, 500,
            IntfChannel.TIER_NORMAL, 100,
            IntfChannel.TIER_NEW, 30,
            IntfChannel.TIER_LOWEST, 30);

    private final IntfChannelDao channelDao;
    private final CredentialService credentialService;
    private final TierChangeLogService tierChangeLog;

    public PartnerChannelServiceImpl(IntfChannelDao channelDao,
                                     CredentialService credentialService,
                                     TierChangeLogService tierChangeLog) {
        this.channelDao = channelDao;
        this.credentialService = credentialService;
        this.tierChangeLog = tierChangeLog;
    }

    @Override
    public List<Map<String, Object>> list(String partnerCode) {
        requireIntfOps("查看伙伴通道");
        LambdaQueryWrapper<IntfChannel> w = new LambdaQueryWrapper<IntfChannel>()
                .eq(IntfChannel::getDelFlag, "0")
                .eq(StringUtils.hasText(partnerCode), IntfChannel::getPartnerCode,
                        partnerCode == null ? null : partnerCode.trim())
                .orderByDesc(IntfChannel::getCreateDate);
        List<Map<String, Object>> out = new ArrayList<>();
        for (IntfChannel c : channelDao.selectList(w)) {
            out.add(row(c));
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> createChannel(Map<String, Object> payload) {
        requireIntfOps("创建伙伴通道");
        String partnerCode = str(payload.get("partnerCode"));
        String partnerName = str(payload.get("partnerName"));
        String channelType = str(payload.get("channelType"));
        String protocol = str(payload.get("protocol"));
        if (!StringUtils.hasText(partnerCode) || !StringUtils.hasText(partnerName)) {
            throw new ServiceException(422, "伙伴编码与名称必填");
        }
        if (!"API".equals(channelType) && !"EDI".equals(channelType)) {
            throw new ServiceException(422, "channelType 须为 API 或 EDI");
        }
        if (!StringUtils.hasText(protocol)) {
            throw new ServiceException(422, "协议必填（HTTPS/AS2/OFTP2/SFTP）");
        }
        // 同伙伴同协议重复创建 409
        Long dup = channelDao.selectCount(new LambdaQueryWrapper<IntfChannel>()
                .eq(IntfChannel::getDelFlag, "0")
                .eq(IntfChannel::getPartnerCode, partnerCode.trim())
                .eq(IntfChannel::getChannelType, channelType)
                .eq(IntfChannel::getProtocol, protocol.trim()));
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "该伙伴已存在同类型同协议通道");
        }
        IntfChannel c = new IntfChannel();
        c.setId(uuid());
        c.setPartnerCode(partnerCode.trim());
        c.setPartnerName(partnerName.trim());
        // 关联供应商（迁移 182，design D5）：门户服务等级章节隔离按此桥接
        c.setSupplierId(str(payload.get("supplierId")));
        // 伙伴等级默认 NEW（新伙伴 30 次/分钟，BR-4.9-18 档位语义）
        String tier = str(payload.get("partnerTier"));
        c.setPartnerTier(tier != null && TIER_LIMIT.containsKey(tier) ? tier
                : IntfChannel.TIER_NEW);
        c.setChannelType(channelType);
        c.setProtocol(protocol.trim());
        c.setCallbackUrl(str(payload.get("callbackUrl")));
        c.setCertFingerprint(str(payload.get("certFingerprint")));
        c.setCertExpireDate(parseDate(str(payload.get("certExpireDate"))));
        // 新建默认 SANDBOX（PROD 切换走 interface-onboarding 放行会签）
        c.setStatus(IntfChannel.ST_SANDBOX);
        c.setRateTier(c.getPartnerTier());
        c.setLimitPerMin(TIER_LIMIT.get(c.getRateTier()));
        c.setCircuitState(IntfChannel.CIRCUIT_CLOSED);
        c.setRemark(str(payload.get("remark")));
        c.setCreateBy(currentUser());
        c.setCreateDate(java.time.LocalDateTime.now());
        channelDao.insert(c);
        log.info("partner channel created partner={} type={} protocol={}",
                partnerCode, channelType, protocol);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", c);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> generateKey(Map<String, Object> payload) {
        requireIntfOps("生成伙伴密钥");
        String channelId = str(payload.get("channelId"));
        IntfChannel c = requireChannel(channelId);
        String scope = str(payload.get("scope"));
        if (!StringUtils.hasText(scope)) {
            throw new ServiceException(422, "scope 必填（接口权限范围）");
        }
        Integer ttlDays = payload.get("ttlDays") == null ? 90
                : Integer.valueOf(String.valueOf(payload.get("ttlDays")));
        // 复用既有凭证签发（加盐哈希，明文仅本次返回；同伙伴 ACTIVE ≤2 套在 issue 内校验）
        Map<String, Object> issued = credentialService.issue(c.getPartnerCode(), scope,
                c.getRateTier(), "SANDBOX", ttlDays, str(payload.get("remark")));
        Map<String, Object> out = new LinkedHashMap<>(issued);
        out.put("channelId", channelId);
        out.put("channelStatus", c.getStatus());
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> updateRateTier(String channelId, String rateTier) {
        requireIntfOps("更新限流档位");
        IntfChannel c = requireChannel(channelId);
        Integer limit = TIER_LIMIT.get(rateTier);
        if (limit == null) {
            throw new ServiceException(422, "限流档位非法：" + rateTier
                    + "（STRATEGIC/NORMAL/NEW/LOWEST）");
        }
        String before = c.getRateTier();
        Integer beforeLimit = c.getLimitPerMin();
        c.setRateTier(rateTier);
        c.setLimitPerMin(limit);
        c.setUpdateBy(currentUser());
        c.setUpdateDate(java.time.LocalDateTime.now());
        channelDao.updateById(c);
        // 档位变更历史（spec 12.3.2：手动调档同源留痕，design D1 单入口）
        tierChangeLog.record(c.getPartnerCode(), c.getPartnerCode(),
                IntfTierChangeLog.TYPE_MANUAL, before, rateTier, beforeLimit, limit,
                "管理员手动调档（12.1.3 通道开通）", currentUser());
        log.info("partner channel rate tier updated channel={} tier={} limit={}",
                channelId, rateTier, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", c);
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> updateCert(String channelId, Map<String, Object> payload) {
        requireIntfOps("更新通道证书");
        IntfChannel c = requireChannel(channelId);
        String fp = str(payload.get("certFingerprint"));
        LocalDate expire = parseDate(str(payload.get("certExpireDate")));
        if (!StringUtils.hasText(fp)) {
            throw new ServiceException(422, "证书指纹必填");
        }
        if (expire != null && !expire.isAfter(LocalDate.now())) {
            throw new ServiceException(422, "证书有效期须晚于当前日期（BR-4.9-22 前置）");
        }
        c.setCertFingerprint(fp);
        c.setCertExpireDate(expire);
        c.setUpdateBy(currentUser());
        c.setUpdateDate(java.time.LocalDateTime.now());
        channelDao.updateById(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", c);
        return out;
    }

    // ================================================================
    // 内部工具
    // ================================================================

    private IntfChannel requireChannel(String id) {
        IntfChannel c = channelDao.selectById(id);
        if (c == null || "1".equals(c.getDelFlag())) {
            throw new ServiceException(404, "通道不存在：" + id);
        }
        return c;
    }

    private Map<String, Object> row(IntfChannel c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("partnerCode", c.getPartnerCode());
        m.put("partnerName", c.getPartnerName());
        m.put("supplierId", c.getSupplierId());
        m.put("partnerTier", c.getPartnerTier());
        m.put("channelType", c.getChannelType());
        m.put("protocol", c.getProtocol());
        m.put("status", c.getStatus());
        m.put("rateTier", c.getRateTier());
        m.put("limitPerMin", c.getLimitPerMin());
        m.put("certFingerprint", c.getCertFingerprint());
        m.put("certExpireDate", c.getCertExpireDate());
        m.put("circuitState", c.getCircuitState());
        m.put("callbackUrl", c.getCallbackUrl());
        m.put("createDate", c.getCreateDate());
        return m;
    }

    private void requireIntfOps(String action) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        boolean ok = auth.getAuthorities().stream()
                .anyMatch(g -> "ROLE_ADMIN".equalsIgnoreCase(g.getAuthority())
                        || "ROLE_INTF_OPS".equalsIgnoreCase(g.getAuthority()));
        if (!ok) {
            throw new ServiceException(403, "无权限" + action + "（需 INTF_OPS/ADMIN）");
        }
    }

    private String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static LocalDate parseDate(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        return LocalDate.parse(s.trim());
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? null : s;
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
