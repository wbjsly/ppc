package com.erp.service.eam;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.entity.eam.EamAssetCategory;

/**
 * 资产分类主数据（add-asset-capitalization，spec asset-capitalization）。
 * 无审批立即生效（主数据基线模式）；转资按编码带入默认折旧政策。
 */
public interface EamCategoryService {

    /** 分页（关键字/状态过滤） */
    Page<EamAssetCategory> page(long current, long size, String keyword, String status);

    /** 新增（编码唯一 409；无审批立即生效） */
    EamAssetCategory create(EamAssetCategory category);

    /** 修改（乐观锁；无审批立即生效） */
    EamAssetCategory update(EamAssetCategory category);

    /** 按编码取生效分类（转资带入默认折旧政策，未登记返回 null） */
    EamAssetCategory getActive(String categoryCode);
}
