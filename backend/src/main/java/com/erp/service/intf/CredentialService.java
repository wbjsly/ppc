package com.erp.service.intf;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.intf.IntfCredential;

import java.util.Map;

/** API 凭证生命周期（spec api-credential-management，SOP-5.5-B 六步）。 */
public interface CredentialService {

    /** 步骤1 签发：加盐哈希 + 对称签名密钥，明文仅本次响应返回；同伙伴 ACTIVE ≤ 2 套 */
    Map<String, Object> issue(String partnerCode, String scope, String tier, String env,
                              Integer ttlDays, String remark);

    /** 步骤2 一次性分发链接（默认 10 分钟，下载即标记来源） */
    Map<String, Object> createLink(String credId);

    /** 链接下载（token 校验），返回明文口令与签名密钥 */
    Map<String, Object> download(String token, String ip, String userAgent);

    /** 调用方「已接收」确认：清空临时明文 */
    Map<String, Object> ack(String token);

    /** 步骤4 计划内轮换：新凭证灰度启用，观察期后旧凭证 DEPRECATED（保留 7 天）→ 自动吊销 */
    Map<String, Object> rotateStart(String oldCredId);

    /** 步骤5 紧急吊销（仅 ADMIN）：立即 REVOKED 并清空令牌桶 */
    void revoke(String credId, String reason);

    /** 步骤6 泄露追溯：近 30 天调用按来源 IP 比对，给出存在/不存在异常调用结论 */
    Map<String, Object> trace(String partnerCode);

    /** 到期提醒（30/7/1 日）+ 到期自动 EXPIRED + 轮换观察期推进 + 临时明文超期清理 */
    int sweepExpiry();

    Page<IntfCredential> page(long current, long size, String partnerCode, String status);
}
