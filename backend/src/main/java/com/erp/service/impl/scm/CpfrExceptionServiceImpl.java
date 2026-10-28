package com.erp.service.impl.scm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.scm.CpfrAdjustLogDao;
import com.erp.dao.scm.CpfrAgreementDao;
import com.erp.dao.scm.CpfrExceptionDao;
import com.erp.entity.scm.CpfrAdjustLog;
import com.erp.entity.scm.CpfrAgreement;
import com.erp.entity.scm.CpfrException;
import com.erp.service.scm.CpfrClockService;
import com.erp.service.scm.CpfrExceptionService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CPFR 分歧例外（spec cpfr-collaboration R4）：
 * BR-4.9-32 超阈值自动标记；两态闭环（供应商意见 → 计划员终确认）；
 * 每轮调整写 adjust_log（C-4.9-11 留痕）。
 */
@Service
public class CpfrExceptionServiceImpl implements CpfrExceptionService {

    private final CpfrExceptionDao exceptionDao;
    private final CpfrAgreementDao agreementDao;
    private final CpfrAdjustLogDao adjustLogDao;
    private final CpfrClockService clockService;

    public CpfrExceptionServiceImpl(CpfrExceptionDao exceptionDao,
                                    CpfrAgreementDao agreementDao,
                                    CpfrAdjustLogDao adjustLogDao,
                                    CpfrClockService clockService) {
        this.exceptionDao = exceptionDao;
        this.agreementDao = agreementDao;
        this.adjustLogDao = adjustLogDao;
        this.clockService = clockService;
    }

    @Override
    @Transactional
    public void detect(CpfrFeedbackRef ref) {
        if (ref.demandQty() == null || ref.demandQty().signum() <= 0 || ref.supplyQty() == null) {
            return; // 无需求基准不判定
        }
        CpfrAgreement agr = agreementDao.selectById(ref.agreementId());
        if (agr == null) {
            return;
        }
        BigDecimal threshold = agr.getDeviationThresholdPct() == null
                ? new BigDecimal("10") : agr.getDeviationThresholdPct();
        BigDecimal deviation = ref.supplyQty().subtract(ref.demandQty())
                .divide(ref.demandQty(), 4, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"))
                .setScale(2, RoundingMode.HALF_UP);
        if (deviation.abs().compareTo(threshold) <= 0) {
            return; // 阈值内不产生例外
        }
        CpfrException exists = exceptionDao.selectOne(new LambdaQueryWrapper<CpfrException>()
                .eq(CpfrException::getAgreementId, ref.agreementId())
                .eq(CpfrException::getPeriodTag, ref.periodTag())
                .eq(CpfrException::getWeekOffset, ref.weekOffset())
                .eq(CpfrException::getItemCode, ref.itemCode()));
        if (exists != null) {
            return; // 幂等
        }
        CpfrException ex = new CpfrException();
        ex.setAgreementId(ref.agreementId());
        ex.setPeriodTag(ref.periodTag());
        ex.setWeekOffset(ref.weekOffset());
        ex.setItemCode(ref.itemCode());
        ex.setDemandQty(ref.demandQty());
        ex.setSupplyQty(ref.supplyQty());
        ex.setDeviationPct(deviation);
        ex.setStatus(CpfrException.ST_OPEN);
        exceptionDao.insert(ex);
    }

    @Override
    public List<Map<String, Object>> list(String agreementId, String status) {
        List<CpfrException> list = exceptionDao.selectList(new LambdaQueryWrapper<CpfrException>()
                .eq(StringUtils.hasText(agreementId), CpfrException::getAgreementId, agreementId)
                .eq(StringUtils.hasText(status), CpfrException::getStatus, status)
                .orderByAsc(CpfrException::getPeriodTag)
                .orderByAsc(CpfrException::getWeekOffset));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpfrException ex : list) {
            Map<String, Object> m = toMap(ex);
            m.put("history", history(ex.getId()));
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public Map<String, Object> supplierOpinion(String supplierId, Map<String, Object> payload) {
        String exceptionId = CpfrAgreementServiceImpl.str(payload.get("exceptionId"));
        CpfrException ex = require(exceptionId);
        CpfrAgreement agr = agreementDao.selectById(ex.getAgreementId());
        if (agr == null || !supplierId.equals(agr.getSupplierId())) {
            throw new ServiceException(404, "例外项不存在"); // 行级隔离
        }
        if (!CpfrException.ST_OPEN.equals(ex.getStatus())) {
            throw new ServiceException(422, "例外项已关闭，不可再提交意见");
        }
        BigDecimal proposed = CpfrAgreementServiceImpl.dec(payload.get("proposedQty"));
        if (proposed == null || proposed.signum() < 0) {
            throw new ServiceException(422, "调整数量必填且不为负");
        }
        writeLog(CpfrAdjustLog.TT_EXCEPTION, ex.getId(), ex.getAgreementId(), ex.getPeriodTag(),
                CpfrAdjustLog.ACT_SUPPLIER_OPINION, ex.getSupplyQty(), proposed,
                CpfrAgreementServiceImpl.str(payload.get("remark")));
        // 供应商意见更新当前供给（待计划员终确认）
        ex.setSupplyQty(proposed);
        exceptionDao.updateById(ex);
        Map<String, Object> m = toMap(ex);
        m.put("history", history(ex.getId()));
        return m;
    }

    @Override
    @Transactional
    public Map<String, Object> finalConfirm(String exceptionId, boolean accept, String remark) {
        CpfrException ex = require(exceptionId);
        if (!CpfrException.ST_OPEN.equals(ex.getStatus())) {
            throw new ServiceException(422, "例外项已关闭");
        }
        if (!StringUtils.hasText(remark)) {
            throw new ServiceException(422, "终确认须填写意见（协商留痕）");
        }
        ex.setStatus(accept ? CpfrException.ST_AGREED : CpfrException.ST_REJECTED);
        ex.setClosedAt(clockService.now());
        ex.setCloseBy(currentUser());
        exceptionDao.updateById(ex);
        writeLog(CpfrAdjustLog.TT_EXCEPTION, ex.getId(), ex.getAgreementId(), ex.getPeriodTag(),
                CpfrAdjustLog.ACT_PLAN_CONFIRM, null, ex.getSupplyQty(),
                (accept ? "接受：" : "驳回：") + remark);
        Map<String, Object> m = toMap(ex);
        m.put("history", history(ex.getId()));
        return m;
    }

    @Override
    public List<Map<String, Object>> portalList(String supplierId) {
        List<CpfrAgreement> agreements = agreementDao.selectList(new LambdaQueryWrapper<CpfrAgreement>()
                .eq(CpfrAgreement::getSupplierId, supplierId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpfrAgreement a : agreements) {
            List<CpfrException> list = exceptionDao.selectList(new LambdaQueryWrapper<CpfrException>()
                    .eq(CpfrException::getAgreementId, a.getId())
                    .eq(CpfrException::getStatus, CpfrException.ST_OPEN)
                    .orderByAsc(CpfrException::getPeriodTag));
            for (CpfrException ex : list) {
                Map<String, Object> m = toMap(ex);
                m.put("history", history(ex.getId()));
                out.add(m);
            }
        }
        return out;
    }

    /** 未达成例外数（计划确认前置校验用） */
    public long openCount(String agreementId, String periodTag) {
        return exceptionDao.selectCount(new LambdaQueryWrapper<CpfrException>()
                .eq(CpfrException::getAgreementId, agreementId)
                .eq(CpfrException::getPeriodTag, periodTag)
                .eq(CpfrException::getStatus, CpfrException.ST_OPEN));
    }

    CpfrException require(String id) {
        CpfrException ex = exceptionDao.selectById(id);
        if (ex == null) {
            throw new ServiceException(404, "例外项不存在");
        }
        return ex;
    }

    void writeLog(String targetType, String targetId, String agreementId, String periodTag,
                  String action, BigDecimal oldQty, BigDecimal newQty, String remark) {
        CpfrAdjustLog log = new CpfrAdjustLog();
        log.setTargetType(targetType);
        log.setTargetId(targetId);
        log.setAgreementId(agreementId);
        log.setPeriodTag(periodTag);
        log.setAction(action);
        log.setOldQty(oldQty);
        log.setNewQty(newQty);
        log.setOperator(currentUser());
        log.setOperatorRole(currentRole());
        log.setRemark(remark);
        log.setOperatedAt(clockService.now());
        adjustLogDao.insert(log);
    }

    List<Map<String, Object>> history(String exceptionId) {
        List<CpfrAdjustLog> logs = adjustLogDao.selectList(new LambdaQueryWrapper<CpfrAdjustLog>()
                .eq(CpfrAdjustLog::getTargetType, CpfrAdjustLog.TT_EXCEPTION)
                .eq(CpfrAdjustLog::getTargetId, exceptionId)
                .orderByAsc(CpfrAdjustLog::getOperatedAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (CpfrAdjustLog l : logs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("action", l.getAction());
            m.put("oldQty", l.getOldQty());
            m.put("newQty", l.getNewQty());
            m.put("operator", l.getOperator());
            m.put("operatorRole", l.getOperatorRole());
            m.put("remark", l.getRemark());
            m.put("operatedAt", l.getOperatedAt());
            out.add(m);
        }
        return out;
    }

    static Map<String, Object> toMap(CpfrException ex) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ex.getId());
        m.put("agreementId", ex.getAgreementId());
        m.put("periodTag", ex.getPeriodTag());
        m.put("weekOffset", ex.getWeekOffset());
        m.put("itemCode", ex.getItemCode());
        m.put("demandQty", ex.getDemandQty());
        m.put("supplyQty", ex.getSupplyQty());
        m.put("deviationPct", ex.getDeviationPct());
        m.put("status", ex.getStatus());
        m.put("closedAt", ex.getClosedAt());
        m.put("closeBy", ex.getCloseBy());
        return m;
    }

    static String currentUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    static String currentRole() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities() == null) {
            return null;
        }
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .reduce((a, b) -> a + "," + b).orElse(null);
    }
}
