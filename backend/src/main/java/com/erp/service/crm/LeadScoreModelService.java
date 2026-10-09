package com.erp.service.crm;

import com.erp.entity.crm.LeadScoreModel;

import java.util.List;

/**
 * 线索评分模型（spec crm-lead-management，BR-4.8-07 按行业/产品线差异化）。
 * 权重合计 ≠100% 一律 L1 硬阻断；保存产生新版本，历史版本可回溯。
 */
public interface LeadScoreModelService {

    List<LeadScoreModel> list(String keyword, String industry);

    List<LeadScoreModel> versions(String modelKey);

    /** 保存（新建或按同 key 升版本），权重合计必须 100% */
    LeadScoreModel save(LeadScoreModel model);

    /** 评分取用的生效模型：行业/产品线优先，其次 DEFAULT */
    LeadScoreModel active(String industry, String productLine);
}
