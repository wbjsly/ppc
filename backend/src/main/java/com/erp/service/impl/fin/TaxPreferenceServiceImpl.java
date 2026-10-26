package com.erp.service.impl.fin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.erp.common.ServiceException;
import com.erp.dao.fin.TaxPreferenceDao;
import com.erp.entity.fin.TaxPreference;
import com.erp.service.SysParamService;
import com.erp.service.fin.TaxPreferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
import java.util.UUID;

/**
 * 税收优惠备案管理实现（tax-preference-register）。
 * 过期判定为查询/校验时惰性计算（design D4：双入口，不建 cron）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaxPreferenceServiceImpl implements TaxPreferenceService {

    private final TaxPreferenceDao prefDao;
    private final SysParamService sysParamService;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyMM");

    // ================================================================
    // 2.1 台账登记（S-4.14-11）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaxPreference register(Map<String, Object> payload) {
        String policyName = str(payload.get("policyName"));
        String policyDocNo = str(payload.get("policyDocNo"));
        String prefType = str(payload.get("prefType"));
        String subject = str(payload.get("subject"));
        LocalDate start = date(payload.get("prefStart"));
        LocalDate end = date(payload.get("prefEnd"));
        // 必备字段（政策依据/主体/期间）
        require(policyName, "政策名称");
        require(policyDocNo, "政策依据文号");
        require(prefType, "优惠类型");
        require(subject, "适用主体");
        if (start == null || end == null) {
            throw new ServiceException(422, "优惠期间（起止）必填");
        }
        if (!start.isBefore(end)) {
            throw new ServiceException(422, "优惠期间起日必须早于止日");
        }
        if (!List.of(TaxPreference.TYPE_VAT_REFUND, TaxPreference.TYPE_RND_SUPER,
                TaxPreference.TYPE_OTHER).contains(prefType)) {
            throw new ServiceException(422, "优惠类型非法：" + prefType);
        }
        // 重叠期间防重：同主体同类型 ACTIVE/FROZEN 期间重叠 422
        List<TaxPreference> overlapping = prefDao.selectList(new LambdaQueryWrapper<TaxPreference>()
                .eq(TaxPreference::getDelFlag, "0")
                .eq(TaxPreference::getSubject, subject)
                .eq(TaxPreference::getPrefType, prefType)
                .in(TaxPreference::getStatus, TaxPreference.ST_ACTIVE, TaxPreference.ST_FROZEN)
                .lt(TaxPreference::getPrefStart, end)
                .gt(TaxPreference::getPrefEnd, start));
        if (!overlapping.isEmpty()) {
            TaxPreference exist = overlapping.get(0);
            throw new ServiceException(422, "重复备案：" + exist.getPrefNo()
                    + " 已覆盖同主体同类型期间 " + exist.getPrefStart() + "~" + exist.getPrefEnd());
        }

        TaxPreference p = new TaxPreference();
        p.setId(uuid());
        p.setPrefNo(nextNo(prefDao::selectNosByPrefix));
        p.setPolicyName(policyName);
        p.setPolicyDocNo(policyDocNo);
        p.setPrefType(prefType);
        p.setSubject(subject);
        p.setPrefStart(start);
        p.setPrefEnd(end);
        p.setPrefScopeJson(str(payload.get("prefScopeJson")));
        p.setDocList(str(payload.get("docList")));
        p.setObligation(str(payload.get("obligation")));
        p.setStatus(TaxPreference.ST_ACTIVE);
        p.setCreateBy(currentUser());
        prefDao.insert(p);
        log.info("tax preference registered: {} {} {}", p.getPrefNo(), prefType, subject);
        return p;
    }

    // ================================================================
    // 列表（惰性过期判定 + 临期标注）
    // ================================================================

    @Override
    public List<Map<String, Object>> list(String status, String prefType) {
        LambdaQueryWrapper<TaxPreference> qw = new LambdaQueryWrapper<TaxPreference>()
                .eq(TaxPreference::getDelFlag, "0")
                .orderByDesc(TaxPreference::getCreateDate);
        if (status != null && !status.isBlank()) {
            qw.eq(TaxPreference::getStatus, status);
        }
        if (prefType != null && !prefType.isBlank()) {
            qw.eq(TaxPreference::getPrefType, prefType);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (TaxPreference p : prefDao.selectList(qw)) {
            boolean expired = lazyExpireIfDue(p);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("preference", p);
            row.put("expired", expired);
            row.put("daysToEnd", p.getPrefEnd() == null ? null
                    : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), p.getPrefEnd()));
            out.add(row);
        }
        return out;
    }

    // ================================================================
    // 2.2 续期（BR-4.14-06）
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaxPreference renew(String prefId, Map<String, Object> payload) {
        TaxPreference p = require(prefId);
        if (TaxPreference.ST_TERMINATED.equals(p.getStatus())) {
            throw new ServiceException(422, "已终止备案不可续期（政策已废止）");
        }
        LocalDate newEnd = date(payload.get("newEnd"));
        String basis = str(payload.get("basis"));
        if (newEnd == null) {
            throw new ServiceException(422, "续期后止日必填");
        }
        require(basis, "续期依据");
        LocalDate oldStart = p.getPrefStart();
        LocalDate oldEnd = p.getPrefEnd();
        if (!newEnd.isAfter(oldEnd)) {
            throw new ServiceException(422, "续期止日必须晚于原止日 " + oldEnd);
        }
        // 全程留痕：原期间/新期间/续期依据/操作人/时间
        String history = appendRenewHistory(p.getRenewHistoryJson(),
                Map.of("oldStart", String.valueOf(oldStart), "oldEnd", String.valueOf(oldEnd),
                        "newEnd", String.valueOf(newEnd), "basis", basis,
                        "operator", currentUser(), "operatedAt", String.valueOf(LocalDateTime.now())));
        p.setPrefEnd(newEnd);
        p.setRenewHistoryJson(history);
        if (TaxPreference.ST_FROZEN.equals(p.getStatus()) && p.getFrozenReason() != null
                && p.getFrozenReason().contains("系统过期")) {
            // 过期冻结的备案续期后回 ACTIVE（手工冻结不自动解冻）
            p.setStatus(TaxPreference.ST_ACTIVE);
            p.setFrozenReason(null);
        }
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        prefDao.updateById(p);
        log.info("tax preference renewed: {} end {} -> {}", p.getPrefNo(), oldEnd, newEnd);
        return p;
    }

    // ================================================================
    // 2.3 冻结 / 终止
    // ================================================================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaxPreference freeze(String prefId, String reason) {
        TaxPreference p = require(prefId);
        if (TaxPreference.ST_TERMINATED.equals(p.getStatus())) {
            throw new ServiceException(422, "已终止备案不可冻结");
        }
        require(reason, "冻结原因");
        p.setStatus(TaxPreference.ST_FROZEN);
        p.setFrozenReason("手工冻结：" + reason.trim());
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        prefDao.updateById(p);
        log.info("tax preference frozen: {} reason {}", p.getPrefNo(), reason);
        return p;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaxPreference revoke(String prefId, String revokeDate, String riskAssessNote) {
        TaxPreference p = require(prefId);
        require(riskAssessNote, "补税风险评估说明");
        LocalDate rd = revokeDate == null || revokeDate.isBlank()
                ? LocalDate.now() : LocalDate.parse(revokeDate);
        p.setStatus(TaxPreference.ST_TERMINATED);
        p.setRevokeDate(rd);
        p.setTerminateReason("政策依据废止");
        p.setRiskAssessNote(riskAssessNote.trim());
        p.setUpdateBy(currentUser());
        p.setUpdateDate(LocalDateTime.now());
        prefDao.updateById(p);
        log.info("tax preference terminated: {} revokeDate {} — 补税风险评估留痕", p.getPrefNo(), rd);
        return p;
    }

    // ================================================================
    // 到期预警（BR-4.14-06：90 天惰性）
    // ================================================================

    @Override
    public Map<String, Object> warnings() {
        int warnDays = intParam("PREF_EXPIRE_WARNING_DAYS", 90);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> expiring = new ArrayList<>();
        List<Map<String, Object>> frozenOrTerminated = new ArrayList<>();
        for (TaxPreference p : prefDao.selectList(new LambdaQueryWrapper<TaxPreference>()
                .eq(TaxPreference::getDelFlag, "0")
                .in(TaxPreference::getStatus, TaxPreference.ST_ACTIVE, TaxPreference.ST_FROZEN,
                        TaxPreference.ST_TERMINATED))) {
            boolean expired = lazyExpireIfDue(p);
            long days = p.getPrefEnd() == null ? Long.MAX_VALUE
                    : java.time.temporal.ChronoUnit.DAYS.between(today, p.getPrefEnd());
            if (TaxPreference.ST_ACTIVE.equals(p.getStatus()) && !expired && days <= warnDays) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("preference", p);
                row.put("daysToEnd", days);
                row.put("suggestedAction", "启动续期（BR-4.14-06）");
                expiring.add(row);
            } else if (TaxPreference.ST_FROZEN.equals(p.getStatus())
                    || TaxPreference.ST_TERMINATED.equals(p.getStatus())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("preference", p);
                row.put("reason", TaxPreference.ST_TERMINATED.equals(p.getStatus())
                        ? p.getTerminateReason() : p.getFrozenReason());
                row.put("suggestedAction", TaxPreference.ST_TERMINATED.equals(p.getStatus())
                        ? "评估补税风险（已留痕）" : "补齐条件后解冻或办理续期");
                frozenOrTerminated.add(row);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("expiring", expiring);
        out.put("expiringCount", expiring.size());
        out.put("frozenOrTerminated", frozenOrTerminated);
        out.put("frozenOrTerminatedCount", frozenOrTerminated.size());
        out.put("warnDays", warnDays);
        return out;
    }

    @Override
    public TaxPreference detail(String prefId) {
        TaxPreference p = require(prefId);
        lazyExpireIfDue(p);
        return p;
    }

    // ================================================================
    // 内部
    // ================================================================

    /**
     * 过期惰性判定（design D4）：ACTIVE 且止日已过 → 判定过期并回写状态留痕。
     * 返回 true 表示当前已过期（含本次判定）。
     */
    boolean lazyExpireIfDue(TaxPreference p) {
        boolean due = p.getPrefEnd() != null && p.getPrefEnd().isBefore(LocalDate.now());
        if (due && TaxPreference.ST_ACTIVE.equals(p.getStatus())) {
            p.setStatus(TaxPreference.ST_EXPIRED);
            p.setFrozenReason("系统过期：优惠期间止日 " + p.getPrefEnd() + " 已过（BR-4.14-05 自动冻结）");
            p.setUpdateBy("system");
            p.setUpdateDate(LocalDateTime.now());
            prefDao.updateById(p);
            log.info("tax preference lazily expired: {}", p.getPrefNo());
        }
        return due;
    }

    private String appendRenewHistory(String existing, Map<String, Object> entry) {
        try {
            List<Map<String, Object>> list = existing == null || existing.isBlank()
                    ? new ArrayList<>()
                    : new ArrayList<>(MAPPER.readValue(existing,
                            MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class)));
            list.add(entry);
            return MAPPER.writeValueAsString(list);
        } catch (Exception e) {
            log.warn("renew history serialize failed, overwrite with single entry");
            try {
                return MAPPER.writeValueAsString(List.of(entry));
            } catch (Exception e2) {
                throw new ServiceException(500, "续期留痕序列化失败");
            }
        }
    }

    private TaxPreference require(String prefId) {
        TaxPreference p = prefDao.selectById(prefId);
        if (p == null) {
            throw new ServiceException(404, "优惠备案不存在：" + prefId);
        }
        return p;
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) {
            throw new ServiceException(422, field + "必填");
        }
    }

    private String nextNo(java.util.function.Function<String, List<String>> nosFn) {
        String prefix = "TPF" + LocalDate.now().format(NO_FMT);
        List<String> existing = nosFn.apply(prefix + "%");
        int max = 0;
        for (String no : existing) {
            try {
                max = Math.max(max, Integer.parseInt(no.substring(prefix.length() + 1)));
            } catch (Exception ignored) {
                // 流水后缀非数字则跳过
            }
        }
        return prefix + "-" + String.format("%04d", max + 1);
    }

    private int intParam(String key, int dflt) {
        String v = sysParamService.getValue(key);
        try {
            return v == null || v.isBlank() ? dflt : Integer.parseInt(v.trim());
        } catch (Exception e) {
            return dflt;
        }
    }

    private static LocalDate date(Object o) {
        if (o == null || String.valueOf(o).isBlank()) {
            return null;
        }
        return LocalDate.parse(String.valueOf(o).trim().substring(0, 10));
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static String currentUser() {
        var auth = org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        return auth == null ? "system" : auth.getName();
    }

    private static String uuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
