package com.erp.service.system;

import java.util.Map;

/** 账号生命周期（add-account-lifecycle-console，spec account-lifecycle）。 */
public interface AccountLifecycleService {

    /**
     * 强停：置 STATUS='0' + 生成角色清单 JSON 快照写审计留痕（《离职人员权限清单》语义）。
     *
     * @return 停用摘要 {userId, roleSnapshot}
     */
    Map<String, Object> disable(String userId, String operator);

    /**
     * 归档：校验二次确认用户名一致 + 当前为停用态 → 置 '2' + ARCHIVED_AT（C-0-05 不可恢复）。
     */
    Map<String, Object> archive(String userId, String confirmUsername, String operator);

    /**
     * 生命周期清扫（幂等，design D5）：
     * ①离职最后工作日 +24h 未停用 → 强停 + 告警审计（BR-5.6-09）；
     * ②停用满 N 天（app.admin.account-archive-days，默认 90）→ 自动归档。
     *
     * @return {disabled, archived}
     */
    Map<String, Object> sweep();
}
