package com.erp.service.i18n;

import java.util.List;
import java.util.Map;

/**
 * 翻译键值（17.1.1，spec i18n-localization）：CRUD + XLIFF/CSV 导入导出 + 占位符校验（BR-5.7-03）。
 */
public interface I18nKeyService {

    /** 键值列表（按 locale/module/priority 过滤） */
    List<Map<String, Object>> list(String locale, String module, String priority, String keyword, int page, int size);

    /** 新增/修改键值（源语 zh-CN 必填；目标语可空=缺口） */
    Map<String, Object> save(Map<String, Object> body);

    /** 删除键（源语删除级联目标语） */
    void delete(String id);

    /**
     * 导入译文（CSV：key_name,target_text 两列）。
     * BR-5.7-03：占位符集合（数量/名称/顺序）与源语不一致 → 拒绝整个文件并标注错误行；
     * 键名不存在/重复 → 422。
     *
     * @return 导入成功条数
     */
    int importCsv(String locale, String csvText);

    /** 导出键清单 CSV（key_name,module,priority,source_text,target_text） */
    String exportCsv(String locale, String module);
}
