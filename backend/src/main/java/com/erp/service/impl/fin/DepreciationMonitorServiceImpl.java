package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.eam.EamAssetCardDao;
import com.erp.dao.eam.EamDepreciationDetailDao;
import com.erp.dao.eam.EamDepreciationRunDao;
import com.erp.dao.fin.FinVoucherDao;
import com.erp.eam.DepreciationEngine;
import com.erp.entity.eam.EamAssetCard;
import com.erp.entity.eam.EamDepreciationDetail;
import com.erp.entity.eam.EamDepreciationRun;
import com.erp.entity.fin.FinVoucher;
import com.erp.service.SysParamService;
import com.erp.service.fin.DepreciationMonitorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 财务域折旧监控实现（add-depreciation-monitoring，design D1/D2/D3）。
 * 只读：期间状态查 EAM 运行台账，残值预警实时扫在用卡（C-4.6-04 同源公式）；
 * 结账⑦两辅助（需计提卡数/是否有 POSTED 运行）供 gl-management 清单复用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DepreciationMonitorServiceImpl implements DepreciationMonitorService {

    /** 残值预警阈值参数（C-4.6-04；缺省 10%，与引擎默认一致） */
    private static final String PARAM_RESIDUAL_ALERT = "DEPRECIATION_RESIDUAL_ALERT";
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    private final EamDepreciationRunDao runDao;
    private final EamDepreciationDetailDao detailDao;
    private final EamAssetCardDao cardDao;
    private final FinVoucherDao voucherDao;
    private final SysParamService sysParamService;

    // ================================================================
    // M8-6-1 期间状态视图
    // ================================================================

    @Override
    public List<Map<String, Object>> periodStatuses() {
        List<EamDepreciationRun> runs = runDao.selectList(new LambdaQueryWrapper<EamDepreciationRun>()
                .eq(EamDepreciationRun::getDelFlag, "0")
                .orderByDesc(EamDepreciationRun::getPeriodTag));
        List<Map<String, Object>> out = new ArrayList<>();
        String current = LocalDate.now().format(YM);
        boolean currentListed = false;
        for (EamDepreciationRun r : runs) {
            if (current.equals(r.getPeriodTag())) {
                currentListed = true;
            }
            out.add(runRow(r));
        }
        // 当前期间无运行记录 → 未计提行（红字标识位 notRun=true，design D4 前端口径）
        if (!currentListed) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("period", current);
            m.put("status", "NOT_RUN");
            m.put("notRun", Boolean.TRUE);
            m.put("totalDepr", null);
            m.put("warningCount", null);
            m.put("reconDiff", null);
            m.put("voucherNo", null);
            m.put("voucherStatus", null);
            m.put("reversedFlag", "0");
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> runRow(EamDepreciationRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("period", r.getPeriodTag());
        boolean reversed = "1".equals(r.getReversedFlag()) || "REVERSED".equals(r.getStatus());
        m.put("status", reversed ? "REVERSED" : r.getStatus());
        m.put("notRun", Boolean.FALSE);
        m.put("totalDepr", r.getTotalDepr());
        m.put("warningCount", r.getWarningCount());
        m.put("reconDiff", r.getReconDiff());
        m.put("reversedFlag", r.getReversedFlag());
        m.put("runNo", r.getRunNo());
        FinVoucher v = hasText(r.getVoucherId()) ? voucherDao.selectById(r.getVoucherId()) : null;
        m.put("voucherNo", v == null ? null : v.getVoucherNo());
        m.put("voucherStatus", v == null ? null : v.getStatus());
        return m;
    }

    @Override
    public Map<String, Object> periodDetails(String periodTag) {
        List<EamDepreciationDetail> details = detailDao.selectList(
                new LambdaQueryWrapper<EamDepreciationDetail>()
                        .eq(EamDepreciationDetail::getPeriodTag, periodTag)
                        .eq(EamDepreciationDetail::getDelFlag, "0")
                        .orderByAsc(EamDepreciationDetail::getCardNo));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EamDepreciationDetail d : details) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cardNo", d.getCardNo());
            m.put("assetName", d.getAssetName());
            m.put("assetCost", d.getAssetCost());
            m.put("periodDepr", d.getPeriodDepr());
            m.put("endAccumDepr", d.getEndAccumDepr());
            m.put("endNbv", d.getEndNbv());
            m.put("residualAlert", "1".equals(d.getResidualAlert()));
            m.put("capped", "1".equals(d.getCapped()));
            m.put("reversed", "1".equals(d.getReversed()));
            rows.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", periodTag);
        out.put("items", rows);
        return out;
    }

    // ================================================================
    // M8-6-2 残值预警实时扫描（C-4.6-04）
    // ================================================================

    @Override
    public Map<String, Object> residualAlerts() {
        BigDecimal threshold = nvl(sysParamService.getAmount(PARAM_RESIDUAL_ALERT,
                DepreciationEngine.DEFAULT_RESIDUAL_ALERT_THRESHOLD));
        // 在用卡（排除 DISPOSED/REPLACED）；提足判定 accum+impairment < cost−residual（design D2）
        List<EamAssetCard> cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getDelFlag, "0")
                .eq(EamAssetCard::getStatus, "IN_USE"));
        List<Map<String, Object>> alerts = new ArrayList<>();
        int scanned = 0;
        for (EamAssetCard c : cards) {
            BigDecimal cost = nvl(c.getAssetCost());
            BigDecimal accum = nvl(c.getAccumDepr());
            BigDecimal impair = nvl(c.getImpairment());
            BigDecimal residual = c.getResidualValue() != null ? c.getResidualValue()
                    : cost.multiply(nvl(c.getResidualRate())).setScale(2, RoundingMode.HALF_UP);
            // 已提足排除：累计+减值已达提足线（NBV ≤ 残值）
            if (accum.add(impairment(impair)).compareTo(cost.subtract(residual)) >= 0) {
                continue;
            }
            scanned++;
            BigDecimal nbv = cost.subtract(accum).subtract(impair);
            if (nbv.signum() <= 0) {
                continue;
            }
            // C-4.6-04：残值 > NBV × 阈值
            if (residual.compareTo(nbv.multiply(threshold)) > 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("cardNo", c.getCardNo());
                m.put("assetName", c.getAssetName());
                m.put("assetCost", cost);
                m.put("accumDepr", accum);
                m.put("impairment", impair);
                m.put("nbv", nbv);
                m.put("residualValue", residual);
                m.put("residualRatio", nbv.signum() > 0
                        ? residual.divide(nbv, 4, RoundingMode.HALF_UP) : BigDecimal.ZERO);
                m.put("effectiveDate", c.getEffectiveDate() == null ? null : c.getEffectiveDate().toString());
                alerts.add(m);
            }
        }
        // 残值占比降序
        alerts.sort((a, b) -> ((BigDecimal) b.get("residualRatio"))
                .compareTo((BigDecimal) a.get("residualRatio")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("threshold", threshold);
        out.put("scannedCount", scanned);
        out.put("alertCount", alerts.size());
        out.put("items", alerts);
        return out;
    }

    // ================================================================
    // 结账⑦辅助（design D3：按需阻断判定）
    // ================================================================

    @Override
    public long countDepreciableCards(String periodTag) {
        // 与引擎选卡同源条件（design D3）：在用 + 未提足 + 已过起提月（BR-4.13-11 启用次月）
        List<EamAssetCard> cards = cardDao.selectList(new LambdaQueryWrapper<EamAssetCard>()
                .eq(EamAssetCard::getDelFlag, "0")
                .eq(EamAssetCard::getStatus, "IN_USE"));
        long n = 0;
        for (EamAssetCard c : cards) {
            if ("FULLY_DEPRECIATED".equals(c.getDeprStatus())) {
                continue;
            }
            BigDecimal cost = nvl(c.getAssetCost());
            BigDecimal accum = nvl(c.getAccumDepr());
            BigDecimal impair = nvl(c.getImpairment());
            BigDecimal residual = c.getResidualValue() != null ? c.getResidualValue()
                    : cost.multiply(nvl(c.getResidualRate())).setScale(2, RoundingMode.HALF_UP);
            if (accum.add(impair).compareTo(cost.subtract(residual)) >= 0) {
                continue;   // 已提足
            }
            String startMonth = hasText(c.getDeprStartMonth()) ? c.getDeprStartMonth()
                    : (c.getEffectiveDate() == null ? null
                            : DepreciationEngine.startMonth(c.getEffectiveDate()));
            if (!DepreciationEngine.isDepreciable(periodTag, startMonth)) {
                continue;   // 未到起提月（启用当月不计提）
            }
            n++;
        }
        return n;
    }

    @Override
    public boolean hasPostedRun(String periodTag) {
        // 注意：MySQL `col != '1'` 不命中 NULL 行——reversedFlag 判定放 Java 侧
        List<EamDepreciationRun> runs = runDao.selectList(new LambdaQueryWrapper<EamDepreciationRun>()
                .eq(EamDepreciationRun::getPeriodTag, periodTag)
                .eq(EamDepreciationRun::getStatus, "POSTED")
                .eq(EamDepreciationRun::getDelFlag, "0"));
        for (EamDepreciationRun r : runs) {
            if (!"1".equals(r.getReversedFlag())) {
                return true;
            }
        }
        return false;
    }

    private static BigDecimal impairment(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static boolean hasText(String v) {
        return v != null && !v.isBlank();
    }
}
