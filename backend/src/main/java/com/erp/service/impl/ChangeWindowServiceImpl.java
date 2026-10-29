package com.erp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.system.ChangeWindowDao;
import com.erp.entity.system.ChangeWindow;
import com.erp.service.ChangeWindowService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ChangeWindowServiceImpl implements ChangeWindowService {

    private final ChangeWindowDao windowDao;
    private final JdbcTemplate jdbc;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ChangeWindow w : windowDao.selectList(new LambdaQueryWrapper<ChangeWindow>()
                .orderByAsc(ChangeWindow::getDayOfWeek).orderByAsc(ChangeWindow::getStartTime))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", w.getId());
            m.put("name", w.getName());
            m.put("dayOfWeek", w.getDayOfWeek());
            m.put("startTime", w.getStartTime());
            m.put("endTime", w.getEndTime());
            m.put("status", w.getStatus());
            out.add(m);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(String id, String name, Integer dayOfWeek, String startTime, String endTime, String status) {
        if (!StringUtils.hasText(name)) {
            throw new ServiceException(422, "窗口名称必填");
        }
        if (dayOfWeek == null || dayOfWeek < DayOfWeek.MONDAY.getValue() || dayOfWeek > DayOfWeek.SUNDAY.getValue()) {
            throw new ServiceException(422, "星期须为 1-7（周一至周日）");
        }
        LocalTime start = parseTime(startTime, "开始时刻");
        LocalTime end = parseTime(endTime, "结束时刻");
        if (!start.isBefore(end)) {
            throw new ServiceException(422, "开始时刻须早于结束时刻");
        }
        if (!StringUtils.hasText(status) || (!"0".equals(status) && !"1".equals(status))) {
            throw new ServiceException(422, "状态仅支持 0/1");
        }
        ChangeWindow w;
        if (StringUtils.hasText(id)) {
            w = windowDao.selectById(id);
            if (w == null) {
                throw new ServiceException(404, "窗口不存在：" + id);
            }
        } else {
            w = new ChangeWindow();
            w.setId(UUID.randomUUID().toString().replace("-", ""));
        }
        w.setName(name.trim());
        w.setDayOfWeek(dayOfWeek);
        w.setStartTime(start.format(TIME_FMT));
        w.setEndTime(end.format(TIME_FMT));
        w.setStatus(status);
        if (w.getCreateDate() == null) {
            windowDao.insert(w);
        } else {
            w.setUpdateDate(LocalDateTime.now());
            windowDao.updateById(w);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        ChangeWindow w = windowDao.selectById(id);
        if (w == null) {
            throw new ServiceException(404, "窗口不存在：" + id);
        }
        windowDao.deleteById(id);
    }

    @Override
    public boolean isInWindow(LocalDateTime now) {
        int day = now.getDayOfWeek().getValue();
        String time = now.toLocalTime().format(TIME_FMT);
        Integer cnt = jdbc.queryForObject(
                "SELECT COUNT(*) FROM erp_change_window WHERE STATUS='1' AND DEL_FLAG='0' "
                        + "AND DAY_OF_WEEK=? AND START_TIME<=? AND END_TIME>?",
                Integer.class, day, time, time);
        return cnt != null && cnt > 0;
    }

    private LocalTime parseTime(String v, String label) {
        if (!StringUtils.hasText(v)) {
            throw new ServiceException(422, label + "必填");
        }
        try {
            return LocalTime.parse(v.trim().length() == 5 ? v.trim() + ":00" : v.trim(), TIME_FMT);
        } catch (Exception e) {
            throw new ServiceException(422, label + "须为 HH:mm 或 HH:mm:ss");
        }
    }
}
