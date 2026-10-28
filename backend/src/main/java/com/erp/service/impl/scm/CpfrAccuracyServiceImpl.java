package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.dao.scm.CpfrExceptionDao;
import com.erp.dao.scm.CpfrFeedbackDao;
import com.erp.dao.scm.CpfrPlanDao;
import com.erp.dao.scm.CpfrPlanLineDao;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.entity.scm.CpfrException;
import com.erp.entity.scm.CpfrFeedback;
import com.erp.entity.scm.CpfrPlan;
import com.erp.entity.scm.CpfrPlanLine;
import com.erp.service.scm.CpfrAccuracyService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CPFR 预测准确率月度评估（spec cpfr-collaboration R7）：
 * 准确率 = 1 − Σ|实际−预测|/Σ预测（按已转化行）；
 * 连续 3 周期 < 70% 或单周期例外占比 > 30% → 协议「待复盘」（暂停生成订单）。
 */
@Service
public class CpfrAccuracyServiceImpl implements CpfrAccuracyService {

    private final CpfrPlanDao planDao;
    private final CpfrPlanLineDao lineDao;
    private final CpfrAgreementDao agreementDao;
    private final CpfrExceptionDao exceptionDao;
    private final CpfrFeedbackDao feedbackDao;

    public CpfrAccuracyServiceImpl(CpfrPlanDao planDao,
                                   CpfrPlanLineDao lineDao,
                                   CpfrAgreementDao agreementDao,
                                   CpfrExceptionDao exceptionDao,
                                   CpfrFeedbackDao feedbackDao) {
        this.planDao = planDao;
        this.lineDao = lineDao;
        this.agreementDao = agreementDao;
        this.exceptionDao = exceptionDao;
        this.feedbackDao = feedbackDao;
    }

    @Override
    @Transactional
    public Map<String, Object> evaluateMonth(String yyyyMM) {
        if (yyyyMM == null || !yyyyMM.matches("\\d{6}")) {
            throw new com.erp.common.ServiceException(422, "月份格式须为 yyyyMM");
        }
        List<CpfrPlan> plans = planDao.selectList(new LambdaQueryWrapper<CpfrPlan>()
                .isNull(CpfrPlan::getAccuracyMonth)); // 尚未评估的计划
        Map<String, Object> out = new HashMap<>();
        out.put("plans", 0);
        out.put("agreements", 0);
        out.put("reviewTriggered", new ArrayList<String>());
        for (CpfrPlan plan : plans) {
            BigDecimal acc = accuracyOf(plan);
            if (acc == null) {
                continue; // 无转化数据，不评估
            }
            plan.setAccuracyPct(acc);
            plan.setAccuracyMonth(yyyyMM);
            planDao.updateById(plan);
            out.put("plans", (int) out.get("plans") + 1);
        }
        // 协议级复盘判定（连续 3 周期 <70% 或例外占比 >30%）
        List<CpfrAgreement> agreements = agreementDao.selectList(new LambdaQueryWrapper<CpfrAgreement>()
                .ne(CpfrAgreement::getStatus, CpfrAgreement.ST_INIT));
        @SuppressWarnings("unchecked")
        List<String> triggered = (List<String>) out.get("reviewTriggered");
        for (CpfrAgreement agr : agreements) {
            if (needReview(agr)) {
                if (!CpfrAgreement.ST_REVIEW.equals(agr.getStatus())) {
                    agr.setStatus(CpfrAgreement.ST_REVIEW);
                    agr.setPauseReason(CpfrAgreement.REASON_ACCURACY);
                    agreementDao.updateById(agr);
                }
                triggered.add(agr.getId());
            }
            out.put("agreements", agreements.size());
        }
        return out;
    }

    /** 准确率 = 1 − Σ|实际−预测|/Σ预测（按已转化行；无转化行返回 null） */
    BigDecimal accuracyOf(CpfrPlan plan) {
        List<CpfrPlanLine> lines = lineDao.selectList(new LambdaQueryWrapper<CpfrPlanLine>()
                .eq(CpfrPlanLine::getPlanId, plan.getId())
                .isNotNull(CpfrPlanLine::getPoConversionQty));
        if (lines.isEmpty()) {
            return null;
        }
        BigDecimal sumForecast = BigDecimal.ZERO;
        BigDecimal sumAbsDev = BigDecimal.ZERO;
        for (CpfrPlanLine l : lines) {
            BigDecimal f = l.getQty() == null ? BigDecimal.ZERO : l.getQty();
            BigDecimal a = l.getPoConversionQty();
            sumForecast = sumForecast.add(f);
            sumAbsDev = sumAbsDev.add(a.subtract(f).abs());
        }
        if (sumForecast.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = sumAbsDev.divide(sumForecast, 4, RoundingMode.HALF_UP);
        return BigDecimal.ONE.subtract(ratio)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /** 复盘判定：最近 3 个已评估周期全部 <70%，或任一周期例外占比 >30% */
    boolean needReview(CpfrAgreement agr) {
        List<CpfrPlan> evaluated = planDao.selectList(new LambdaQueryWrapper<CpfrPlan>()
                .eq(CpfrPlan::getAgreementId, agr.getId())
                .isNotNull(CpfrPlan::getAccuracyPct)
                .orderByDesc(CpfrPlan::getPeriodTag)
                .last("LIMIT 3"));
        if (evaluated.size() >= 3 && evaluated.stream()
                .allMatch(p -> p.getAccuracyPct().compareTo(new BigDecimal("70")) < 0)) {
            return true;
        }
        // 单周期例外占比 >30%
        List<String> periods = evaluated.stream().map(CpfrPlan::getPeriodTag).distinct().toList();
        for (String p : periods) {
            long exCount = exceptionDao.selectCount(new LambdaQueryWrapper<CpfrException>()
                    .eq(CpfrException::getAgreementId, agr.getId())
                    .eq(CpfrException::getPeriodTag, p));
            long fbCount = feedbackDao.selectCount(new LambdaQueryWrapper<CpfrFeedback>()
                    .eq(CpfrFeedback::getAgreementId, agr.getId())
                    .eq(CpfrFeedback::getPeriodTag, p));
            if (fbCount > 0 && exCount * 100 > fbCount * 30) {
                return true;
            }
        }
        return false;
    }
}
