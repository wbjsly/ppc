package com.erp.service.pm;

import java.util.List;
import java.util.Map;

/** WBS 模板（project-initiation，BR-4.8-13；11.3.2 页内 Tab）。 */
public interface PmTemplateService {

    /** 模板清单（启用状态可选过滤） */
    List<Map<String, Object>> list(String status);

    /** 新建模板 */
    Map<String, Object> create(Map<String, Object> payload);

    /** 更新模板（含 JSON 校验） */
    Map<String, Object> update(String id, Map<String, Object> payload);

    /** 启停 */
    void toggle(String id, String status);

    /** 删除（逻辑删） */
    void delete(String id);

    /** 一键生成骨架：深拷贝模板层级为项目 WBS；已有 WBS 需 confirm=true */
    Map<String, Object> generate(String projectId, String templateId, boolean confirm);
}
