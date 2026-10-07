package com.erp.service.impl.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.crm.LeadScoreModelDao;
import com.erp.entity.crm.LeadScoreModel;
import com.erp.service.crm.LeadScoreModelService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class LeadScoreModelServiceImpl implements LeadScoreModelService {

    public static final String ST_ACTIVE = "ACTIVE";

    private final LeadScoreModelDao modelDao;

    public LeadScoreModelServiceImpl(LeadScoreModelDao modelDao) {
        this.modelDao = modelDao;
    }

    @Override
    public List<LeadScoreModel> list(String keyword, String industry) {
        LambdaQueryWrapper<LeadScoreModel> qw = new LambdaQueryWrapper<LeadScoreModel>()
                .orderByDesc(LeadScoreModel::getIsCurrent)
                .orderByDesc(LeadScoreModel::getVersion);
        if (industry != null && !industry.trim().isEmpty()) {
            qw.eq(LeadScoreModel::getIndustry, industry.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim();
            qw.and(w -> w.like(LeadScoreModel::getModelKey, k)
                    .or().like(LeadScoreModel::getModelName, k));
        }
        return modelDao.selectList(qw);
    }

    @Override
    public List<LeadScoreModel> versions(String modelKey) {
        return modelDao.selectVersions(modelKey);
    }

    @Override
    @Transactional
    public LeadScoreModel save(LeadScoreModel model) {
        requireRole("维护评分模型", "ROLE_SALES_MGR");
        if (model == null || isBlank(model.getModelKey()) || isBlank(model.getModelName())) {
            throw new ServiceException(422, "模型键与名称必填");
        }
        // FR-4.8-1-2 / task 2.5：权重合计必须 100%，否则 L1 硬阻断
        if (model.weightSum() != 100) {
            throw new ServiceException(422, "权重合计须为 100%，当前 " + model.weightSum() + "%");
        }
        validateGrades(model);
        if (model.getGradeAMin() == null || model.getGradeBMin() == null || model.getGradeCMin() == null) {
            throw new ServiceException(422, "等级阈值必填");
        }

        String key = model.getModelKey().trim();
        List<LeadScoreModel> history = modelDao.selectVersions(key);
        int nextVersion = history.stream()
                .mapToInt(m -> m.getVersion() == null ? 0 : m.getVersion()).max().orElse(0) + 1;

        LeadScoreModel row = new LeadScoreModel();
        row.setModelKey(key);
        row.setModelName(model.getModelName().trim());
        row.setIndustry(blankToNull(model.getIndustry()));
        row.setProductLine(blankToNull(model.getProductLine()));
        row.setWNeed(model.getWNeed());
        row.setWBudget(model.getWBudget());
        row.setWChain(model.getWChain());
        row.setWUrgency(model.getWUrgency());
        row.setWCompete(model.getWCompete());
        row.setGradeAMin(model.getGradeAMin());
        row.setGradeBMin(model.getGradeBMin());
        row.setGradeCMin(model.getGradeCMin());
        row.setVersion(nextVersion);
        row.setIsCurrent(true);
        row.setStatus(ST_ACTIVE);
        row.setRemark(model.getRemark());

        // 同 key 的历史版本退为非当前（版本记录：只增不改）
        for (LeadScoreModel old : history) {
            if (Boolean.TRUE.equals(old.getIsCurrent())) {
                old.setIsCurrent(false);
                modelDao.updateById(old);
            }
        }
        modelDao.insert(row);
        return row;
    }

    @Override
    public LeadScoreModel active(String industry, String productLine) {
        LeadScoreModel m = modelDao.selectActive(blankToNull(industry), blankToNull(productLine));
        if (m == null) {
            m = modelDao.selectActive(null, null);
        }
        if (m == null) {
            throw new ServiceException(422, "无生效评分模型，请先在 11.1.2 模型配置中创建");
        }
        return m;
    }

    private void validateGrades(LeadScoreModel m) {
        int a = m.getGradeAMin(), b = m.getGradeBMin(), c = m.getGradeCMin();
        if (a <= b || b <= c || c < 0 || a > 100) {
            throw new ServiceException(422, "等级阈值须满足 A > B > C 且落在 0-100");
        }
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        java.util.List<String> roles = new java.util.ArrayList<>();
        auth.getAuthorities().forEach(a -> roles.add(a.getAuthority()));
        if (roles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : roles) {
                if (want.equalsIgnoreCase(r)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "无权限" + action);
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String blankToNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
