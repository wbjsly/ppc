package com.erp.service.pm;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** 岗位费率（project-initiation，FR-4.8-2-3 人工成本口径）。 */
public interface PmRateService {

    /** 费率清单 */
    List<Map<String, Object>> list();

    /** 保存费率（GRADE 唯一 upsert） */
    Map<String, Object> save(Map<String, Object> payload);

    /**
     * 取职级适用费率：项目覆盖优先 → 费率表 → 默认 100。
     * @param projectRateOverrideJson 项目级覆盖 JSON {grade:rate}，可空
     */
    BigDecimal rateFor(String grade, String projectRateOverrideJson);
}
