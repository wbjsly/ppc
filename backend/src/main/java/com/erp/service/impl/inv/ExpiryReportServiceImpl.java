package com.erp.service.impl.inv;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.inv.ExpiryReportDao;
import com.erp.entity.inv.ExpiryReport;
import com.erp.service.inv.ExpiryPriorityService;
import com.erp.service.inv.ExpiryReportService;
import com.erp.service.system.NoticeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 每日效期预警报告实现（4.10.1，spec expiry-management 需求①）。
 * 数据源复用 warnings() 全量清单（4.6.2 同源双视角，proposal D1）；
 * 与最近一份报告 diff 新增/解除；REPORT_DATE UK upsert 幂等；推送 ROLE_WAREHOUSE。
 */
@Slf4j
@Service
public class ExpiryReportServiceImpl implements ExpiryReportService {

    /** 单次全量取（warnings 内部按分页切片，给足上限） */
    private static final long FULL_SIZE = 100000;

    private final ExpiryReportDao reportDao;
    private final ExpiryPriorityService expiryPriorityService;
    private final NoticeService noticeService;
    private final ObjectMapper jsonMapper;

    public ExpiryReportServiceImpl(ExpiryReportDao reportDao,
                                   ExpiryPriorityService expiryPriorityService,
                                   NoticeService noticeService, ObjectMapper jsonMapper) {
        this.reportDao = reportDao;
        this.expiryPriorityService = expiryPriorityService;
        this.noticeService = noticeService;
        this.jsonMapper = jsonMapper;
    }

    @Override
    @Transactional
    public Map<String, Object> generateDailyReport() {
        // 手工补生成入口的写权限（调度路径无认证上下文，需放行）
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null
                && !auth.getName().startsWith("system")) {
            boolean allowed = auth.getAuthorities().stream().anyMatch(a -> {
                String r = a.getAuthority();
                return "ROLE_ADMIN".equalsIgnoreCase(r) || "ROLE_WAREHOUSE".equalsIgnoreCase(r);
            });
            if (!allowed) {
                throw new com.erp.common.ServiceException(403,
                        "生成预警报告：需要仓库主管或管理员角色");
            }
        }
        LocalDate today = LocalDate.now();
        // 全量清单（无筛选）
        Map<String, Object> w = expiryPriorityService.warnings(null, null, null, null, 1, FULL_SIZE);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) w.get("records");

        int yellow = 0;
        int orange = 0;
        int red = 0;
        int locked = 0;
        List<Map<String, Object>> detail = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String lv = String.valueOf(r.get("level"));
            switch (lv) {
                case "RED" -> red++;
                case "ORANGE" -> orange++;
                default -> yellow++;
            }
            if (Boolean.TRUE.equals(r.get("locked"))) {
                locked++;
            }
            detail.add(r);
        }

        // 与最近一份报告 diff（按报告日倒序取前一份；今日已有则取其前一份——覆盖场景）
        ExpiryReport prev = reportDao.selectOne(new LambdaQueryWrapper<ExpiryReport>()
                .lt(ExpiryReport::getReportDate, today)
                .orderByDesc(ExpiryReport::getReportDate)
                .last("LIMIT 1"));
        Map<String, Object> diff = diffWith(prev, detail);

        ExpiryReport todayRow = reportDao.selectOne(new LambdaQueryWrapper<ExpiryReport>()
                .eq(ExpiryReport::getReportDate, today));
        boolean isNew = todayRow == null;
        if (isNew) {
            todayRow = new ExpiryReport();
            todayRow.setReportDate(today);
        }
        todayRow.setYellowCnt(yellow);
        todayRow.setOrangeCnt(orange);
        todayRow.setRedCnt(red);
        todayRow.setLockedCnt(locked);
        todayRow.setDetailJson(toJson(detail));
        todayRow.setNewJson(String.valueOf(diff.get("newJson")));
        todayRow.setClearedJson(String.valueOf(diff.get("clearedJson")));
        todayRow.setNoBaseline(diff.get("noBaseline") != null
                && Boolean.TRUE.equals(diff.get("noBaseline")) ? "1" : "0");
        todayRow.setGenAt(LocalDateTime.now());
        todayRow.setGenBy("system:expiry-report");
        if (isNew) {
            reportDao.insert(todayRow);
        } else {
            reportDao.updateById(todayRow);
        }

        // 推送（notice 幂等：同 BIZ_TYPE+BIZ_ID 未读不重推；bizId=报告日支持每日一条）
        noticeService.push("ROLE_WAREHOUSE", null,
                "效期预警报告：" + today,
                "黄 " + yellow + " / 橙 " + orange + " / 红 " + red
                        + "，锁定批次 " + locked + "；较前报新增 "
                        + listSize(diff.get("new")) + " 条、解除 "
                        + listSize(diff.get("cleared")) + " 条。",
                "EXPIRY_REPORT", today.toString());
        log.info("expiry report {} generated: Y/O/R={}/{}/{} locked={}",
                today, yellow, orange, red, locked);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("reportDate", today.toString());
        out.put("yellowCnt", yellow);
        out.put("orangeCnt", orange);
        out.put("redCnt", red);
        out.put("lockedCnt", locked);
        out.put("noBaseline", diff.get("noBaseline"));
        return out;
    }

    @Override
    public List<Map<String, Object>> history(long size) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExpiryReport r : reportDao.selectList(new LambdaQueryWrapper<ExpiryReport>()
                .orderByDesc(ExpiryReport::getReportDate)
                .last("LIMIT " + Math.max(size, 1)))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("reportDate", r.getReportDate());
            m.put("yellowCnt", r.getYellowCnt());
            m.put("orangeCnt", r.getOrangeCnt());
            m.put("redCnt", r.getRedCnt());
            m.put("lockedCnt", r.getLockedCnt());
            m.put("noBaseline", "1".equals(r.getNoBaseline()));
            m.put("genAt", r.getGenAt());
            out.add(m);
        }
        return out;
    }

    @Override
    public Map<String, Object> detail(String reportDate) {
        if (reportDate == null || reportDate.isBlank()) {
            throw new ServiceException(422, "报告日期必填");
        }
        ExpiryReport r;
        try {
            r = reportDao.selectOne(new LambdaQueryWrapper<ExpiryReport>()
                    .eq(ExpiryReport::getReportDate, LocalDate.parse(reportDate.trim())));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ServiceException(422, "报告日期格式须为 yyyy-MM-dd");
        }
        if (r == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("reportDate", r.getReportDate());
        m.put("yellowCnt", r.getYellowCnt());
        m.put("orangeCnt", r.getOrangeCnt());
        m.put("redCnt", r.getRedCnt());
        m.put("lockedCnt", r.getLockedCnt());
        m.put("detail", parseJson(r.getDetailJson()));
        m.put("added", parseJson(r.getNewJson()));
        m.put("cleared", parseJson(r.getClearedJson()));
        m.put("noBaseline", "1".equals(r.getNoBaseline()));
        m.put("genAt", r.getGenAt());
        return m;
    }

    // ---------- diff ----------

    /**
     * 与前报按 "item|batch" 键 diff：当前在清单而前报不在 → 新增；前报在当前不在 → 解除。
     * 首日无前报：noBaseline=true，增减清单置空。
     */
    private Map<String, Object> diffWith(ExpiryReport prev, List<Map<String, Object>> current) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (prev == null) {
            out.put("noBaseline", true);
            out.put("new", List.of());
            out.put("cleared", List.of());
            out.put("newJson", "[]");
            out.put("clearedJson", "[]");
            return out;
        }
        Map<String, Map<String, Object>> prevMap = new LinkedHashMap<>();
        for (Map<String, Object> r : parseJson(prev.getDetailJson())) {
            prevMap.put(key(r), r);
        }
        Map<String, Map<String, Object>> curMap = new LinkedHashMap<>();
        for (Map<String, Object> r : current) {
            curMap.put(key(r), r);
        }
        List<Map<String, Object>> added = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : curMap.entrySet()) {
            if (!prevMap.containsKey(e.getKey())) {
                added.add(e.getValue());
            }
        }
        List<Map<String, Object>> cleared = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : prevMap.entrySet()) {
            if (!curMap.containsKey(e.getKey())) {
                cleared.add(e.getValue());
            }
        }
        out.put("noBaseline", false);
        out.put("new", added);
        out.put("cleared", cleared);
        out.put("newJson", toJson(added));
        out.put("clearedJson", toJson(cleared));
        return out;
    }

    private static String key(Map<String, Object> r) {
        return r.get("itemCode") + "|" + r.get("batchNo");
    }

    // ---------- helpers ----------

    private String toJson(Object o) {
        try {
            return jsonMapper.writeValueAsString(o);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new ServiceException(422, "报告序列化失败：" + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return jsonMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("expiry report json unparsable: {}", e.getMessage());
            return List.of();
        }
    }

    private static int listSize(Object o) {
        return o instanceof List<?> l ? l.size() : 0;
    }

    private static Object nvl(Object o) {
        return Objects.requireNonNullElse(o, "");
    }
}
