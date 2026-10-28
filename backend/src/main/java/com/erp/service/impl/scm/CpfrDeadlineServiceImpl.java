package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.dao.scm.CpfrFeedbackDao;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.entity.scm.CpfrFeedback;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.scm.CpfrDeadlineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CPFR 时限惰性判定（spec cpfr-collaboration R3，BR-4.9-33）：
 * 48h 催办 / 72h 升级采购经理（ROLE_PMGR）+ 按上轮反馈量滚动「默认采纳」。
 * 幂等：REMIND_AT / ESCALATE_AT 时间戳为标记，重复 evaluate 不重复推进（design D2）。
 */
@Slf4j
@Service
public class CpfrDeadlineServiceImpl implements CpfrDeadlineService {

    private final CpfrFeedbackDao feedbackDao;
    private final CpfrAgreementDao agreementDao;
    private final CpfrClockService clockService;

    @Value("${app.scm.cpfr-remind-hours:48}")
    private long remindHours;

    @Value("${app.scm.cpfr-escalate-hours:72}")
    private long escalateHours;

    public CpfrDeadlineServiceImpl(CpfrFeedbackDao feedbackDao,
                                   CpfrAgreementDao agreementDao,
                                   CpfrClockService clockService) {
        this.feedbackDao = feedbackDao;
        this.agreementDao = agreementDao;
        this.clockService = clockService;
    }

    @Override
    @Transactional
    public Map<String, Object> evaluate(String agreementId, String periodTag) {
        List<CpfrFeedback> pending = feedbackDao.selectList(new LambdaQueryWrapper<CpfrFeedback>()
                .eq(CpfrFeedback::getAgreementId, agreementId)
                .eq(CpfrFeedback::getPeriodTag, periodTag)
                .in(CpfrFeedback::getStatus,
                        CpfrFeedback.ST_PENDING, CpfrFeedback.ST_REMINDED, CpfrFeedback.ST_ESCALATED));
        Map<String, Object> out = new HashMap<>();
        out.put("reminded", 0);
        out.put("escalated", 0);
        out.put("defaulted", 0);
        LocalDateTime now = clockService.now();
        for (CpfrFeedback fb : pending) {
            if (fb.getPeriodStartAt() == null) {
                continue;
            }
            long hours = java.time.Duration.between(fb.getPeriodStartAt(), now).toHours();
            if (hours >= escalateHours && !CpfrFeedback.ST_DEFAULT.equals(fb.getStatus())) {
                // 72h：升级 + 默认采纳（上轮量滚动）
                if (fb.getEscalateAt() == null) {
                    fb.setStatus(CpfrFeedback.ST_ESCALATED);
                    fb.setEscalateAt(now);
                    fb.setEscalateToRole("ROLE_PMGR");
                    feedbackDao.updateById(fb);
                    out.put("escalated", (int) out.get("escalated") + 1);
                }
                applyDefaultRoll(fb, now);
                out.put("defaulted", (int) out.get("defaulted") + 1);
            } else if (hours >= remindHours && CpfrFeedback.ST_PENDING.equals(fb.getStatus())) {
                fb.setStatus(CpfrFeedback.ST_REMINDED);
                fb.setRemindAt(now);
                feedbackDao.updateById(fb);
                out.put("reminded", (int) out.get("reminded") + 1);
            }
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> evaluateAll() {
        List<CpfrAgreement> active = agreementDao.selectList(new LambdaQueryWrapper<CpfrAgreement>()
                .eq(CpfrAgreement::getStatus, CpfrAgreement.ST_ACTIVE));
        Map<String, Object> total = new HashMap<>();
        total.put("reminded", 0);
        total.put("escalated", 0);
        total.put("defaulted", 0);
        for (CpfrAgreement a : active) {
            CpfrAgreementServiceImpl.evaluateExpiryStatic(a, clockService);
            if (!CpfrAgreement.ST_ACTIVE.equals(a.getStatus())) {
                agreementDao.updateById(a); // 落库暂停（BR-4.9-34）
                continue;
            }
            List<String> periods = feedbackDao.selectList(new LambdaQueryWrapper<CpfrFeedback>()
                            .eq(CpfrFeedback::getAgreementId, a.getId())
                            .select(CpfrFeedback::getPeriodTag))
                    .stream().map(CpfrFeedback::getPeriodTag).distinct().toList();
            for (String p : periods) {
                Map<String, Object> r = evaluate(a.getId(), p);
                for (String k : List.of("reminded", "escalated", "defaulted")) {
                    total.put(k, (int) total.get(k) + (int) r.get(k));
                }
            }
        }
        return total;
    }

    /** 按上一轮反馈量滚动默认采纳（无上轮 → 0），标注来源 DEFAULT_ROLL（BR-4.9-33） */
    private void applyDefaultRoll(CpfrFeedback fb, LocalDateTime now) {
        BigDecimal lastQty = lastSupply(fb);
        fb.setStatus(CpfrFeedback.ST_DEFAULT);
        fb.setSupplyQty(lastQty);
        fb.setFeedbackSource(CpfrFeedback.SRC_DEFAULT_ROLL);
        fb.setSubmitAt(now);
        feedbackDao.updateById(fb);
        log.info("CPFR 默认采纳：协议 {} 周期 {} W{} {} 上轮量={}",
                fb.getAgreementId(), fb.getPeriodTag(), fb.getWeekOffset(), fb.getItemCode(), lastQty);
    }

    /** 上一轮（周期标签最小但小于当前）同 SKU 同周偏移的反馈量 */
    private BigDecimal lastSupply(CpfrFeedback fb) {
        List<CpfrFeedback> prev = feedbackDao.selectList(new LambdaQueryWrapper<CpfrFeedback>()
                .eq(CpfrFeedback::getAgreementId, fb.getAgreementId())
                .eq(CpfrFeedback::getItemCode, fb.getItemCode())
                .eq(CpfrFeedback::getWeekOffset, fb.getWeekOffset())
                .ne(CpfrFeedback::getPeriodTag, fb.getPeriodTag())
                .in(CpfrFeedback::getStatus, CpfrFeedback.ST_DONE, CpfrFeedback.ST_DEFAULT)
                .isNotNull(CpfrFeedback::getSupplyQty)
                .orderByDesc(CpfrFeedback::getPeriodTag)
                .last("LIMIT 1"));
        return prev.isEmpty() || prev.get(0).getSupplyQty() == null
                ? BigDecimal.ZERO : prev.get(0).getSupplyQty();
    }
}
