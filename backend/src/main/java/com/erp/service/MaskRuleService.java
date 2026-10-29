package com.erp.service;

import java.util.List;
import java.util.Map;

/**
 * 脱敏规则维护（16.7.3，限 ADMIN）：CRUD + 启停；改动后使 MaskService 缓存失效（新规则即刻生效）。
 */
public interface MaskRuleService {

    List<Map<String, Object>> list();

    /** 新建/更新规则（id 空 = 新建）；PATTERN 须为合法 Java 正则且编译通过 */
    void save(String id, String code, String name, String pattern, String maskTemplate,
              String status, String remark);

    void delete(String id);
}
