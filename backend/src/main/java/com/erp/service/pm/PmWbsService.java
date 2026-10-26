package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** WBS 分解（project-initiation，FR-4.8-2-2 / BR-4.8-10；11.3.2）。 */
public interface PmWbsService {

    /** 树形查询（parentId 起，或全树） */
    List<Map<String, Object>> tree(String projectId);

    /** 新增节点（层级规则校验；任务级级联建空预算行），返回新节点 */
    Map<String, Object> create(Map<String, Object> payload);

    /** 更新节点（层级不可变；level2 交付物必填重校验） */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 删除节点（级联删子节点与预算行） */
    void delete(String id);

    /** 立项校验：WBS 非空 + 每分支延伸至任务级；返回错误清单（空 = 通过） */
    List<String> validateForBaseline(String projectId);
}
