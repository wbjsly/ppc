package com.erp.service.fin;

import com.erp.entity.fin.TaxPreference;

import java.util.List;
import java.util.Map;

/**
 * 税收优惠备案管理（tax-preference-register，BR-4.14-05/06 / S-4.14-11）。
 */
public interface TaxPreferenceService {

    /**
     * 登记优惠备案（S-4.14-11）：必备字段（政策依据/主体/期间）缺失 422；
     * 同主体同类型期间重叠 422。
     */
    TaxPreference register(Map<String, Object> payload);

    /** 备案列表（过期惰性判定后返回，附临期/过期标注） */
    List<Map<String, Object>> list(String status, String prefType);

    /** 续期登记（BR-4.14-06）：延长优惠期间 + 全程留痕（原期间/新期间/依据/操作人/时间） */
    TaxPreference renew(String prefId, Map<String, Object> payload);

    /** 手工冻结（条件不满足）：原因必填留痕 */
    TaxPreference freeze(String prefId, String reason);

    /**
     * 政策废止终止（BR-4.14-06）：REVOKE_DATE + 补税风险评估说明必填，
     * 自动终止适用并全程留痕。
     */
    TaxPreference revoke(String prefId, String revokeDate, String riskAssessNote);

    /**
     * 到期预警清单（BR-4.14-06）：ACTIVE 且距止日 ≤ PREF_EXPIRE_WARNING_DAYS（默认 90 天）
     * + 冻结/终止记录（含原因）。
     */
    Map<String, Object> warnings();

    /** 详情 */
    TaxPreference detail(String prefId);
}
