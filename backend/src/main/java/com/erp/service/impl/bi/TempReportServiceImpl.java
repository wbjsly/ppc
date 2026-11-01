package com.erp.service.impl.bi;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.bi.BiReportTemplateDao;
import com.erp.dao.bi.BiTempReportDao;
import com.erp.entity.bi.BiReportTemplate;
import com.erp.entity.bi.TempReport;
import com.erp.security.IntfGuard;
import com.erp.service.bi.AdhocQueryBuilder;
import com.erp.service.bi.AdhocQueryService;
import com.erp.service.bi.ColumnSemantics;
import com.erp.service.bi.ReportTemplateService;
import com.erp.service.bi.TempReportService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 临时报表实现（add-adhoc-query-console，spec bi-temp-report）：
 * 存查询定义非快照（打开按访问者重跑，权限掩码实时生效）；
 * 30 天到期归档 + 前 3 天提醒；转正式报表走 14.1 create（内含 diffCheck C-4.10-01）。
 */
@Slf4j
@Service
public class TempReportServiceImpl implements TempReportService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int EXPIRE_DAYS = 30;
    private static final int REMIND_DAYS = 3;

    private final BiTempReportDao tempReportDao;
    private final BiReportTemplateDao templateDao;
    /** ObjectProvider 懒取：AdhocQueryService ↔ TempReportService 同族防环 */
    private final ObjectProvider<AdhocQueryService> adhocProvider;
    private final ReportTemplateService templateService;
    private final JdbcTemplate jdbc;

    public TempReportServiceImpl(BiTempReportDao tempReportDao,
                                 BiReportTemplateDao templateDao,
                                 ObjectProvider<AdhocQueryService> adhocProvider,
                                 ReportTemplateService templateService,
                                 JdbcTemplate jdbc) {
        this.tempReportDao = tempReportDao;
        this.templateDao = templateDao;
        this.adhocProvider = adhocProvider;
        this.templateService = templateService;
        this.jdbc = jdbc;
    }

    @Override
    public List<Map<String, Object>> list() {
        String user = IntfGuard.currentUser();
        List<TempReport> mine = tempReportDao.selectList(new LambdaQueryWrapper<TempReport>()
                .eq(TempReport::getCreateBy, user)
                .eq(TempReport::getArchivedFlag, false)
                .orderByDesc(TempReport::getCreateDate));
        List<Map<String, Object>> out = new ArrayList<>();
        for (TempReport r : mine) {
            out.add(toRow(r, "mine"));
        }
        // 共享给我的（SHARE_JSON 扫描，仅未归档）
        List<TempReport> shared = tempReportDao.selectList(new LambdaQueryWrapper<TempReport>()
                .eq(TempReport::getArchivedFlag, false)
                .isNotNull(TempReport::getShareJson));
        for (TempReport r : shared) {
            if (user.equals(r.getCreateBy())) {
                continue;
            }
            if (shareHits(r.getShareJson(), user)) {
                out.add(toRow(r, "shared"));
            }
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveAs(Map<String, Object> payload) {
        String user = IntfGuard.currentUser();
        String name = str(payload.get("name"));
        String templateId = str(payload.get("templateId"));
        String queryJson = str(payload.get("queryJson"));
        if (name == null || name.isBlank()) {
            throw new ServiceException(422, "名称必填");
        }
        BiReportTemplate t = templateDao.selectById(templateId);
        if (t == null || !BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())) {
            throw new ServiceException(422, "仅已发布模板可另存为临时报表");
        }
        TempReport r = new TempReport();
        r.setId(uuid());
        r.setCode("TMP" + uuid().substring(0, 16));
        r.setName(name);
        r.setRemark(str(payload.get("remark")));
        r.setTemplateId(templateId);
        r.setQueryJson(queryJson);
        r.setExpireAt(LocalDateTime.now().plusDays(EXPIRE_DAYS));
        r.setExpireNotified(false);
        r.setArchivedFlag(false);
        r.setCreateBy(user);
        tempReportDao.insert(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("code", r.getCode());
        out.put("expireAt", r.getExpireAt());
        return out;
    }

    @Override
    public Map<String, Object> open(String id, int page, int size) {
        TempReport r = require(id);
        String user = IntfGuard.currentUser();
        boolean creator = user.equals(r.getCreateBy());
        if (!creator && !shareHits(r.getShareJson(), user) && !IntfGuard.isAdmin()) {
            throw new ServiceException(404, "报表不存在"); // 不暴露存在性
        }
        if (Boolean.TRUE.equals(r.getArchivedFlag())) {
            throw new ServiceException(410, "临时报表已到期归档");
        }
        // 按访问者身份重跑（行级注入+掩码实时生效，spec bi-temp-report）
        Map<String, Object> data = adhocProvider.getObject()
                .rerunFor(r.getTemplateId(), r.getQueryJson(), user, page, size);
        data.put("tempReportId", r.getId());
        data.put("name", r.getName());
        data.put("expireAt", r.getExpireAt());
        data.put("promotedTo", r.getPromotedTo());
        return data;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> share(String id, List<Map<String, Object>> shareList) {
        TempReport r = require(id);
        if (!IntfGuard.currentUser().equals(r.getCreateBy())) {
            throw new ServiceException(403, "仅创建人可设置共享");
        }
        if (shareList == null) {
            r.setShareJson(null);
        } else {
            try {
                r.setShareJson(JSON.writeValueAsString(shareList));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new ServiceException(422, "共享清单序列化失败");
            }
        }
        r.setUpdateBy(IntfGuard.currentUser());
        tempReportDao.updateById(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.getId());
        out.put("shareJson", r.getShareJson());
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> promote(String id) {
        TempReport r = require(id);
        if (!IntfGuard.currentUser().equals(r.getCreateBy())) {
            throw new ServiceException(403, "仅创建人可转正");
        }
        if (r.getPromotedTo() != null && !r.getPromotedTo().isBlank()) {
            throw new ServiceException(422, "已转正，不可重复转正");
        }
        BiReportTemplate src = requirePublished(r.getTemplateId());
        List<ColumnSemantics.Col> cols = ColumnSemantics.parse(src.getColumnsJson());
        // 生成转正模板草稿：SQL = 查询定义的治理生成 SQL；列 = 查询维度/指标反推；
        // 指标 = 数据集 METRICS_JSON 子集（create 内走 diffCheck C-4.10-01，
        // 口径不一致 422 带差异清单——spec 转正校验语义）
        AdhocQueryBuilder.Built built = AdhocQueryBuilder.build(
                src.getSqlText(), cols, r.getQueryJson(), Integer.MAX_VALUE);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", "TMP2RPT" + uuid().substring(0, 16));
        payload.put("name", "【转正】" + r.getName());
        payload.put("category", src.getCategory());
        payload.put("sqlText", built.sql);
        payload.put("columnsJson", reverseColumns(r.getQueryJson(), cols));
        payload.put("metricsJson", src.getMetricsJson());
        payload.put("remark", "由临时报表 " + r.getCode() + " 转正（BR-4.10-34）");
        // create 内置 diffCheck：不通过抛 422（口径差异）——不落模板不回填
        Map<String, Object> tpl = templateService.create(payload);
        String newId = str(tpl.get("id"));
        r.setPromotedTo(newId);
        r.setPromoteAt(LocalDateTime.now());
        r.setUpdateBy(IntfGuard.currentUser());
        tempReportDao.updateById(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tempReportId", r.getId());
        out.put("templateId", newId);
        out.put("templateStatus", tpl.get("status"));
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sweepLifecycle() {
        LocalDateTime now = LocalDateTime.now();
        // 到期归档
        int archived = jdbc.update(
                "UPDATE erp_bi_temp_report SET ARCHIVED_FLAG='1', UPDATE_DATE=? "
                        + "WHERE ARCHIVED_FLAG='0' AND DEL_FLAG='0' AND EXPIRE_AT <= ?",
                now, now);
        // 前 3 天提醒（去重：EXPIRE_NOTIFIED='0'）
        List<Map<String, Object>> due = jdbc.queryForList(
                "SELECT ID, NAME, CREATE_BY, EXPIRE_AT FROM erp_bi_temp_report "
                        + "WHERE ARCHIVED_FLAG='0' AND DEL_FLAG='0' AND EXPIRE_NOTIFIED='0' "
                        + "AND EXPIRE_AT <= ?", now.plusDays(REMIND_DAYS));
        int reminded = 0;
        for (Map<String, Object> d : due) {
            jdbc.update("INSERT INTO erp_sys_notice (ID, TARGET_USER, TITLE, CONTENT, "
                            + "BIZ_TYPE, BIZ_ID, READ_FLAG, CREATE_BY, CREATE_DATE, DEL_FLAG, VER_NO) "
                            + "VALUES (?,?,?,?,? ,?,?,?,NOW(),'0',0)",
                    uuid(), str(d.get("CREATE_BY")),
                    "临时报表到期提醒", "临时报表「" + d.get("NAME") + "」将于 "
                            + d.get("EXPIRE_AT") + " 到期归档", "TempReportExpire",
                    str(d.get("ID")), "0", "system");
            jdbc.update("UPDATE erp_bi_temp_report SET EXPIRE_NOTIFIED='1' WHERE ID=?",
                    str(d.get("ID")));
            reminded++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("archived", archived);
        out.put("reminded", reminded);
        return out;
    }

    // ---------- 内部 ----------

    private Map<String, Object> toRow(TempReport r, String relation) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("remark", r.getRemark());
        m.put("templateId", r.getTemplateId());
        m.put("relation", relation);
        m.put("shareCount", shareCount(r.getShareJson()));
        m.put("expireAt", r.getExpireAt());
        long days = java.time.Duration.between(LocalDateTime.now(), r.getExpireAt()).toDays();
        m.put("daysLeft", Math.max(0, days));
        m.put("promotedTo", r.getPromotedTo());
        m.put("createBy", r.getCreateBy());
        m.put("createDate", r.getCreateDate());
        return m;
    }

    /** SHARE_JSON 命中判定：type=user 且 code=用户；type=role 且 code∈当前用户角色 */
    private boolean shareHits(String shareJson, String user) {
        if (shareJson == null || shareJson.isBlank()) {
            return false;
        }
        try {
            List<Map<String, Object>> list = JSON.readValue(shareJson,
                    new TypeReference<List<Map<String, Object>>>() { });
            for (Map<String, Object> s : list) {
                String type = str(s.get("type"));
                String code = str(s.get("code"));
                if (code == null) {
                    continue;
                }
                if ("user".equalsIgnoreCase(type) && code.equals(user)) {
                    return true;
                }
                if ("role".equalsIgnoreCase(type)) {
                    Integer n = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM erp_admin_user u "
                                    + "JOIN erp_admin_user_role ur ON ur.USER_ID=u.ID "
                                    + "JOIN erp_admin_role r ON r.ID=ur.ROLE_ID "
                                    + "WHERE u.USERNAME=? AND r.ROLE_CODE=? AND r.DEL_FLAG='0'",
                            Integer.class, user, code);
                    if (n != null && n > 0) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("SHARE_JSON 解析失败: {}", e.getMessage());
        }
        return false;
    }

    private int shareCount(String shareJson) {
        if (shareJson == null || shareJson.isBlank()) {
            return 0;
        }
        try {
            return JSON.readValue(shareJson, new TypeReference<List<Object>>() { }).size();
        } catch (Exception e) {
            return 0;
        }
    }

    /** 查询定义反推列定义（转正模板 COLUMNS_JSON：维度 DIMENSION / 指标 MEASURE） */
    private String reverseColumns(String queryJson, List<ColumnSemantics.Col> srcCols) {
        try {
            Map<String, Object> q = JSON.readValue(queryJson,
                    new TypeReference<Map<String, Object>>() { });
            List<Map<String, Object>> out = new ArrayList<>();
            Object dims = q.get("dimensions");
            if (dims instanceof List<?> dl) {
                for (Object d : dl) {
                    ColumnSemantics.Col c = ColumnSemantics.field(srcCols, String.valueOf(d));
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("field", String.valueOf(d));
                    col.put("label", c != null ? c.label : String.valueOf(d));
                    col.put("role", "DIMENSION");
                    if (c != null) {
                        col.put("timeFlag", c.timeFlag);
                        col.put("roleFlag", c.roleFlag);
                        col.put("entityCol", c.entityCol);
                    }
                    out.add(col);
                }
            }
            Object ms = q.get("measures");
            if (ms instanceof List<?> ml) {
                for (Object mo : ml) {
                    if (mo instanceof Map<?, ?> mm) {
                        String f = str(mm.get("field"));
                        ColumnSemantics.Col c = ColumnSemantics.field(srcCols, f);
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("field", f);
                        col.put("label", c != null ? c.label : f);
                        col.put("role", "MEASURE");
                        col.put("agg", List.of(str(mm.get("agg")) == null ? "SUM"
                                : String.valueOf(mm.get("agg"))));
                        out.add(col);
                    }
                }
            }
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            return "[]";
        }
    }

    private TempReport require(String id) {
        TempReport r = tempReportDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "报表不存在");
        }
        return r;
    }

    private BiReportTemplate requirePublished(String templateId) {
        BiReportTemplate t = templateDao.selectById(templateId);
        if (t == null || !BiReportTemplate.ST_PUBLISHED.equals(t.getStatus())) {
            throw new ServiceException(422, "数据集模板不存在或未发布");
        }
        return t;
    }

    private String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
