package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.dao.scm.CpfrFeedbackDao;
import com.erp.dao.scm.CpfrForecastLineDao;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.entity.scm.CpfrFeedback;
import com.erp.entity.scm.CpfrForecastLine;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.scm.CpfrDeadlineService;
import com.erp.service.scm.CpfrExceptionService;
import com.erp.service.scm.CpfrFeedbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** CPFR 供给能力反馈（spec cpfr-collaboration R3）。 */
@Service
public class CpfrFeedbackServiceImpl implements CpfrFeedbackService {

    private final CpfrFeedbackDao feedbackDao;
    private final CpfrAgreementDao agreementDao;
    private final CpfrForecastLineDao lineDao;
    private final CpfrExceptionService exceptionService;
    private final CpfrDeadlineService deadlineService;
    private final CpfrClockService clockService;

    public CpfrFeedbackServiceImpl(CpfrFeedbackDao feedbackDao,
                                   CpfrAgreementDao agreementDao,
                                   CpfrForecastLineDao lineDao,
                                   CpfrExceptionService exceptionService,
                                   CpfrDeadlineService deadlineService,
                                   CpfrClockService clockService) {
        this.feedbackDao = feedbackDao;
        this.agreementDao = agreementDao;
        this.lineDao = lineDao;
        this.exceptionService = exceptionService;
        this.deadlineService = deadlineService;
        this.clockService = clockService;
    }

    @Override
    @Transactional
    public Map<String, Object> submit(String supplierId, Map<String, Object> payload) {
        String feedbackId = CpfrAgreementServiceImpl.str(payload.get("feedbackId"));
        CpfrFeedback fb;
        if (StringUtils.hasText(feedbackId)) {
            fb = feedbackDao.selectById(feedbackId);
            if (fb == null) {
                throw new ServiceException(404, "反馈行不存在");
            }
            CpfrAgreement agr = agreementDao.selectById(fb.getAgreementId());
            if (agr == null || !supplierId.equals(agr.getSupplierId())) {
                throw new ServiceException(404, "反馈行不存在"); // 行级隔离默认拒绝
            }
        } else {
            String agreementId = CpfrAgreementServiceImpl.str(payload.get("agreementId"));
            String periodTag = CpfrAgreementServiceImpl.str(payload.get("periodTag"));
            Integer weekOffset = CpfrAgreementServiceImpl.inte(payload.get("weekOffset"));
            String itemCode = CpfrAgreementServiceImpl.str(payload.get("itemCode"));
            CpfrAgreement agr = agreementId == null ? null : agreementDao.selectById(agreementId);
            if (agr == null || !supplierId.equals(agr.getSupplierId())) {
                throw new ServiceException(404, "CPFR 协议不存在");
            }
            fb = feedbackDao.selectOne(new LambdaQueryWrapper<CpfrFeedback>()
                    .eq(CpfrFeedback::getAgreementId, agreementId)
                    .eq(CpfrFeedback::getPeriodTag, periodTag)
                    .eq(CpfrFeedback::getWeekOffset, weekOffset)
                    .eq(CpfrFeedback::getItemCode, itemCode));
            if (fb == null) {
                throw new ServiceException(404, "该周期无待反馈行（预测数据包未推送）");
            }
        }
        CpfrAgreement agr = agreementDao.selectById(fb.getAgreementId());
        CpfrAgreementServiceImpl.evaluateExpiryStatic(agr, clockService);
        if (!CpfrAgreement.ST_ACTIVE.equals(agr.getStatus())) {
            throw new ServiceException(422, "协议未启用或已暂停，禁止反馈");
        }
        BigDecimal supplyQty = CpfrAgreementServiceImpl.dec(payload.get("supplyQty"));
        if (supplyQty == null || supplyQty.signum() < 0) {
            throw new ServiceException(422, "可供数量必填且不为负");
        }
        fb.setSupplyQty(supplyQty);
        fb.setCapacityNote(CpfrAgreementServiceImpl.str(payload.get("capacityNote")));
        fb.setRiskNote(CpfrAgreementServiceImpl.str(payload.get("riskNote")));
        fb.setFeedbackSource(CpfrFeedback.SRC_PORTAL);
        fb.setStatus(CpfrFeedback.ST_DONE);
        fb.setSubmitAt(clockService.now());
        if (feedbackDao.updateById(fb) == 0) {
            throw new ServiceException(422, "反馈提交冲突，请重试");
        }
        // 提交即比对（BR-4.9-32）
        exceptionService.detect(new CpfrExceptionService.CpfrFeedbackRef(
                fb.getAgreementId(), fb.getPeriodTag(), fb.getWeekOffset(), fb.getItemCode(),
                fb.getDemandQty(), fb.getSupplyQty()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", fb.getId());
        m.put("status", fb.getStatus());
        m.put("submitAt", fb.getSubmitAt());
        return m;
    }

    @Override
    public List<Map<String, Object>> monitor(String agreementId, String periodTag) {
        if (StringUtils.hasText(agreementId) && StringUtils.hasText(periodTag)) {
            deadlineService.evaluate(agreementId, periodTag); // 惰性推进
        } else {
            deadlineService.evaluateAll();
        }
        List<CpfrFeedback> list = feedbackDao.selectList(new LambdaQueryWrapper<CpfrFeedback>()
                .eq(StringUtils.hasText(agreementId), CpfrFeedback::getAgreementId, agreementId)
                .eq(StringUtils.hasText(periodTag), CpfrFeedback::getPeriodTag, periodTag)
                .orderByAsc(CpfrFeedback::getPeriodTag)
                .orderByAsc(CpfrFeedback::getWeekOffset));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpfrFeedback fb : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", fb.getId());
            m.put("agreementId", fb.getAgreementId());
            m.put("periodTag", fb.getPeriodTag());
            m.put("weekOffset", fb.getWeekOffset());
            m.put("itemCode", fb.getItemCode());
            m.put("demandQty", fb.getDemandQty());
            m.put("supplyQty", fb.getSupplyQty());
            m.put("capacityNote", fb.getCapacityNote());
            m.put("riskNote", fb.getRiskNote());
            m.put("feedbackSource", fb.getFeedbackSource());
            m.put("status", fb.getStatus());
            m.put("submitAt", fb.getSubmitAt());
            m.put("periodStartAt", fb.getPeriodStartAt());
            m.put("remindAt", fb.getRemindAt());
            m.put("escalateAt", fb.getEscalateAt());
            m.put("escalateToRole", fb.getEscalateToRole());
            out.add(m);
        }
        return out;
    }
}
