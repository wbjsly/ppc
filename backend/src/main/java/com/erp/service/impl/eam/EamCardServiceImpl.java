package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamAssetCategoryDao;
import com.erp.dao.eam.EamCapitalizationDao;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamCapitalization;
import com.erp.service.eam.EamCardService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 资产卡片台账实现（add-asset-capitalization，7.1.3）。
 * 更新委托转资服务的 BR-4.13-10 锁定拦截（同一口径）。
 */
@Service
public class EamCardServiceImpl implements EamCardService {

    private final EamAssetCardDao cardDao;
    private final EamAssetCategoryDao categoryDao;
    private final EamCapitalizationDao capDao;
    private final EamCapitalizationServiceImpl capService;

    public EamCardServiceImpl(EamAssetCardDao cardDao,
                              EamAssetCategoryDao categoryDao,
                              EamCapitalizationDao capDao,
                              EamCapitalizationServiceImpl capService) {
        this.cardDao = cardDao;
        this.categoryDao = categoryDao;
        this.capDao = capDao;
        this.capService = capService;
    }

    @Override
    public Page<EamAssetCard> page(long current, long size, String status, String categoryCode,
                                   String keyword) {
        LambdaQueryWrapper<EamAssetCard> qw = new LambdaQueryWrapper<EamAssetCard>()
                .eq(status != null && !status.isEmpty(), EamAssetCard::getStatus, status)
                .eq(categoryCode != null && !categoryCode.isEmpty(),
                        EamAssetCard::getCategoryCode, categoryCode)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(EamAssetCard::getCardNo, keyword)
                        .or().like(EamAssetCard::getAssetName, keyword))
                .orderByDesc(EamAssetCard::getCreateDate);
        return cardDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    public Map<String, Object> detail(String id) {
        EamAssetCard card = cardDao.selectById(id);
        if (card == null) {
            throw new ServiceException(404, "资产卡片不存在：" + id);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("card", card);
        out.put("category", categoryDao.selectActiveByCode(card.getCategoryCode()));
        EamCapitalization cap = card.getSourceCapId() == null ? null
                : capDao.selectById(card.getSourceCapId());
        out.put("cap", cap);
        return out;
    }

    @Override
    public EamAssetCard update(EamAssetCard card) {
        // BR-4.13-10 锁定拦截统一在转资服务
        return capService.updateCard(card);
    }
}
