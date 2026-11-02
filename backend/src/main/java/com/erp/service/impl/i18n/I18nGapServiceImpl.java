package com.erp.service.impl.i18n;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.i18n.I18nGapDao;
import com.erp.entity.i18n.I18nGap;
import com.erp.service.i18n.I18nGapService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 翻译缺口实现（add-i18n-l10n，BR-5.7-05 批量回传 UPSERT 累加）。
 * 高频升级：周命中 >500 自动升 P1（SOP-5.7-A 步7）。
 */
@Slf4j
@Service
public class I18nGapServiceImpl implements I18nGapService {

    private static final int HIGH_FREQ = 500;

    private final I18nGapDao gapDao;
    private final JdbcTemplate jdbc;

    public I18nGapServiceImpl(I18nGapDao gapDao, JdbcTemplate jdbc) {
        this.gapDao = gapDao;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int reportBatch(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            throw new ServiceException(422, "回传列表为空");
        }
        int n = 0;
        for (Map<String, Object> it : items) {
            String keyName = str(it.get("keyName"));
            if (keyName == null || keyName.isBlank()) {
                continue;
            }
            int hits = it.get("hitCount") == null ? 1 : toInt(it.get("hitCount"));
            I18nGap exist = gapDao.selectOne(new LambdaQueryWrapper<I18nGap>()
                    .eq(I18nGap::getKeyName, keyName).last("LIMIT 1"));
            if (exist == null) {
                I18nGap g = new I18nGap();
                g.setKeyName(keyName);
                g.setModule(str(it.get("moduleName")));
                g.setPriority(str(it.getOrDefault("priority", "P2")));
                g.setFirstHitAt(parseTime(it.get("firstHitAt")));
                g.setHitCount(hits);
                g.setStatus(I18nGap.ST_OPEN);
                g.setCreateBy("i18n-collector");
                g.setCreateDate(LocalDateTime.now());
                // 周命中 >500 直接 P1（SOP-5.7-A 步7）
                if (hits > HIGH_FREQ && !"P0".equals(g.getPriority()) && !"P1".equals(g.getPriority())) {
                    g.setPriority("P1");
                }
                gapDao.insert(g);
            } else {
                // UPSERT 累加：FIRST_HIT_AT 不变
                exist.setHitCount((exist.getHitCount() == null ? 0 : exist.getHitCount()) + hits);
                if (exist.getHitCount() > HIGH_FREQ && "P2".equals(exist.getPriority())) {
                    exist.setPriority("P1");
                }
                exist.setUpdateBy("i18n-collector");
                exist.setUpdateDate(LocalDateTime.now());
                gapDao.updateById(exist);
            }
            n++;
        }
        return n;
    }

    @Override
    public List<Map<String, Object>> list(String status, String priority, int limit) {
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, KEY_NAME AS keyName, MODULE AS module, PRIORITY AS priority, "
                        + "FIRST_HIT_AT AS firstHitAt, HIT_COUNT AS hitCount, STATUS AS status, "
                        + "CLOSED_AT AS closedAt FROM erp_i18n_gap WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND STATUS=?");
            args.add(status.trim());
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND PRIORITY=?");
            args.add(priority.trim());
        }
        sql.append(" ORDER BY PRIORITY, HIT_COUNT DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void close(String id) {
        I18nGap g = gapDao.selectById(id);
        if (g == null) {
            throw new ServiceException(404, "缺口不存在");
        }
        g.setStatus(I18nGap.ST_CLOSED);
        g.setClosedAt(LocalDateTime.now());
        g.setUpdateBy("i18n-admin");
        g.setUpdateDate(LocalDateTime.now());
        gapDao.updateById(g);
    }

    @Override
    public Map<String, Object> coverage(String locale) {
        String target = locale == null || locale.isBlank() ? "en-US" : locale;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locale", target);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.PRIORITY AS priority, COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN t.TARGET_TEXT IS NOT NULL AND t.TARGET_TEXT<>'' "
                        + "THEN 1 ELSE 0 END),0) AS translated "
                        + "FROM erp_i18n_key s "
                        + "LEFT JOIN erp_i18n_key t ON t.KEY_NAME=s.KEY_NAME AND t.LOCALE=? AND 1=1 AND t.DEL_FLAG='0' "
                        + "WHERE s.DEL_FLAG='0' AND s.LOCALE='zh-CN' "
                        + "GROUP BY s.PRIORITY ORDER BY s.PRIORITY", target);
        long total = 0, translated = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long t = ((Number) r.get("total")).longValue();
            long tr = ((Number) r.get("translated")).longValue();
            total += t;
            translated += tr;
            Map<String, Object> item = new LinkedHashMap<>(r);
            item.put("coverage", t == 0 ? BigDecimal.valueOf(100)
                    : BigDecimal.valueOf(tr * 100).divide(BigDecimal.valueOf(t), 2, RoundingMode.HALF_UP));
            items.add(item);
        }
        out.put("items", items);
        out.put("totalKeys", total);
        out.put("translated", translated);
        out.put("coverage", total == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(translated * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP));
        // 缺口键清单
        out.put("gaps", jdbc.queryForList(
                "SELECT KEY_NAME AS keyName, MODULE AS module, PRIORITY AS priority "
                        + "FROM erp_i18n_key WHERE DEL_FLAG='0' AND LOCALE='zh-CN' AND KEY_NAME NOT IN "
                        + "(SELECT KEY_NAME FROM erp_i18n_key WHERE DEL_FLAG='0' AND LOCALE=? "
                        + "AND TARGET_TEXT IS NOT NULL AND TARGET_TEXT<>'') ORDER BY PRIORITY, KEY_NAME LIMIT 200",
                target));
        return out;
    }

    private LocalDateTime parseTime(Object v) {
        if (v == null) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(String.valueOf(v).replace(' ', 'T'));
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return 1;
        }
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
