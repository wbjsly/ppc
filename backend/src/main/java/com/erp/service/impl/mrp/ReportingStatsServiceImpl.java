package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.dao.mrp.MrpMoOpDao;
import com.erp.entity.mrp.MrpMoOp;
import com.erp.service.SysParamService;
import com.erp.service.mrp.ReportingService;
import com.erp.service.mrp.ReportingStatsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * 良率统计实现（design D2 唯一口径：累计侧复用 {@link ReportingService#confirmedTotals}
 * ——报工提交/完工校验/冻结挂点三处消费同一数据源；标准侧取工单工序快照 STANDARD_YIELD）。
 */
@Slf4j
@Service
public class ReportingStatsServiceImpl implements ReportingStatsService {

    private final ReportingService reportingService;
    private final MrpMoOpDao moOpDao;
    private final SysParamService sysParam;

    public ReportingStatsServiceImpl(ReportingService reportingService, MrpMoOpDao moOpDao,
                                     SysParamService sysParam) {
        this.reportingService = reportingService;
        this.moOpDao = moOpDao;
        this.sysParam = sysParam;
    }

    @Override
    public Map<String, Object> yieldOf(String moId, Integer opSeq) {
        Map<String, Object> totals = reportingService.confirmedTotals(moId, opSeq);
        BigDecimal input = (BigDecimal) totals.get("inputQty");
        BigDecimal good = (BigDecimal) totals.get("goodQty");
        BigDecimal ng = (BigDecimal) totals.get("ngQty");

        BigDecimal standardYield = standardYieldOf(moId, opSeq);
        BigDecimal actual = null;
        BigDecimal deviation = null;
        if (input != null && input.signum() > 0) {
            // 实际良率 = 合格 / 累计投入 × 100%（保留 2 位，规格例题 32/35=91.4%）
            actual = good.multiply(BigDecimal.valueOf(100))
                    .divide(input, 2, RoundingMode.HALF_UP);
            if (standardYield != null && standardYield.signum() > 0) {
                // 偏差 = |实际 − 标准| / 标准 × 100%
                deviation = actual.subtract(standardYield).abs()
                        .multiply(BigDecimal.valueOf(100))
                        .divide(standardYield, 2, RoundingMode.HALF_UP);
            }
        }
        Map<String, Object> out = new HashMap<>();
        out.put("inputQty", input);
        out.put("goodQty", good);
        out.put("ngQty", ng);
        out.put("actualYield", actual);
        out.put("standardYield", standardYield);
        out.put("deviation", deviation);
        out.put("rptCount", totals.get("rptCount"));
        return out;
    }

    @Override
    public Map<String, Object> deviationExceeded(String moId, Integer opSeq) {
        Map<String, Object> y = yieldOf(moId, opSeq);
        BigDecimal deviation = (BigDecimal) y.get("deviation");
        BigDecimal tolerance = new BigDecimal(sysParam.getValue("YIELD_TOLERANCE", "10"));
        boolean exceeded = deviation != null && deviation.compareTo(tolerance) > 0;
        Map<String, Object> out = new HashMap<>();
        out.put("exceeded", exceeded);
        out.put("tolerance", tolerance);
        out.put("actualYield", y.get("actualYield"));
        out.put("standardYield", y.get("standardYield"));
        out.put("deviation", deviation);
        if (!exceeded) {
            log.debug("良率未超阈值 mo={} op={} deviation={} tolerance={}",
                    moId, opSeq, deviation, tolerance);
        }
        return out;
    }

    /** 快照标准良率：无快照行或未维护 → null（不判定，不误冻结） */
    private BigDecimal standardYieldOf(String moId, Integer opSeq) {
        MrpMoOp op = moOpDao.selectOne(new LambdaQueryWrapper<MrpMoOp>()
                .eq(MrpMoOp::getMoId, moId)
                .eq(opSeq != null, MrpMoOp::getOpSeq, opSeq)
                .last("LIMIT 1"));
        return op == null ? null : op.getStandardYield();
    }
}
