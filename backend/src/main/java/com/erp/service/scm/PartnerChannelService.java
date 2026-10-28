package com.erp.service.scm;

import java.util.List;
import java.util.Map;

/**
 * 伙伴通道配置业务能力契约（add-partner-onboarding，菜单 12.1.3，design D5）。
 * 通道配置态唯一写入口：创建通道/生成密钥/限流档位/证书更新。
 * 运行态（熔断/探测/吊销）留 2.8.3 interface-console，不在本服务。
 * 生产态切换须经 interface-onboarding 放行会签（不重造），本服务只写 SANDBOX 初始态。
 */
public interface PartnerChannelService {

    /** 通道列表（partnerCode 可选过滤），含入驻状态聚合（申请单/账号激活） */
    List<Map<String, Object>> list(String partnerCode);

    /**
     * 创建通道（默认 SANDBOX 态）：协议/回调 URL/证书指纹登记。
     * 同伙伴同协议重复创建 409。
     */
    Map<String, Object> createChannel(Map<String, Object> payload);

    /** 生成 API Key+Secret（复用 CredentialService.issue：加盐哈希，明文仅本次返回） */
    Map<String, Object> generateKey(Map<String, Object> payload);

    /** 更新限流档位（STRATEGIC 500/NORMAL 100/NEW 30，同步 LIMIT_PER_MIN） */
    Map<String, Object> updateRateTier(String channelId, String rateTier);

    /** 证书指纹与有效期更新（登记台账，不真连） */
    Map<String, Object> updateCert(String channelId, Map<String, Object> payload);
}
