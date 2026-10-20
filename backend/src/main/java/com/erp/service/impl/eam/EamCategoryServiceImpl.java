package com.erp.service.impl.eam;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.eam.EamAssetCategoryDao;
import com.erp.entity.eam.EamAssetCategory;
import com.erp.service.eam.EamCategoryService;
import com.erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 资产分类主数据实现（add-asset-capitalization）：
 * 无审批立即生效（主数据基线：无审批、立即生效、乐观锁）。
 */
@Service
public class EamCategoryServiceImpl implements EamCategoryService {

    private final EamAssetCategoryDao categoryDao;

    public EamCategoryServiceImpl(EamAssetCategoryDao categoryDao) {
        this.categoryDao = categoryDao;
    }

    @Override
    public Page<EamAssetCategory> page(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<EamAssetCategory> qw = new LambdaQueryWrapper<EamAssetCategory>()
                .eq(status != null && !status.isEmpty(), EamAssetCategory::getStatus, status)
                .and(keyword != null && !keyword.isEmpty(), w -> w
                        .like(EamAssetCategory::getCategoryCode, keyword)
                        .or().like(EamAssetCategory::getName, keyword))
                .orderByAsc(EamAssetCategory::getCategoryCode);
        return categoryDao.selectPage(new Page<>(current, size), qw);
    }

    @Override
    @Transactional
    public EamAssetCategory create(EamAssetCategory category) {
        validate(category);
        Long dup = categoryDao.selectCount(new LambdaQueryWrapper<EamAssetCategory>()
                .eq(EamAssetCategory::getCategoryCode, category.getCategoryCode()));
        if (dup != null && dup > 0) {
            throw new ServiceException(409, "分类编码已存在：" + category.getCategoryCode());
        }
        category.setId(null);
        if (category.getStatus() == null || category.getStatus().isEmpty()) {
            category.setStatus("ACTIVE");
        }
        category.setCreateBy(SecurityUtils.getCurrentUserId());
        categoryDao.insert(category);
        return category;
    }

    @Override
    @Transactional
    public EamAssetCategory update(EamAssetCategory category) {
        if (category.getId() == null || category.getId().isEmpty()) {
            throw new ServiceException(422, "分类 ID 必填");
        }
        EamAssetCategory old = categoryDao.selectById(category.getId());
        if (old == null) {
            throw new ServiceException(404, "分类不存在：" + category.getId());
        }
        validate(category);
        EamAssetCategory upd = new EamAssetCategory();
        upd.setId(old.getId());
        upd.setVerNo(old.getVerNo());
        upd.setName(category.getName());
        upd.setCapitalizationFloor(category.getCapitalizationFloor());
        upd.setDefaultLifeYears(category.getDefaultLifeYears());
        upd.setDefaultResidualRate(category.getDefaultResidualRate());
        upd.setDefaultDepreciationMethod(category.getDefaultDepreciationMethod());
        upd.setLifeYearsMin(category.getLifeYearsMin());
        upd.setLifeYearsMax(category.getLifeYearsMax());
        upd.setAssetAccount(category.getAssetAccount());
        upd.setStatus(category.getStatus());
        upd.setRemark(category.getRemark());
        upd.setUpdateBy(SecurityUtils.getCurrentUserId());
        if (categoryDao.updateById(upd) == 0) {
            throw new ServiceException(409, "分类更新冲突（并发修改），请刷新后重试");
        }
        return categoryDao.selectById(old.getId());
    }

    @Override
    public EamAssetCategory getActive(String categoryCode) {
        return categoryDao.selectActiveByCode(categoryCode);
    }

    /** 校验：必填项 + 数值域 */
    private void validate(EamAssetCategory c) {
        if (isBlank(c.getCategoryCode())) {
            throw new ServiceException(422, "分类编码必填");
        }
        if (isBlank(c.getName())) {
            throw new ServiceException(422, "分类名称必填");
        }
        if (isBlank(c.getAssetAccount())) {
            throw new ServiceException(422, "资产科目编码必填（转资凭证取数）");
        }
        if (c.getCapitalizationFloor() == null || c.getCapitalizationFloor().signum() < 0) {
            throw new ServiceException(422, "资本化下限必须为非负数（BR-4.13-09）");
        }
        if (c.getDefaultLifeYears() == null || c.getDefaultLifeYears() <= 0) {
            throw new ServiceException(422, "默认使用年限必须大于 0");
        }
        if (c.getDefaultResidualRate() == null
                || c.getDefaultResidualRate().compareTo(BigDecimal.ZERO) < 0
                || c.getDefaultResidualRate().compareTo(BigDecimal.ONE) >= 0) {
            throw new ServiceException(422, "默认残值率须在 [0, 1) 区间");
        }
        if (c.getLifeYearsMin() == null || c.getLifeYearsMax() == null
                || c.getLifeYearsMin() <= 0 || c.getLifeYearsMax() < c.getLifeYearsMin()) {
            throw new ServiceException(422, "使用年限范围非法（下限 ≥1 且上限 ≥ 下限）");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
