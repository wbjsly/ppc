package com.erp.service.scm;

import java.util.Map;

/**
 * 门户账号激活业务能力契约（add-partner-onboarding，design D4，FR-4.9-1-4 / BR-4.9-11）。
 * 一次性 token 7 天、首登设置密码即激活、过期/重复/重发旧链立即失效。
 */
public interface PartnerActivateService {

    /** 匿名校验 token：返回脱敏企业名+邮箱域（不回显全量）；失效/过期/已用 410 */
    Map<String, Object> verify(String token);

    /** 匿名激活：设置密码（复杂度 ≥8 含大小写数字特殊）→ 账号启用 + token 置已用（一次性） */
    Map<String, Object> activate(String token, String password);

    /** 管理端：申请单激活链接（链接+状态+过期时间，PROCUREMENT/ADMIN） */
    Map<String, Object> activateLink(String applyId);

    /** 管理端：重新生成（旧 token 立即失效，新 token 有效期重置 7 天） */
    Map<String, Object> regenerate(String applyId);

    /** 每日扫表：过期未用 token 置失效（幂等），返回处理条数 */
    int sweepExpired();
}
