package com.erp.service.i18n;

import java.util.List;
import java.util.Map;

/**
 * 语言包版本发布（17.1.3，SOP-5.7-B 六步，spec i18n-localization）。
 */
public interface I18nPackService {

    /** 构建版本（不可变快照：把当前键值按 locale 打包版本号；UTF-8/键完整性校验） */
    Map<String, Object> build(String locale, String packNo, String remark);

    /**
     * 发布前预检（BR-5.7-01/02、C-5.7-06）：
     * P0/P1 覆盖率 MUST 100%、总体 ≥ TRANSLATION_COVERAGE_MIN（默认 99%）。
     *
     * @return {pass, totalKeys, translated, coverage, p0Coverage, p1Coverage, gaps:[...]}
     */
    Map<String, Object> precheck(String locale);

    /** 审批（本地化负责人；预检不通过 422 阻断，BR-5.7-01） */
    Map<String, Object> approve(String id);

    /** 灰度启动（默认 10%，按用户哈希分流；回退策略为发布前置） */
    Map<String, Object> startCanary(String id, Integer pct);

    /** 灰度检查：渲染异常率 > 0.1% 自动回退上一版本（BR-5.7-07） */
    Map<String, Object> checkCanary(String id);

    /** 归档（版本锁定不可修改，C-0-05） */
    Map<String, Object> archive(String id);

    /** 版本列表 */
    List<Map<String, Object>> list(String locale, String status);

    /** 当前生效版本的键值 JSON（前端加载器消费） */
    Map<String, Object> currentPack(String locale);
}
