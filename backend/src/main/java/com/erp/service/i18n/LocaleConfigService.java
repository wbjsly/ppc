package com.erp.service.i18n;

import java.util.List;
import java.util.Map;

/**
 * 区域格式配置（17.2，C-5.7-02 回退默认 Locale / C-5.7-08 输入格式校验）。
 */
public interface LocaleConfigService {

    /** 三语 Locale 的日期/数字/货币格式配置（zh-CN/en-US/ja-JP） */
    List<Map<String, Object>> list();

    /** 保存格式配置（含格式模板与小数位） */
    Map<String, Object> save(Map<String, Object> body);

    /**
     * 输入格式校验（C-5.7-08）：按 locale 格式模板校验日期/邮编/电话等。
     *
     * @return {pass, errors:[{field, expected}]}
     */
    Map<String, Object> validate(String locale, Map<String, String> fields);

    /** 回退事件记录（C-5.7-02：Locale 未配置回退 zh-CN 的事件台账） */
    List<Map<String, Object>> fallbackEvents(int limit);
}
