package com.erp.service.fin;

import com.erp.entity.fin.TpAssessment;
import com.erp.entity.fin.TpDocument;
import com.erp.entity.fin.TpParty;

import java.util.List;
import java.util.Map;

/**
 * 关联交易定价管理（transfer-pricing，S-4.14-08）。
 */
public interface TransferPricingService {

    // ---------- 关联方 ----------

    /** 登记关联方（名称+识别号重复 422；境外标注） */
    TpParty registerParty(Map<String, Object> payload);

    /** 关联方列表 */
    List<TpParty> listParties();

    // ---------- 定价文档 ----------

    /** 登记年度定价文档（三档/方法五族校验 422） */
    TpDocument registerDocument(Map<String, Object> payload);

    /** 文档列表（按关联方/年度/类型） */
    List<TpDocument> listDocuments(String partyId, String coverYear);

    /**
     * 提醒（查询型，design D5 惯例不建 cron）：
     * 备查有效期 ≤ PREF_EXPIRE_WARNING_DAYS 临期 + 年度文档断档。
     */
    Map<String, Object> reminders();

    // ---------- 风险自评 ----------

    /** 年度自评提交（同年度一条；有风险项 → 有待改进；留痕不阻断） */
    TpAssessment submitAssessment(Map<String, Object> payload);

    /** 自评历史 */
    List<TpAssessment> listAssessments();
}
