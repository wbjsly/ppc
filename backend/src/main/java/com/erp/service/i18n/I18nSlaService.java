package com.erp.service.i18n;

import java.util.List;
import java.util.Map;

/**
 * i18n SLA 月度报告（17.7，BR-5.7-14 未归档阻断，复用 16.9/18.5 报告范式）。
 */
public interface I18nSlaService {

    /** 生成月度报告（五指标：语言包版本数/三语覆盖率/缺口闭环率/渲染异常/灰度回退） */
    Map<String, Object> generate(String period);

    /** 报告快照 */
    Map<String, Object> report(String period);

    /** 归档（DRAFT→ARCHIVED，版本锁定） */
    Map<String, Object> archive(String period);

    /** 对外发布（非 ARCHIVED 422 阻断并留痕 BR-5.7-14/C-5.7-05） */
    Map<String, Object> publish(String period, String scope);

    /** 报告列表 */
    List<Map<String, Object>> list();
}
