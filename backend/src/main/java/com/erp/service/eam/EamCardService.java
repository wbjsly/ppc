package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamAssetCard;

import java.util.Map;

/**
 * 资产卡片台账（add-asset-capitalization，spec asset-capitalization 7.1.3）。
 * 列表/详情/更新（BR-4.13-10 锁定拦截委托转资服务）/标签打印数据。
 */
public interface EamCardService {

    /** 分页（状态/分类/关键字） */
    Page<EamAssetCard> page(long current, long size, String status, String categoryCode,
                            String keyword);

    /** 详情（含所属转资单与分类，供标签打印） */
    Map<String, Object> detail(String id);

    /** 更新（BR-4.13-10 锁定拦截由转资服务统一实现） */
    EamAssetCard update(EamAssetCard card);
}
