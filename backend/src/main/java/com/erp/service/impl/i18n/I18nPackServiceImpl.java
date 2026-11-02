package com.erp.service.impl.i18n;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.i18n.I18nPackDao;
import com.erp.entity.i18n.I18nPack;
import com.erp.service.i18n.I18nPackService;
import com.erp.util.SecurityUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
 * 语言包版本发布实现（add-i18n-l10n，SOP-5.7-B 六步）。
 * 预检门禁：P0/P1=100%、总体≥TRANSLATION_COVERAGE_MIN（BR-5.7-01/02、C-5.7-06）；
 * 灰度异常率>0.1% 自动回退（BR-5.7-07）；归档锁定（C-0-05）。
 */
@Slf4j
@Service
public class I18nPackServiceImpl implements I18nPackService {

    private static final String SRC = "zh-CN";
    private static final double CANARY_ERR_RATE = 0.001;

    @Value("${i18n.coverage-min:99}")
    private BigDecimal coverageMin;

    private final I18nPackDao packDao;
    private final JdbcTemplate jdbc;

    public I18nPackServiceImpl(I18nPackDao packDao, JdbcTemplate jdbc) {
        this.packDao = packDao;
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> build(String locale, String packNo, String remark) {
        if (locale == null || locale.isBlank() || SRC.equals(locale)) {
            throw new ServiceException(422, "目标语种必填且不能为源语");
        }
        if (packNo == null || !packNo.matches("\\d+\\.\\d+\\.\\d+")) {
            throw new ServiceException(422, "版本号须为语义化版本 Major.Minor.Patch");
        }
        I18nPack exist = packDao.selectOne(new LambdaQueryWrapper<I18nPack>()
                .eq(I18nPack::getLocale, locale).eq(I18nPack::getPackNo, packNo).last("LIMIT 1"));
        if (exist != null) {
            throw new ServiceException(422, "版本已存在（不可变版本包）");
        }
        I18nPack p = new I18nPack();
        p.setPackNo(packNo);
        p.setLocale(locale);
        p.setStatus(I18nPack.ST_DRAFT);
        p.setSourceVersion("0.0.0");
        p.setCanaryPct(10);
        p.setCanaryWindowH(24);
        p.setRenderErrCnt(0);
        p.setRenderReqCnt(0);
        p.setRemark(remark);
        p.setCreateBy(operator());
        p.setCreateDate(LocalDateTime.now());
        packDao.insert(p);
        // 版本元数据行即快照锚点：键值关联按 KEY_NAME+LOCALE（1=1），不迁移 PACK_NO
        //（迁移会在跨轮"孤儿清理"中误删种子键——ja 208 条曾因此归零）
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", p.getId());
        out.put("packNo", packNo);
        return out;
    }

    @Override
    public Map<String, Object> precheck(String locale) {
        String target = locale == null || locale.isBlank() ? "en-US" : locale;
        // 按优先级统计覆盖率（源语为分母）
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT s.PRIORITY AS priority, COUNT(*) AS total, "
                        + "COALESCE(SUM(CASE WHEN t.TARGET_TEXT IS NOT NULL AND t.TARGET_TEXT<>'' "
                        + "THEN 1 ELSE 0 END),0) AS translated "
                        + "FROM erp_i18n_key s "
                        + "LEFT JOIN erp_i18n_key t ON t.KEY_NAME=s.KEY_NAME AND t.LOCALE=? AND 1=1 AND t.DEL_FLAG='0' "
                        + "WHERE s.DEL_FLAG='0' AND s.LOCALE='zh-CN' GROUP BY s.PRIORITY", target);
        long total = 0, translated = 0, p0Total = 0, p0Tr = 0, p1Total = 0, p1Tr = 0;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String pri = String.valueOf(r.get("priority"));
            long t = ((Number) r.get("total")).longValue();
            long tr = ((Number) r.get("translated")).longValue();
            total += t;
            translated += tr;
            if ("P0".equals(pri)) {
                p0Total += t;
                p0Tr += tr;
            }
            if ("P1".equals(pri)) {
                p1Total += t;
                p1Tr += tr;
            }
            Map<String, Object> item = new LinkedHashMap<>(r);
            item.put("coverage", t == 0 ? BigDecimal.valueOf(100)
                    : BigDecimal.valueOf(tr * 100).divide(BigDecimal.valueOf(t), 2, RoundingMode.HALF_UP));
            items.add(item);
        }
        BigDecimal coverage = total == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(translated * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        BigDecimal p0Cov = p0Total == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(p0Tr * 100).divide(BigDecimal.valueOf(p0Total), 2, RoundingMode.HALF_UP);
        BigDecimal p1Cov = p1Total == 0 ? BigDecimal.valueOf(100)
                : BigDecimal.valueOf(p1Tr * 100).divide(BigDecimal.valueOf(p1Total), 2, RoundingMode.HALF_UP);
        // 门禁（BR-5.7-01/02、C-5.7-06）
        boolean p01Ok = p0Cov.compareTo(BigDecimal.valueOf(100)) >= 0
                && p1Cov.compareTo(BigDecimal.valueOf(100)) >= 0;
        boolean totalOk = coverage.compareTo(coverageMin) >= 0;
        boolean pass = p01Ok && totalOk;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pass", pass);
        out.put("locale", target);
        out.put("totalKeys", total);
        out.put("translated", translated);
        out.put("coverage", coverage);
        out.put("coverageMin", coverageMin);
        out.put("p0Coverage", p0Cov);
        out.put("p1Coverage", p1Cov);
        out.put("items", items);
        // 缺口键清单（逐条列出，BR-5.7-01）
        out.put("gaps", jdbc.queryForList(
                "SELECT KEY_NAME AS keyName, MODULE AS module, PRIORITY AS priority "
                        + "FROM erp_i18n_key WHERE DEL_FLAG='0' AND LOCALE='zh-CN' AND KEY_NAME NOT IN "
                        + "(SELECT KEY_NAME FROM erp_i18n_key WHERE DEL_FLAG='0' AND LOCALE=? "
                        + "AND TARGET_TEXT IS NOT NULL AND TARGET_TEXT<>'') ORDER BY PRIORITY, KEY_NAME LIMIT 500",
                target));
        if (!p01Ok) {
            out.put("blockReason", "P0/P1 覆盖率未达 100%（BR-5.7-01）");
        } else if (!totalOk) {
            out.put("blockReason", "总体覆盖率 " + coverage + "% < 门槛 " + coverageMin + "%（BR-5.7-02）");
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(String id) {
        I18nPack p = mustGet(id);
        if (!I18nPack.ST_DRAFT.equals(p.getStatus()) && !I18nPack.ST_PRECHECK.equals(p.getStatus())) {
            throw new ServiceException(422, "仅草稿/预检态可审批（当前 " + p.getStatus() + "）");
        }
        // 审批前强制预检（BR-5.7-01/02）
        Map<String, Object> pc = precheck(p.getLocale());
        if (!Boolean.TRUE.equals(pc.get("pass"))) {
            throw new ServiceException(422, "预检不通过：" + pc.get("blockReason"));
        }
        p.setStatus(I18nPack.ST_APPROVED);
        p.setUpdateBy(operator());
        p.setUpdateDate(LocalDateTime.now());
        packDao.updateById(p);
        return wrap(p);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> startCanary(String id, Integer pct) {
        I18nPack p = mustGet(id);
        if (!I18nPack.ST_APPROVED.equals(p.getStatus())) {
            throw new ServiceException(422, "仅已审批版本可灰度（当前 " + p.getStatus() + "）");
        }
        // 回退策略为发布前置（SOP-5.7-B 步3）
        if (p.getFallbackPolicyJson() == null || p.getFallbackPolicyJson().isBlank()) {
            throw new ServiceException(422, "回退策略未配置，不可灰度发布");
        }
        p.setStatus(I18nPack.ST_CANARY);
        if (pct != null && pct > 0 && pct <= 100) {
            p.setCanaryPct(pct);
        }
        p.setRenderErrCnt(0);
        p.setRenderReqCnt(0);
        p.setUpdateBy(operator());
        p.setUpdateDate(LocalDateTime.now());
        packDao.updateById(p);
        return wrap(p);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> checkCanary(String id) {
        I18nPack p = mustGet(id);
        if (!I18nPack.ST_CANARY.equals(p.getStatus())) {
            throw new ServiceException(422, "非灰度态不可检查");
        }
        int err = p.getRenderErrCnt() == null ? 0 : p.getRenderErrCnt();
        int req = p.getRenderReqCnt() == null ? 0 : p.getRenderReqCnt();
        Map<String, Object> out = new LinkedHashMap<>(wrap(p));
        if (req > 0 && (double) err / req > CANARY_ERR_RATE) {
            // 自动回退上一稳定版本（BR-5.7-07）
            p.setStatus(I18nPack.ST_DRAFT);
            p.setUpdateBy("canary-engine");
            p.setUpdateDate(LocalDateTime.now());
            packDao.updateById(p);
            out.put("status", I18nPack.ST_DRAFT);
            out.put("rolledBack", true);
            out.put("reason", "渲染异常率 " + (err * 100.0 / req) + "% > 0.1%，自动回退（BR-5.7-07）");
        } else {
            out.put("rolledBack", false);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> archive(String id) {
        I18nPack p = mustGet(id);
        if (I18nPack.ST_ARCHIVED.equals(p.getStatus())) {
            return wrap(p);
        }
        p.setStatus(I18nPack.ST_ARCHIVED);
        p.setUpdateBy(operator());
        p.setUpdateDate(LocalDateTime.now());
        packDao.updateById(p);
        return wrap(p);
    }

    @Override
    public List<Map<String, Object>> list(String locale, String status) {
        StringBuilder sql = new StringBuilder(
                "SELECT ID AS id, PACK_NO AS packNo, LOCALE AS locale, STATUS AS status, "
                        + "CANARY_PCT AS canaryPct, CANARY_WINDOW_H AS canaryWindowH, "
                        + "RENDER_ERR_CNT AS renderErrCnt, RENDER_REQ_CNT AS renderReqCnt, "
                        + "REMARK AS remark, CREATE_DATE AS createDate FROM erp_i18n_pack WHERE DEL_FLAG='0'");
        List<Object> args = new ArrayList<>();
        if (locale != null && !locale.isBlank()) {
            sql.append(" AND LOCALE=?");
            args.add(locale.trim());
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND STATUS=?");
            args.add(status.trim());
        }
        sql.append(" ORDER BY CREATE_DATE DESC LIMIT 200");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    @Override
    public Map<String, Object> currentPack(String locale) {
        String target = locale == null || locale.isBlank() ? "en-US" : locale;
        // 生效版本：已归档 > 已全量（APPROVED 无灰度）> 草稿；无版本则 0.0.0
        Map<String, Object> pack = null;
        try {
            pack = jdbc.queryForMap(
                    "SELECT ID, PACK_NO, STATUS, FALLBACK_POLICY_JSON FROM erp_i18n_pack "
                            + "WHERE DEL_FLAG='0' AND LOCALE=? ORDER BY CASE STATUS "
                            + "WHEN 'ARCHIVED' THEN 0 WHEN 'APPROVED' THEN 1 WHEN 'CANARY' THEN 2 ELSE 3 END, "
                            + "CREATE_DATE DESC LIMIT 1", target);
        } catch (Exception e) {
            pack = null;
        }
        String packNo = pack == null ? "0.0.0" : String.valueOf(pack.get("PACK_NO"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("locale", target);
        out.put("packNo", packNo);
        out.put("status", pack == null ? "NONE" : pack.get("STATUS"));
        out.put("fallbackPolicy", pack == null ? null : pack.get("FALLBACK_POLICY_JSON"));
        // 键值：目标语译文 + 源语兜底（缺失键由前端回退链处理）
        List<Map<String, Object>> keys = jdbc.queryForList(
                "SELECT s.KEY_NAME AS msgKey, "
                        + "COALESCE(NULLIF(t.TARGET_TEXT,''), s.SOURCE_TEXT) AS msgText, "
                        + "CASE WHEN t.TARGET_TEXT IS NULL OR t.TARGET_TEXT='' THEN 1 ELSE 0 END AS missing "
                        + "FROM erp_i18n_key s "
                        + "LEFT JOIN erp_i18n_key t ON t.KEY_NAME=s.KEY_NAME AND t.LOCALE=? AND 1=1 AND t.DEL_FLAG='0' "
                        + "WHERE s.DEL_FLAG='0' AND s.LOCALE='zh-CN' ORDER BY s.KEY_NAME", target);
        Map<String, Object> messages = new LinkedHashMap<>();
        int missing = 0;
        for (Map<String, Object> k : keys) {
            messages.put(String.valueOf(k.get("msgKey")), k.get("msgText"));
            if (((Number) k.get("missing")).intValue() == 1) {
                missing++;
            }
        }
        out.put("messages", messages);
        out.put("missingCount", missing);
        return out;
    }

    private I18nPack mustGet(String id) {
        I18nPack p = packDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "语言包版本不存在");
        }
        return p;
    }

    private Map<String, Object> wrap(I18nPack p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("packNo", p.getPackNo());
        m.put("locale", p.getLocale());
        m.put("status", p.getStatus());
        m.put("canaryPct", p.getCanaryPct());
        m.put("renderErrCnt", p.getRenderErrCnt());
        m.put("renderReqCnt", p.getRenderReqCnt());
        return m;
    }

    private String operator() {
        try {
            String u = SecurityUtils.getCurrentUserId();
            return u == null ? "system" : u;
        } catch (Exception e) {
            return "system";
        }
    }
}
