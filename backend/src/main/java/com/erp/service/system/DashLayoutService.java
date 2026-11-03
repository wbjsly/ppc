package com.erp.service.system;

import java.util.List;
import java.util.Map;

/**
 * 工作台三层布局（change add-configurable-workbench，spec personal-workbench）。
 * 三层单向覆盖：GLOBAL → ROLE → USER，个人 excluded 最高优先（design D2 差量合并）。
 * 写操作只 upsert 不删行（恢复默认 = 清空 LAYOUT_JSON）。
 */
public interface DashLayoutService {

    /**
     * 合并三层返回登录用户的最终布局。
     *
     * @return {"v":1,"items":[{id,x,y,w,h}...],"excluded":[id...],"shortcuts":[...],"layers":{"global":bool,"role":bool,"user":bool}}
     */
    Map<String, Object> merged(String userId, List<String> roles);

    /** ADMIN 抽屉回显：{"global": json|null, "roles": {roleName: json, ...}} */
    Map<String, Object> layers();

    /** 读单层原始 JSON（不存在返回 null）。scope ∈ GLOBAL/ROLE/USER。 */
    String layer(String scope, String refId);

    /** 保存个人层（upsert）。 */
    void saveUser(String userId, String layoutJson);

    /** 保存角色模板层（upsert，调用方须已校验 ADMIN）。 */
    void saveRole(String role, String layoutJson);

    /** 保存全局默认层（upsert，调用方须已校验 ADMIN）。 */
    void saveGlobal(String layoutJson);
}
