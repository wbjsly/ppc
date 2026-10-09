package com.erp.service.impl.qms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.qms.GaugeCalibrationDao;
import com.erp.dao.qms.GaugeDao;
import com.erp.dao.qms.LotItemDao;
import com.erp.dao.qms.SuspectLotDao;
import com.erp.entity.qms.Gauge;
import com.erp.entity.qms.GaugeCalibration;
import com.erp.entity.qms.LotItem;
import com.erp.entity.qms.SuspectLot;
import com.erp.service.qms.GaugeService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计量器具与校准实现（spec gauge-calibration，tasks 10.6~10.7）。
 * CTQ 周期 ≤12 月；校准 FAIL → 停用 + 自上次合格校准日反向追溯可疑批次（PENDING_EVAL，
 * 未评估阻断放行 C-4.12-12 由 confirmRelease 校验）；30/7 天到期预警扫描幂等可查。
 */
@Slf4j
@Service
public class GaugeServiceImpl implements GaugeService {

    private final GaugeDao gaugeDao;
    private final GaugeCalibrationDao calDao;
    private final SuspectLotDao suspectDao;
    private final LotItemDao lotItemDao;

    public GaugeServiceImpl(GaugeDao gaugeDao, GaugeCalibrationDao calDao,
                            SuspectLotDao suspectDao, LotItemDao lotItemDao) {
        this.gaugeDao = gaugeDao;
        this.calDao = calDao;
        this.suspectDao = suspectDao;
        this.lotItemDao = lotItemDao;
    }

    // ================= 台账 =================

    @Override
    @Transactional
    public Gauge save(Map<String, Object> body) {
        requireRole("维护器具台账", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        String gaugeCode = str(body.get("gaugeCode"));
        if (!hasText(gaugeCode)) {
            throw new ServiceException(422, "器具编码必填");
        }
        if (!hasText(str(body.get("name")))) {
            throw new ServiceException(422, "器具名称必填");
        }
        int cycle = 12;
        try {
            cycle = body.get("calCycleMonths") == null ? 12
                    : Integer.parseInt(String.valueOf(body.get("calCycleMonths")));
        } catch (Exception e) {
            throw new ServiceException(422, "校准周期必须为数字（月）");
        }
        boolean ctq = "1".equals(str(body.get("ctqFlag")));
        if (ctq && cycle > 12) {
            throw new ServiceException(422, "CTQ 器具校准周期须 ≤12 个月（spec gauge-calibration）");
        }
        if (cycle < 1) {
            throw new ServiceException(422, "校准周期须 ≥1 个月");
        }

        String id = str(body.get("id"));
        Gauge g = hasText(id) ? gaugeDao.selectById(id) : null;
        if (g == null) {
            // 编码唯一
            long dup = gaugeDao.selectCount(new LambdaQueryWrapper<Gauge>()
                    .eq(Gauge::getGaugeCode, gaugeCode));
            if (dup > 0) {
                throw new ServiceException(422, "器具编码已存在：" + gaugeCode);
            }
            g = new Gauge();
            g.setGaugeCode(gaugeCode);
            g.setStatus("VALID");
            g.setCreateBy(SecurityUtils.getCurrentUserId());
        }
        g.setName(str(body.get("name")));
        g.setCategory(str(body.get("category")));
        g.setAccuracyLevel(str(body.get("accuracyLevel")));
        g.setCalCycleMonths(cycle);
        g.setCtqFlag(ctq ? "1" : "0");
        g.setDept(str(body.get("dept")));
        g.setLocation(str(body.get("location")));
        g.setOwnerName(str(body.get("ownerName")));
        g.setLimitScope(str(body.get("limitScope")));
        if (g.getNextCalDate() == null && body.get("nextCalDate") != null) {
            try {
                g.setNextCalDate(LocalDate.parse(String.valueOf(body.get("nextCalDate"))));
            } catch (Exception e) {
                throw new ServiceException(422, "下次校准日格式须为 yyyy-MM-dd");
            }
        }
        if (g.getId() == null) {
            gaugeDao.insert(g);
        } else if (gaugeDao.updateById(g) == 0) {
            throw new ServiceException(409, "器具更新冲突");
        }
        return g;
    }

    // ================= 10.6 校准执行 =================

    @Override
    @Transactional
    public GaugeCalibration calibrate(String gaugeId, Map<String, Object> body) {
        requireRole("执行校准", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        Gauge g = gaugeDao.selectById(gaugeId);
        if (g == null) {
            throw new ServiceException(404, "器具不存在：" + gaugeId);
        }
        String result = str(body.get("result"));
        if (!hasText(result) || !List.of("PASS", "FAIL", "LIMITED").contains(result)) {
            throw new ServiceException(422, "校准结果必填（PASS / FAIL / LIMITED）");
        }
        LocalDate calDate;
        try {
            calDate = hasText(str(body.get("calDate")))
                    ? LocalDate.parse(str(body.get("calDate"))) : LocalDate.now();
        } catch (Exception e) {
            throw new ServiceException(422, "校准日期格式须为 yyyy-MM-dd");
        }

        GaugeCalibration c = new GaugeCalibration();
        c.setGaugeId(g.getId());
        c.setPlanDate(g.getNextCalDate());
        c.setCalDate(calDate);
        c.setResult(result);
        c.setCertNo(str(body.get("certNo")));
        c.setCalOrg(str(body.get("calOrg")));
        c.setOperatorName(str(body.get("operatorName")));
        c.setRemark(str(body.get("remark")));
        c.setCreateBy(SecurityUtils.getCurrentUserId());

        if ("FAIL".equals(result)) {
            // 不合格 → 停用 + 反向追溯可疑批次（tasks 10.7）
            c.setNextCalDate(null);
            calDao.insert(c);
            g.setStatus("INVALID");
            g.setLastCalDate(calDate);
            if (gaugeDao.updateById(g) == 0) {
                throw new ServiceException(409, "器具更新冲突");
            }
            int traced = traceToSuspect(g, calDate);
            log.warn("gauge {} calibration FAIL → INVALID, suspect lots={}", g.getGaugeCode(), traced);
            return c;
        }

        // PASS / LIMITED：推算下次校准日（LIMITED 限用范围记 remark）
        LocalDate next = calDate.plusMonths(g.getCalCycleMonths() == null ? 12 : g.getCalCycleMonths());
        c.setNextCalDate(next);
        calDao.insert(c);
        g.setStatus("LIMITED".equals(result) ? "LIMITED" : "VALID");
        g.setLastCalDate(calDate);
        g.setNextCalDate(next);
        if (gaugeDao.updateById(g) == 0) {
            throw new ServiceException(409, "器具更新冲突");
        }
        log.info("gauge {} calibrated {} → next {}", g.getGaugeCode(), result, next);
        return c;
    }

    /** 自上次合格校准日以来用过该器具的检验项 → 可疑批次清单（幂等：已有可疑记录跳过） */
    private int traceToSuspect(Gauge g, LocalDate failDate) {
        LocalDate since = g.getLastCalDate() != null ? g.getLastCalDate() : failDate.minusMonths(6);
        List<LotItem> used = lotItemDao.selectList(new LambdaQueryWrapper<LotItem>()
                .eq(LotItem::getInstrumentCode, g.getGaugeCode())
                .ge(LotItem::getInspectTime, since.atStartOfDay())
                .le(LotItem::getInspectTime, failDate.plusDays(1).atStartOfDay()));
        int n = 0;
        for (LotItem it : used) {
            long dup = suspectDao.selectCount(new LambdaQueryWrapper<SuspectLot>()
                    .eq(SuspectLot::getGaugeId, g.getId())
                    .eq(SuspectLot::getLotId, it.getLotId()));
            if (dup > 0) {
                continue;
            }
            SuspectLot sl = new SuspectLot();
            sl.setGaugeId(g.getId());
            sl.setGaugeCode(g.getGaugeCode());
            sl.setLotId(it.getLotId());
            sl.setLotNo(null);
            sl.setItemCode(null);
            sl.setSuspectReason("器具 " + g.getGaugeCode() + " 校准不合格（" + failDate
                    + "），自上次合格校准 " + since + " 起使用过该器具的批次须重新评估");
            sl.setStatus("PENDING_EVAL");
            sl.setCreateBy(SecurityUtils.getCurrentUserId());
            suspectDao.insert(sl);
            n++;
        }
        return n;
    }

    // ================= 10.6 预警 =================

    @Override
    public Map<String, Object> sweepDue() {
        Map<String, Object> list = warningList();
        int due30 = ((List<?>) list.get("due30")).size();
        int due7 = ((List<?>) list.get("due7")).size();
        if (due30 > 0) {
            log.info("gauge due sweep: {} due within 30d ({} within 7d)", due30, due7);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("due30", due30);
        out.put("due7", due7);
        return out;
    }

    @Override
    public Map<String, Object> warningList() {
        LocalDate today = LocalDate.now();
        List<Gauge> all = gaugeDao.selectList(new LambdaQueryWrapper<Gauge>()
                .in(Gauge::getStatus, "VALID", "LIMITED")
                .isNotNull(Gauge::getNextCalDate)
                .orderByAsc(Gauge::getNextCalDate));
        List<Map<String, Object>> due30 = new ArrayList<>();
        List<Map<String, Object>> due7 = new ArrayList<>();
        for (Gauge g : all) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, g.getNextCalDate());
            if (days > 30) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("gaugeCode", g.getGaugeCode());
            m.put("name", g.getName());
            m.put("ctqFlag", g.getCtqFlag());
            m.put("nextCalDate", g.getNextCalDate());
            m.put("daysLeft", days);
            due30.add(m);
            if (days <= 7) {
                due7.add(m);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("due30", due30);
        out.put("due7", due7);
        return out;
    }

    // ================= 10.7 可疑批次 =================

    @Override
    public List<Map<String, Object>> suspectLots(String gaugeCode) {
        List<SuspectLot> list = suspectDao.selectList(new LambdaQueryWrapper<SuspectLot>()
                .eq(hasText(gaugeCode), SuspectLot::getGaugeCode, gaugeCode)
                .orderByDesc(SuspectLot::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate limit = LocalDate.now().minusDays(7); // 5 工作日 ≈ 7 自然天
        for (SuspectLot sl : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sl.getId());
            m.put("gaugeCode", sl.getGaugeCode());
            m.put("lotId", sl.getLotId());
            m.put("lotNo", sl.getLotNo());
            m.put("itemCode", sl.getItemCode());
            m.put("suspectReason", sl.getSuspectReason());
            m.put("status", sl.getStatus());
            m.put("evalConclusion", sl.getEvalConclusion());
            m.put("evalTime", sl.getEvalTime());
            boolean evalOverdue = "PENDING_EVAL".equals(sl.getStatus())
                    && sl.getCreateDate() != null && sl.getCreateDate().toLocalDate().isBefore(limit);
            m.put("evalOverdue", evalOverdue); // 5 工作日评估时限
            m.put("createDate", sl.getCreateDate());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional
    public SuspectLot evalSuspect(String id, String conclusion) {
        requireRole("可疑批次评估", "ROLE_QUALITY_ENG", "ROLE_QUALITY_MGR");
        SuspectLot sl = suspectDao.selectById(id);
        if (sl == null) {
            throw new ServiceException(404, "可疑批次记录不存在");
        }
        if (!"PENDING_EVAL".equals(sl.getStatus())) {
            throw new ServiceException(422, "已评估，不可重复操作：" + sl.getStatus());
        }
        if (!hasText(conclusion) || conclusion.trim().length() < 2) {
            throw new ServiceException(422, "评估结论必填（至少 2 字）");
        }
        sl.setStatus("EVALUATED");
        sl.setEvalBy(SecurityUtils.getCurrentUserId());
        sl.setEvalTime(LocalDateTime.now());
        sl.setEvalConclusion(conclusion);
        if (suspectDao.updateById(sl) == 0) {
            throw new ServiceException(409, "评估更新冲突");
        }
        return sl;
    }

    @Override
    public List<Map<String, Object>> traceLots(String gaugeCode, String fromDate) {
        if (!hasText(gaugeCode)) {
            throw new ServiceException(422, "器具编码必填");
        }
        LocalDateTime since;
        try {
            since = hasText(fromDate) ? LocalDate.parse(fromDate).atStartOfDay()
                    : LocalDateTime.now().minusMonths(6);
        } catch (Exception e) {
            throw new ServiceException(422, "起始日期格式须为 yyyy-MM-dd");
        }
        List<LotItem> used = lotItemDao.selectList(new LambdaQueryWrapper<LotItem>()
                .eq(LotItem::getInstrumentCode, gaugeCode)
                .ge(LotItem::getInspectTime, since)
                .orderByDesc(LotItem::getInspectTime));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LotItem it : used) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lotId", it.getLotId());
            m.put("itemId", it.getId());
            m.put("characteristicName", it.getCharacteristicName());
            m.put("measuredValue", it.getMeasuredValue());
            m.put("judge", it.getJudge());
            m.put("inspectBy", it.getInspectBy());
            m.put("inspectTime", it.getInspectTime());
            out.add(m);
        }
        return out;
    }

    // ================= 查询 =================

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String status) {
        LambdaQueryWrapper<Gauge> qw = new LambdaQueryWrapper<Gauge>()
                .eq(hasText(status), Gauge::getStatus, status)
                .and(hasText(keyword), w -> w.like(Gauge::getGaugeCode, keyword)
                        .or().like(Gauge::getName, keyword))
                .orderByAsc(Gauge::getNextCalDate);
        Page<Gauge> raw = gaugeDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> out = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Gauge g : raw.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.getId());
            m.put("gaugeCode", g.getGaugeCode());
            m.put("name", g.getName());
            m.put("category", g.getCategory());
            m.put("ctqFlag", g.getCtqFlag());
            m.put("calCycleMonths", g.getCalCycleMonths());
            m.put("status", g.getStatus());
            m.put("lastCalDate", g.getLastCalDate());
            m.put("nextCalDate", g.getNextCalDate());
            m.put("daysLeft", g.getNextCalDate() == null ? null
                    : java.time.temporal.ChronoUnit.DAYS.between(today, g.getNextCalDate()));
            m.put("ownerName", g.getOwnerName());
            m.put("dept", g.getDept());
            rows.add(m);
        }
        out.setRecords(rows);
        return out;
    }

    @Override
    public Map<String, Object> detail(String id) {
        Gauge g = gaugeDao.selectById(id);
        if (g == null) {
            throw new ServiceException(404, "器具不存在");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gauge", g);
        out.put("calibrations", calibrations(id));
        out.put("suspectLots", suspectLots(g.getGaugeCode()));
        return out;
    }

    @Override
    public List<GaugeCalibration> calibrations(String gaugeId) {
        return calDao.selectList(new LambdaQueryWrapper<GaugeCalibration>()
                .eq(GaugeCalibration::getGaugeId, gaugeId)
                .orderByDesc(GaugeCalibration::getCalDate));
    }

    private void requireRole(String action, String... allowed) {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth == null) {
            throw new ServiceException(401, "未登录");
        }
        List<String> userRoles = new ArrayList<>();
        auth.getAuthorities().forEach(a -> userRoles.add(a.getAuthority()));
        if (userRoles.stream().anyMatch(r -> "ROLE_ADMIN".equalsIgnoreCase(r))) {
            return;
        }
        for (String want : allowed) {
            for (String r : userRoles) {
                if (r.equalsIgnoreCase(want)) {
                    return;
                }
            }
        }
        throw new ServiceException(403, "当前角色无权" + action);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
