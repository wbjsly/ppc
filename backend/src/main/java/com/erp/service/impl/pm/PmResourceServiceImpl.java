package com.erp.service.impl.pm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.pm.PmCapacityDao;
import com.erp.dao.pm.PmWbsDao;
import com.erp.entity.pm.PmCapacity;
import com.erp.entity.pm.PmWbs;
import com.erp.service.pm.PmResourceService;
import com.erp.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 资源负载现算与产能日历（project-initiation，design D6）。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PmResourceServiceImpl implements PmResourceService {

    private static final int MAX_SPAN_DAYS = 366;

    private final PmWbsDao wbsDao;
    private final PmCapacityDao capacityDao;

    @Override
    public List<Map<String, Object>> loadView(String from, String to) {
        LocalDate start = parse(from);
        LocalDate end = parse(to);
        if (end.isBefore(start)) {
            throw new ServiceException(422, "结束日期不能早于开始日期");
        }
        long span = ChronoUnit.DAYS.between(start, end) + 1;
        if (span > MAX_SPAN_DAYS) {
            throw new ServiceException(422, "区间跨度上限 " + MAX_SPAN_DAYS + " 天");
        }
        // 任务级节点（有负责人 + 计划区间）
        List<PmWbs> tasks = wbsDao.selectList(new LambdaQueryWrapper<PmWbs>()
                .eq(PmWbs::getLevel, PmWbs.LV_TASK)
                .eq(PmWbs::getDelFlag, "0")
                .isNotNull(PmWbs::getOwnerUserId)
                .isNotNull(PmWbs::getPlanStart)
                .isNotNull(PmWbs::getPlanEnd));
        // 产能行（显式维护的覆盖默认值）
        Map<String, Map<LocalDate, BigDecimal>> capMap = new LinkedHashMap<>();
        for (PmCapacity c : capacityDao.selectList(new LambdaQueryWrapper<PmCapacity>()
                .ge(PmCapacity::getCapDate, start)
                .le(PmCapacity::getCapDate, end)
                .eq(PmCapacity::getDelFlag, "0"))) {
            capMap.computeIfAbsent(c.getUserId(), k -> new LinkedHashMap<>())
                    .put(c.getCapDate(), c.getHours());
        }
        // 人 × 日 负载累计；同时记录项目集合用于跨项目重叠判定
        Map<String, Map<LocalDate, BigDecimal>> loadMap = new LinkedHashMap<>();
        Map<String, Map<LocalDate, List<String>>> projMap = new LinkedHashMap<>();
        for (PmWbs t : tasks) {
            LocalDate ts = t.getPlanStart().isBefore(start) ? start : t.getPlanStart();
            LocalDate te = t.getPlanEnd().isAfter(end) ? end : t.getPlanEnd();
            if (ts.isAfter(te)) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(t.getPlanStart(), t.getPlanEnd()) + 1;
            if (days <= 0) {
                continue;
            }
            BigDecimal daily = (t.getEstHours() == null ? BigDecimal.ZERO : t.getEstHours())
                    .divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
            String owner = t.getOwnerUserId();
            for (LocalDate d = ts; !d.isAfter(te); d = d.plusDays(1)) {
                loadMap.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                        .merge(d, daily, BigDecimal::add);
                projMap.computeIfAbsent(owner, k -> new LinkedHashMap<>())
                        .computeIfAbsent(d, k -> new ArrayList<>())
                        .add(t.getProjectId());
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDate, BigDecimal>> e : loadMap.entrySet()) {
            String userId = e.getKey();
            List<Map<String, Object>> days = new ArrayList<>();
            boolean overCap = false;
            boolean crossProject = false;
            for (Map.Entry<LocalDate, BigDecimal> d : e.getValue().entrySet()) {
                BigDecimal capacity = capOf(userId, d.getKey(), capMap);
                boolean warn = d.getValue().compareTo(capacity) > 0;
                List<String> projects = projMap.getOrDefault(userId, Map.of())
                        .getOrDefault(d.getKey(), List.of());
                boolean cross = projects.stream().distinct().count() > 1;
                if (warn) {
                    overCap = true;
                }
                if (cross) {
                    crossProject = true;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", d.getKey().toString());
                row.put("loadHours", d.getValue());
                row.put("capacityHours", capacity);
                row.put("warning", warn || cross);
                row.put("reason", warn ? "超产能" : (cross ? "跨项目重叠" : null));
                row.put("projectIds", projects.stream().distinct().toList());
                days.add(row);
            }
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("userId", userId);
            user.put("days", days);
            user.put("warning", overCap || crossProject);
            user.put("warningReason", overCap ? "超产能" : (crossProject ? "跨项目重叠" : null));
            out.add(user);
        }
        return out;
    }

    @Override
    public List<Map<String, Object>> capacityList(String userId, String from, String to) {
        LambdaQueryWrapper<PmCapacity> qw = new LambdaQueryWrapper<PmCapacity>()
                .eq(PmCapacity::getDelFlag, "0")
                .orderByAsc(PmCapacity::getCapDate);
        if (userId != null && !userId.isBlank()) {
            qw.eq(PmCapacity::getUserId, userId);
        }
        if (from != null && !from.isBlank()) {
            qw.ge(PmCapacity::getCapDate, parse(from));
        }
        if (to != null && !to.isBlank()) {
            qw.le(PmCapacity::getCapDate, parse(to));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PmCapacity c : capacityDao.selectList(qw)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId());
            m.put("userId", c.getUserId());
            m.put("capDate", c.getCapDate().toString());
            m.put("hours", c.getHours());
            m.put("remark", c.getRemark());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> saveCapacity(Map<String, Object> payload) {
        String userId = payload.get("userId") == null ? null : String.valueOf(payload.get("userId")).trim();
        if (userId == null || userId.isBlank()) {
            throw new ServiceException(422, "人员必填");
        }
        String dateStr = payload.get("capDate") == null ? null : String.valueOf(payload.get("capDate")).trim();
        if (dateStr == null || dateStr.isBlank()) {
            throw new ServiceException(422, "日期必填");
        }
        LocalDate date = parse(dateStr);
        BigDecimal hours;
        try {
            hours = new BigDecimal(String.valueOf(payload.get("hours")).trim());
        } catch (Exception e) {
            throw new ServiceException(422, "工时格式非法");
        }
        if (hours.signum() < 0 || hours.compareTo(new BigDecimal("24")) > 0) {
            throw new ServiceException(422, "工时须在 0~24 之间");
        }
        PmCapacity row = capacityDao.selectOne(new LambdaQueryWrapper<PmCapacity>()
                .eq(PmCapacity::getUserId, userId)
                .eq(PmCapacity::getCapDate, date)
                .eq(PmCapacity::getDelFlag, "0")
                .last("LIMIT 1"));
        boolean isNew = row == null;
        if (isNew) {
            row = new PmCapacity();
            row.setId(UUID.randomUUID().toString().replace("-", ""));
            row.setUserId(userId);
            row.setCapDate(date);
            row.setCreateBy(SecurityUtils.getCurrentUserId());
            row.setCreateDate(LocalDateTime.now());
        }
        row.setHours(hours);
        row.setRemark(payload.get("remark") == null ? null : String.valueOf(payload.get("remark")));
        row.setUpdateBy(SecurityUtils.getCurrentUserId());
        row.setUpdateDate(LocalDateTime.now());
        if (isNew) {
            capacityDao.insert(row);
        } else {
            capacityDao.updateById(row);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", row.getId());
        m.put("userId", userId);
        m.put("capDate", date.toString());
        m.put("hours", hours);
        return m;
    }

    /** 产能：显式行优先；缺省 = 工作日 8 / 周末 0（design D6 默认推导） */
    private BigDecimal capOf(String userId, LocalDate date, Map<String, Map<LocalDate, BigDecimal>> capMap) {
        BigDecimal explicit = capMap.getOrDefault(userId, Map.of()).get(date);
        if (explicit != null) {
            return explicit;
        }
        int dow = date.getDayOfWeek().getValue();
        return dow >= 6 ? BigDecimal.ZERO : BigDecimal.valueOf(8);
    }

    private static LocalDate parse(String s) {
        if (s == null || s.isBlank()) {
            throw new ServiceException(422, "日期必填");
        }
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            throw new ServiceException(422, "日期格式非法：" + s);
        }
    }
}
