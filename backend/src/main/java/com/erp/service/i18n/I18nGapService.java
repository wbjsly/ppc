package com.erp.service.i18n;

import java.util.List;
import java.util.Map;

/**
 * 翻译缺口（17.1.2，BR-5.7-05 批量回传，spec i18n-localization）。
 */
public interface I18nGapService {

    /**
     * 批量回传 UPSERT 累加（键名唯一，FIRST_HIT_AT 不变，HIT_COUNT 累加；
     * 周命中 >500 自动升级 P1）。
     *
     * @param items [{keyName, moduleName, priority, hitCount, firstHitAt}]
     * @return 处理条数
     */
    int reportBatch(List<Map<String, Object>> items);

    /** 缺口台账（按状态/优先级/命中次数排序） */
    List<Map<String, Object>> list(String status, String priority, int limit);

    /** 关闭缺口（补译完成） */
    void close(String id);

    /** 覆盖率统计（按 locale 分级 P0~P3，返回总体与各优先级覆盖率） */
    Map<String, Object> coverage(String locale);
}
