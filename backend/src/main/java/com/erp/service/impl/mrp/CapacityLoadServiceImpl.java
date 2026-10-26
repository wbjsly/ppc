package com.erp.service.impl.mrp;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.mrp.MrpDispatchDao;
import com.erp.entity.mrp.MrpDispatch;
import com.erp.service.SysParamService;
import com.erp.service.mrp.CapacityLoadService;
import com.erp.service.system.WorkCalendarService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 产能负荷计算实现（change add-dispatch-management，design D8）。
 * 实时聚合：RELEASED 派工单按 [PLAN_START, PLAN_END] 跨日均摊工时（含非工作日，
 * 保证非工作日有占用算 999——spec capacity-load 场景），除以日历班次可用工时。
 */
@Slf4j
@Service
public class CapacityLoadServiceImpl implements CapacityLoadService {

    /** 区间上限（防爆炸：日期 × WC 笛卡尔积） */
    private static final int MAX_RANGE_DAYS = 186;
    /** 无可用时段且有占用的占位负荷率（spec：除零保护 999） */
    private static final BigDecimal NO_CAPACITY_LOAD = new BigDecimal("999");

    private final MrpDispatchDao dispatchDao;
    private final WorkCalendarService calendarService;
    private final SysParamService sysParam;

    public CapacityLoadServiceImpl(MrpDispatchDao dispatchDao,
                                   WorkCalendarService calendarService,
                                   SysParamService sysParam) {
        this.dispatchDao = dispatchDao;
        this.calendarService = calendarService;
        this.sysParam = sysParam;
    }

    @Override
    public List<LoadRow> loads(LocalDate from, LocalDate to, String wcCode) {
        if (from == null || to == null) {
            throw new ServiceException(422, "负荷查询日期区间必填");
        }
        if (to.isBefore(from)) {
            throw new ServiceException(422, "结束日期不得早于开始日期");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new ServiceException(422, "负荷查询区间不得超过 " + MAX_RANGE_DAYS + " 天");
        }
        // 区间内 RELEASED 派工单（起始落区间 ≤ to，跨入区间过滤在 Java 侧做——
        // PLAN_END 可空，SQL 侧三元列不可用）
        List<MrpDispatch> dispatches = new ArrayList<>(dispatchDao.selectList(
                new LambdaQueryWrapper<MrpDispatch>()
                        .eq(MrpDispatch::getStatus, MrpDispatch.ST_RELEASED)
                        .le(MrpDispatch::getPlanStart, to)));
        dispatches.removeIf(d -> d.getPlanStart() != null
                && effectiveEnd(d).isBefore(from));
        Set<String> wcs = new LinkedHashSet<>();
        if (wcCode != null && !wcCode.isEmpty()) {
            wcs.add(wcCode);
        } else {
            for (MrpDispatch d : dispatches) {
                wcs.add(d.getWcCode());
            }
        }
        BigDecimal trigger = new BigDecimal(sysParam.getValue("CAPACITY_LOAD_TRIGGER", "110"));

        List<LoadRow> out = new ArrayList<>();
        for (String wc : wcs) {
            List<MrpDispatch> mine = new ArrayList<>();
            for (MrpDispatch d : dispatches) {
                if (wc.equals(d.getWcCode())) {
                    mine.add(d);
                }
            }
            for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
                BigDecimal assigned = assignedOn(mine, day, wc);
                BigDecimal available = calendarService.availableHours(day, wc);
                out.add(new LoadRow(wc, day, assigned, available,
                        rateOf(assigned, available),
                        rateOf(assigned, available).compareTo(trigger) > 0));
            }
        }
        out.sort((a, b) -> {
            int c = a.date().compareTo(b.date());
            return c != 0 ? c : a.wcCode().compareTo(b.wcCode());
        });
        return out;
    }

    /** 派工占用结束日（PLAN_END 空/倒挂 → PLAN_START） */
    private LocalDate effectiveEnd(MrpDispatch d) {
        if (d.getPlanEnd() == null || d.getPlanEnd().isBefore(d.getPlanStart())) {
            return d.getPlanStart();
        }
        return d.getPlanEnd();
    }

    /**
     * 某日占用：区间内派工单工时按 [PLAN_START, PLAN_END] 含头尾天数跨日均摊
     * （v1 口径见接口注释；PLAN_END 空 → 全额落 PLAN_START）
     */
    private BigDecimal assignedOn(List<MrpDispatch> dispatches, LocalDate day, String wc) {
        BigDecimal sum = BigDecimal.ZERO;
        for (MrpDispatch d : dispatches) {
            if (d.getPlanStart() == null || d.getDurationHours() == null) {
                continue;
            }
            LocalDate end = effectiveEnd(d);
            if (day.isBefore(d.getPlanStart()) || day.isAfter(end)) {
                continue;
            }
            long span = ChronoUnit.DAYS.between(d.getPlanStart(), end) + 1;
            sum = sum.add(d.getDurationHours().divide(BigDecimal.valueOf(span), 4,
                    RoundingMode.HALF_UP));
        }
        return sum;
    }

    /** 负荷率：无可用 → 有占用 999 / 无占用 0；有可用 → 占用÷可用×100（1 位小数） */
    private BigDecimal rateOf(BigDecimal assigned, BigDecimal available) {
        if (available.signum() <= 0) {
            return assigned.signum() > 0 ? NO_CAPACITY_LOAD : BigDecimal.ZERO;
        }
        return assigned.multiply(BigDecimal.valueOf(100))
                .divide(available, 1, RoundingMode.HALF_UP);
    }
}
