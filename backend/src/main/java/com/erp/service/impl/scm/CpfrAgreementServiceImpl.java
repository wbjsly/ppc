package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.service.scm.CpfrAgreementService;
import com.erp.service.scm.CpfrClockService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPFR 协议服务（spec cpfr-collaboration R1）：
 * BR-4.9-31 五参数任一缺失禁启用（L1）；BR-4.9-34 到期自动暂停（惰性判定，design D2）。
 */
@Service
public class CpfrAgreementServiceImpl implements CpfrAgreementService {

    private final CpfrAgreementDao agreementDao;
    private final CpfrClockService clockService;

    public CpfrAgreementServiceImpl(CpfrAgreementDao agreementDao, CpfrClockService clockService) {
        this.agreementDao = agreementDao;
        this.clockService = clockService;
    }

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword) {
        LambdaQueryWrapper<CpfrAgreement> qw = new LambdaQueryWrapper<CpfrAgreement>()
                .like(StringUtils.hasText(keyword), CpfrAgreement::getSupplierName, keyword)
                .orderByDesc(CpfrAgreement::getCreateDate);
        Page<CpfrAgreement> page = agreementDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(page.getCurrent(), page.getTotal(), page.getSize());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (CpfrAgreement a : page.getRecords()) {
            evaluateExpiry(a); // 惰性到期判定（BR-4.9-34）
            Map<String, Object> m = toMap(a);
            m.put("missingParams", missingParams(a));
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> get(String id) {
        CpfrAgreement a = require(id);
        evaluateExpiry(a);
        Map<String, Object> m = toMap(a);
        m.put("missingParams", missingParams(a));
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> save(Map<String, Object> payload) {
        String id = str(payload.get("id"));
        CpfrAgreement a = StringUtils.hasText(id) ? require(id) : new CpfrAgreement();
        if (!StringUtils.hasText(id)) {
            String supplierId = str(payload.get("supplierId"));
            if (!StringUtils.hasText(supplierId)) {
                throw new ServiceException(422, "供应商必填");
            }
            long dup = agreementDao.selectCount(new LambdaQueryWrapper<CpfrAgreement>()
                    .eq(CpfrAgreement::getSupplierId, supplierId));
            if (dup > 0) {
                throw new ServiceException(422, "该供应商已存在 CPFR 协议");
            }
            a.setSupplierId(supplierId);
            a.setStatus(CpfrAgreement.ST_INIT);
        }
        if (payload.containsKey("supplierName")) a.setSupplierName(str(payload.get("supplierName")));
        if (payload.containsKey("cycleType")) a.setCycleType(str(payload.get("cycleType")));
        if (payload.containsKey("horizonWeeks")) a.setHorizonWeeks(inte(payload.get("horizonWeeks")));
        if (payload.containsKey("freezeWeeks")) a.setFreezeWeeks(inte(payload.get("freezeWeeks")));
        if (payload.containsKey("elasticStartWeek")) a.setElasticStartWeek(inte(payload.get("elasticStartWeek")));
        if (payload.containsKey("elasticEndWeek")) a.setElasticEndWeek(inte(payload.get("elasticEndWeek")));
        if (payload.containsKey("elasticPct")) a.setElasticPct(dec(payload.get("elasticPct")));
        if (payload.containsKey("deviationThresholdPct")) a.setDeviationThresholdPct(dec(payload.get("deviationThresholdPct")));
        if (payload.containsKey("expireDate")) {
            String d = str(payload.get("expireDate"));
            a.setExpireDate(StringUtils.hasText(d) ? LocalDate.parse(d) : null);
        }
        if (a.getDeviationThresholdPct() == null) {
            a.setDeviationThresholdPct(new BigDecimal("10"));
        }
        if (StringUtils.hasText(id)) {
            agreementDao.updateById(a);
        } else {
            agreementDao.insert(a);
        }
        return toMap(a);
    }

    @Override
    @Transactional
    public Map<String, Object> enable(String id) {
        CpfrAgreement a = require(id);
        List<String> missing = missingParams(a);
        if (!missing.isEmpty()) {
            throw new ServiceException(422, "参数未配置完整，禁止启用协同，缺失：" + String.join("、", missing));
        }
        if (a.getExpireDate() != null && !a.getExpireDate().isAfter(clockService.today())) {
            throw new ServiceException(422, "协议已到期，请先续签（BR-4.9-34）");
        }
        a.setStatus(CpfrAgreement.ST_ACTIVE);
        a.setPauseReason(null);
        agreementDao.updateById(a);
        return toMap(a);
    }

    @Override
    @Transactional
    public Map<String, Object> disable(String id) {
        CpfrAgreement a = require(id);
        a.setStatus(CpfrAgreement.ST_PAUSED);
        a.setPauseReason(CpfrAgreement.REASON_MANUAL);
        agreementDao.updateById(a);
        return toMap(a);
    }

    /** 到期自动暂停（BR-4.9-34，L3 惰性）：已启用且到期日 ≤ 今天 → 暂停 + 原因=到期 */
    void evaluateExpiry(CpfrAgreement a) {
        String before = a.getStatus();
        evaluateExpiryStatic(a, clockService);
        if (!before.equals(a.getStatus())) {
            agreementDao.updateById(a);
        }
    }

    /** 供外部服务复用的静态到期判定（含落库） */
    static void evaluateExpiryStatic(CpfrAgreement a, CpfrClockService clock) {
        if (CpfrAgreement.ST_ACTIVE.equals(a.getStatus())
                && a.getExpireDate() != null
                && !a.getExpireDate().isAfter(clock.today())) {
            a.setStatus(CpfrAgreement.ST_PAUSED);
            a.setPauseReason(CpfrAgreement.REASON_EXPIRE);
        }
    }

    /** BR-4.9-31 必备五参数：协同周期 / 展望期 / 冻结期 / 弹性区间（起+止+比例）/ 分歧阈值 */
    static List<String> missingParams(CpfrAgreement a) {
        List<String> miss = new ArrayList<>();
        if (!StringUtils.hasText(a.getCycleType())) miss.add("协同周期");
        if (a.getHorizonWeeks() == null || a.getHorizonWeeks() <= 0) miss.add("预测展望期");
        if (a.getFreezeWeeks() == null || a.getFreezeWeeks() <= 0) miss.add("冻结期");
        if (a.getElasticStartWeek() == null || a.getElasticEndWeek() == null
                || a.getElasticPct() == null
                || a.getElasticEndWeek() < a.getElasticStartWeek()) {
            miss.add("弹性区间");
        }
        if (a.getDeviationThresholdPct() == null || a.getDeviationThresholdPct().signum() <= 0) {
            miss.add("分歧阈值");
        }
        return miss;
    }

    CpfrAgreement require(String id) {
        CpfrAgreement a = agreementDao.selectById(id);
        if (a == null) {
            throw new ServiceException(404, "CPFR 协议不存在");
        }
        return a;
    }

    public static Map<String, Object> toMap(CpfrAgreement a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("supplierId", a.getSupplierId());
        m.put("supplierName", a.getSupplierName());
        m.put("cycleType", a.getCycleType());
        m.put("horizonWeeks", a.getHorizonWeeks());
        m.put("freezeWeeks", a.getFreezeWeeks());
        m.put("elasticStartWeek", a.getElasticStartWeek());
        m.put("elasticEndWeek", a.getElasticEndWeek());
        m.put("elasticPct", a.getElasticPct());
        m.put("deviationThresholdPct", a.getDeviationThresholdPct());
        m.put("status", a.getStatus());
        m.put("pauseReason", a.getPauseReason());
        m.put("expireDate", a.getExpireDate());
        m.put("lastPeriodTag", a.getLastPeriodTag());
        m.put("createDate", a.getCreateDate());
        return m;
    }

    static String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    static Integer inte(Object o) {
        if (o == null || !StringUtils.hasText(String.valueOf(o))) return null;
        return new BigDecimal(String.valueOf(o)).intValue();
    }

    static BigDecimal dec(Object o) {
        if (o == null || !StringUtils.hasText(String.valueOf(o))) return null;
        return new BigDecimal(String.valueOf(o));
    }
}
