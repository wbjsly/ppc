package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ExchangeRateRules;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmExchangeRateDao;
import com.erp.dao.mdm.MdmExchangeRateVersionDao;
import com.erp.dao.ops.MdmOutboxDao;
import com.erp.entity.mdm.MdmExchangeRate;
import com.erp.entity.mdm.MdmExchangeRateVersion;
import com.erp.entity.ops.MdmOutboxEvent;
import com.erp.ops.OutboxPublisher;
import com.erp.service.mdm.MdmExchangeRateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MdmExchangeRateServiceImpl implements MdmExchangeRateService {

    private static final String SNAPSHOT_FIELDS =
            "baseCcy,quoteCcy,rateType,effectiveDate,expireDate,rate,sourceFileNo";
    private static final int RATE_SCALE = 6; // FR-4.6-4-3

    /** 试算缺省类型回退链（D4） */
    private static final String[] FALLBACK = { "MIDDLE", "BUY", "SELL" };

    private final MdmExchangeRateDao rateDao;
    private final MdmExchangeRateVersionDao versionDao;
    private final MdmOutboxDao outboxDao;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;

    public MdmExchangeRateServiceImpl(MdmExchangeRateDao rateDao,
                                      MdmExchangeRateVersionDao versionDao,
                                      MdmOutboxDao outboxDao,
                                      OutboxPublisher outbox,
                                      ObjectMapper objectMapper) {
        this.rateDao = rateDao;
        this.versionDao = versionDao;
        this.outboxDao = outboxDao;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    // ---------- 分页（计算态按日期推导，不落状态列） ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String baseCcy, String quoteCcy,
                                          String rateType, String lifecycle) {
        // MP eq 的值参数即使 condition=false 也会被求值（Java 传参先于调用）→ null-safe 归一化
        String base = isNotBlank(baseCcy) ? baseCcy.toUpperCase() : null;
        String quote = isNotBlank(quoteCcy) ? quoteCcy.toUpperCase() : null;
        LambdaQueryWrapper<MdmExchangeRate> qw = new LambdaQueryWrapper<MdmExchangeRate>()
                .eq(isNotBlank(baseCcy), MdmExchangeRate::getBaseCcy, base)
                .eq(isNotBlank(quoteCcy), MdmExchangeRate::getQuoteCcy, quote)
                .eq(isNotBlank(rateType), MdmExchangeRate::getRateType, rateType)
                .and(isNotBlank(lifecycle), w -> {
                    LocalDate today = LocalDate.now();
                    if ("NOT_EFFECTIVE".equals(lifecycle)) {
                        w.gt(MdmExchangeRate::getEffectiveDate, today);
                    } else if ("EFFECTIVE".equals(lifecycle)) {
                        w.le(MdmExchangeRate::getEffectiveDate, today)
                                .ge(MdmExchangeRate::getExpireDate, today);
                    } else if ("EXPIRED".equals(lifecycle)) {
                        w.lt(MdmExchangeRate::getExpireDate, today);
                    }
                })
                .orderByDesc(MdmExchangeRate::getBaseCcy)
                .orderByDesc(MdmExchangeRate::getEffectiveDate);
        Page<MdmExchangeRate> raw = rateDao.selectPage(new Page<>(current, size), qw);
        // 组装计算态行
        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (MdmExchangeRate r : raw.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("baseCcy", r.getBaseCcy());
            row.put("quoteCcy", r.getQuoteCcy());
            row.put("rateType", r.getRateType());
            row.put("effectiveDate", r.getEffectiveDate());
            row.put("expireDate", r.getExpireDate());
            row.put("rate", r.getRate());
            row.put("sourceFileNo", r.getSourceFileNo());
            row.put("verNo", r.getVerNo());
            row.put("createBy", r.getCreateBy());
            row.put("createDate", r.getCreateDate());
            row.put("lifecycle", lifecycleOf(r, today));
            records.add(row);
        }
        result.setRecords(records);
        return result;
    }

    private String lifecycleOf(MdmExchangeRate r, LocalDate today) {
        if (r.getEffectiveDate().isAfter(today)) return "NOT_EFFECTIVE";
        if (r.getExpireDate().isBefore(today)) return "EXPIRED";
        return "EFFECTIVE";
    }

    @Override
    public MdmExchangeRate getById(String id) {
        MdmExchangeRate r = rateDao.selectById(id);
        if (r == null) {
            throw new ServiceException(404, "汇率记录不存在");
        }
        return r;
    }

    // ---------- 创建 ----------

    @Override
    @Transactional
    public MdmExchangeRate create(MdmExchangeRate rate) {
        requireFields(rate);
        rate.setId(null);
        rate.setRate(rate.getRate().setScale(RATE_SCALE, RoundingMode.HALF_UP));
        rate.setVerNo(0);
        // 区间冲突与断档（BR-4.1-17，序列无自身）
        requireNoGapOrOverlap(rate.getBaseCcy(), rate.getQuoteCcy(), rate.getRateType(),
                rate.getEffectiveDate(), rate.getExpireDate(), null);
        rateDao.insert(rate);
        saveSnapshot(rate, "CREATE", "新建汇率 " + pairOf(rate), null);
        outbox.publish("MDM.RATE.CREATED", bizKey(rate), rate.getVerNo() + 1, null,
                pairOf(rate) + " " + rate.getRate() + " @" + rate.getEffectiveDate());
        return rate;
    }

    // ---------- 变更 ----------

    @Override
    @Transactional
    public MdmExchangeRate update(MdmExchangeRate rate) {
        MdmExchangeRate stored = getById(rate.getId());
        // 序列键创建后锁定（D3：改键脱离区间链）
        if (isNotBlank(rate.getBaseCcy()) && !rate.getBaseCcy().equals(stored.getBaseCcy())
                || isNotBlank(rate.getQuoteCcy()) && !rate.getQuoteCcy().equals(stored.getQuoteCcy())
                || isNotBlank(rate.getRateType()) && !rate.getRateType().equals(stored.getRateType())) {
            throw new ServiceException(422, "币种对与汇率类型创建后不可修改（区间序列键），请新建记录");
        }
        // 历史 append-only（BR-4.1-18）
        if (stored.getExpireDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "已失效历史区间不可篡改（BR-4.1-18），仅可查看");
        }
        String reason = isNotBlank(rate.getChangeReason()) ? rate.getChangeReason().trim() : "";
        if (reason.length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 字）");
        }
        requireFields(rate);
        rate.setRate(rate.getRate().setScale(RATE_SCALE, RoundingMode.HALF_UP));
        // 重跑区间校验（排除自身旧区间）
        requireNoGapOrOverlap(stored.getBaseCcy(), stored.getQuoteCcy(), stored.getRateType(),
                rate.getEffectiveDate(), rate.getExpireDate(), stored.getId());
        String diff = buildDiff(stored, rate);
        if (diff.isEmpty()) {
            throw new ServiceException(422, "无变更内容");
        }
        LambdaUpdateWrapper<MdmExchangeRate> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmExchangeRate::getId, stored.getId())
                .eq(MdmExchangeRate::getVerNo, stored.getVerNo())
                .set(MdmExchangeRate::getEffectiveDate, rate.getEffectiveDate())
                .set(MdmExchangeRate::getExpireDate, rate.getExpireDate())
                .set(MdmExchangeRate::getRate, rate.getRate())
                .set(MdmExchangeRate::getSourceFileNo, rate.getSourceFileNo())
                // changeReason 为 @TableField(exist=false)，不可进 update wrapper（MP lambda 缓存异常，024 同款教训）
                .set(MdmExchangeRate::getVerNo, stored.getVerNo() + 1);
        if (rateDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmExchangeRate after = getById(stored.getId());
        saveSnapshot(after, "UPDATE", diff + "；原因：" + reason, reason);
        outbox.publish("MDM.RATE.UPDATED", bizKey(after), after.getVerNo() + 1, null, diff);
        return after;
    }

    @Override
    @Transactional
    public void delete(String id) {
        MdmExchangeRate stored = getById(id);
        if (stored.getExpireDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "已失效历史区间不可删除（BR-4.1-18）");
        }
        rateDao.deleteById(id); // @TableLogic 软删
        saveSnapshot(stored, "UPDATE", "删除汇率记录 " + pairOf(stored), null);
    }

    // ---------- 区间冲突与断档（design D2 核心） ----------

    /**
     * 判定顺序：① 相交（闭区间共享端点不算相交）→ ② 序列空放行 →
     * ③ 严格贴合链首/链尾放行 → ④ 右断档 / 左缺口 422 带可操作口径。
     */
    private void requireNoGapOrOverlap(String base, String quote, String type,
                                       LocalDate from, LocalDate to, String excludeId) {
        List<MdmExchangeRate> seq = rateDao.selectSequence(base, quote, type, excludeId);
        String reason = ExchangeRateRules.checkInterval(seq, from, to);
        if (reason != null) {
            throw new ServiceException(422, reason);
        }
    }

    // ---------- 试算（D4） ----------

    @Override
    public Map<String, Object> trial(String baseCcy, String quoteCcy, LocalDate date, String rateType) {
        if (!isNotBlank(baseCcy) || !isNotBlank(quoteCcy)) {
            throw new ServiceException(422, "币种对必填");
        }
        String base = baseCcy.toUpperCase();
        String quote = quoteCcy.toUpperCase();
        LocalDate d = date == null ? LocalDate.now() : date;
        List<String> types = isNotBlank(rateType)
                ? List.of(rateType.toUpperCase()) : List.of(FALLBACK);
        List<String> reasons = new ArrayList<>();
        for (String t : types) {
            MdmExchangeRate hit = rateDao.selectHit(base, quote, t, d);
            if (hit != null) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("applicable", true);
                result.put("baseCcy", hit.getBaseCcy());
                result.put("quoteCcy", hit.getQuoteCcy());
                result.put("rateType", hit.getRateType());
                result.put("rate", hit.getRate());
                result.put("effectiveDate", hit.getEffectiveDate());
                result.put("expireDate", hit.getExpireDate());
                result.put("sourceFileNo", hit.getSourceFileNo());
                if (!isNotBlank(rateType)) {
                    result.put("fallbackFrom", "缺省类型回退命中：" + t);
                }
                return result;
            }
            reasons.add(t + " 类型在 " + d + " 无覆盖区间");
        }
        if (rateDao.countPair(base, quote) == 0) {
            reasons.add(0, "该币对（" + base + "/" + quote + "）无任何汇率记录");
        } else {
            reasons.add(0, "该币对存在记录但日期 " + d + " 不在任何生效区间内");
        }
        // 缺失明示（非空结构，spec 要求）
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("applicable", false);
        miss.put("message", "该日期无有效汇率");
        miss.put("reasons", reasons);
        miss.put("hint", "请补录覆盖该日期的汇率记录（附来源文件编号）后重试");
        return miss;
    }

    // ---------- 版本 ----------

    @Override
    public List<MdmExchangeRateVersion> versions(String entityId) {
        return versionDao.selectList(new LambdaQueryWrapper<MdmExchangeRateVersion>()
                .eq(MdmExchangeRateVersion::getEntityId, entityId)
                .orderByAsc(MdmExchangeRateVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmExchangeRateVersion vFrom = findVersion(entityId, from);
        MdmExchangeRateVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", Map.of("versionNo", vFrom.getVersionNo(), "opType", vFrom.getOpType()));
        result.put("to", Map.of("versionNo", vTo.getVersionNo(), "opType", vTo.getOpType()));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- 历史（1.5.3） ----------

    @Override
    public List<Map<String, Object>> sequence(String baseCcy, String quoteCcy, String rateType) {
        if (!isNotBlank(baseCcy) || !isNotBlank(quoteCcy) || !isNotBlank(rateType)) {
            throw new ServiceException(422, "币对与汇率类型必填");
        }
        String base = baseCcy.trim().toUpperCase();
        String quote = quoteCcy.trim().toUpperCase();
        String type = rateType.trim().toUpperCase();
        List<MdmExchangeRate> seq = rateDao.selectSequence(base, quote, type, null);
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (MdmExchangeRate r : seq) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", r.getId());
            row.put("baseCcy", r.getBaseCcy());
            row.put("quoteCcy", r.getQuoteCcy());
            row.put("rateType", r.getRateType());
            row.put("effectiveDate", r.getEffectiveDate());
            row.put("expireDate", r.getExpireDate());
            row.put("rate", r.getRate());
            row.put("sourceFileNo", r.getSourceFileNo());
            row.put("verNo", r.getVerNo());
            row.put("createBy", r.getCreateBy());
            row.put("createDate", r.getCreateDate());
            row.put("lifecycle", lifecycleOf(r, today));
            rows.add(row);
        }
        return rows;
    }

    @Override
    public Map<String, Object> history(long current, long size, String rateType,
                                       String opType, String keyword) {
        // 快照无 RATE_TYPE 冗余列 → 筛选走 SNAPSHOT_JSON LIKE（design D2，量级小可接受）
        String typeJson = isNotBlank(rateType)
                ? "\"rateType\":\"" + rateType.trim().toUpperCase() + "\"" : null;
        String kw = isNotBlank(keyword) ? keyword.trim() : null;
        LambdaQueryWrapper<MdmExchangeRateVersion> qw = new LambdaQueryWrapper<MdmExchangeRateVersion>()
                .like(typeJson != null, MdmExchangeRateVersion::getSnapshotJson, typeJson)
                .eq(isNotBlank(opType), MdmExchangeRateVersion::getOpType,
                        isNotBlank(opType) ? opType.trim().toUpperCase() : null)
                .and(kw != null, w -> {
                    w.like(MdmExchangeRateVersion::getDiffSummary, kw)
                            .or().like(MdmExchangeRateVersion::getChangeReason, kw)
                            .or().like(MdmExchangeRateVersion::getSnapshotJson, kw);
                    // 币对关键字（USD/CNY）：快照 JSON 中币对为分字段存储，须按两字段同时命中
                    String[] pair = kw.split("/");
                    if (pair.length == 2 && isNotBlank(pair[0]) && isNotBlank(pair[1])) {
                        String b = pair[0].trim().toUpperCase();
                        String q = pair[1].trim().toUpperCase();
                        w.or(sub -> sub
                                .like(MdmExchangeRateVersion::getSnapshotJson, "\"baseCcy\":\"" + b + "\"")
                                .like(MdmExchangeRateVersion::getSnapshotJson, "\"quoteCcy\":\"" + q + "\""));
                    }
                })
                .orderByDesc(MdmExchangeRateVersion::getCreateDate)
                .orderByDesc(MdmExchangeRateVersion::getVersionNo);
        Page<MdmExchangeRateVersion> raw = versionDao.selectPage(new Page<>(current, size), qw);

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> records = new ArrayList<>();
        for (MdmExchangeRateVersion v : raw.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            Map<String, String> snap = parse(v.getSnapshotJson());
            row.put("entityId", v.getEntityId());
            row.put("versionNo", v.getVersionNo());
            row.put("opType", v.getOpType());
            row.put("diffSummary", v.getDiffSummary());
            row.put("changeReason", v.getChangeReason());
            row.put("createBy", v.getCreateBy());
            row.put("createDate", v.getCreateDate());
            row.put("baseCcy", snap.getOrDefault("baseCcy", ""));
            row.put("quoteCcy", snap.getOrDefault("quoteCcy", ""));
            row.put("rateType", snap.getOrDefault("rateType", ""));
            row.put("effectiveDate", snap.getOrDefault("effectiveDate", ""));
            row.put("expireDate", snap.getOrDefault("expireDate", ""));
            row.put("rate", snap.getOrDefault("rate", ""));
            row.put("sourceFileNo", snap.getOrDefault("sourceFileNo", ""));
            row.put("idempotencyKey", null);
            records.add(row);
        }
        attachEvents(records);
        result.put("records", records);
        result.put("total", raw.getTotal());
        return result;
    }

    /**
     * 幂等键 = BASE/QUOTE:type:生效日期:vN（与 OutboxPublisher 发布口径一致：
     * recordVersion 恒等于快照 VERSION_NO——CREATE v1、UPDATE v(verNo+1)）。
     * 批查降级：异常仅 warn，事件列置 null 不影响时间线主体。
     */
    private void attachEvents(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        try {
            List<String> keys = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String key = row.get("baseCcy") + "/" + row.get("quoteCcy") + ":"
                        + row.get("rateType") + ":" + row.get("effectiveDate")
                        + ":v" + row.get("versionNo");
                row.put("idempotencyKey", key);
                keys.add(key);
            }
            List<MdmOutboxEvent> events = outboxDao.selectList(new LambdaQueryWrapper<MdmOutboxEvent>()
                    .in(MdmOutboxEvent::getIdempotencyKey, keys));
            Map<String, MdmOutboxEvent> byKey = new LinkedHashMap<>();
            for (MdmOutboxEvent e : events) {
                byKey.put(e.getIdempotencyKey(), e);
            }
            for (Map<String, Object> row : rows) {
                MdmOutboxEvent hit = byKey.get(row.get("idempotencyKey"));
                if (hit == null) {
                    row.put("event", null);
                    continue;
                }
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("eventType", hit.getEventType());
                ev.put("idempotencyKey", hit.getIdempotencyKey());
                ev.put("status", hit.getStatus());
                row.put("event", ev);
            }
        } catch (Exception ex) {
            log.warn("history event attach failed: {}", ex.getMessage());
            rows.forEach(r -> r.put("event", null));
        }
    }

    // ---------- 私有工具 ----------

    private void requireFields(MdmExchangeRate r) {
        String reason = ExchangeRateRules.validateFields(r);
        if (reason != null) {
            throw new ServiceException(422, reason);
        }
    }

    private boolean isIsoCcy(String ccy) {
        return ccy != null && ccy.matches("[A-Z]{3}");
    }

    private String bizKey(MdmExchangeRate r) {
        // 幂等键前缀含生效日：同序列多段记录各自 verNo=1，仅币对:type 会撞键（实施揭示）
        return r.getBaseCcy() + "/" + r.getQuoteCcy() + ":" + r.getRateType()
                + ":" + r.getEffectiveDate();
    }

    private String pairOf(MdmExchangeRate r) {
        return r.getBaseCcy() + "/" + r.getQuoteCcy() + " " + r.getRateType();
    }

    private void saveSnapshot(MdmExchangeRate source, String opType, String diff, String reason) {
        int next = versionDao.selectCount(new LambdaQueryWrapper<MdmExchangeRateVersion>()
                .eq(MdmExchangeRateVersion::getEntityId, source.getId())).intValue() + 1;
        MdmExchangeRateVersion v = new MdmExchangeRateVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diff);
        v.setOpType(opType);
        v.setChangeReason(reason);
        versionDao.insert(v);
    }

    private MdmExchangeRateVersion findVersion(String entityId, int versionNo) {
        MdmExchangeRateVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmExchangeRateVersion>()
                        .eq(MdmExchangeRateVersion::getEntityId, entityId)
                        .eq(MdmExchangeRateVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    private String buildDiff(MdmExchangeRate a, MdmExchangeRate b) {
        StringBuilder sb = new StringBuilder();
        append(sb, "effectiveDate", a.getEffectiveDate(), b.getEffectiveDate());
        append(sb, "expireDate", a.getExpireDate(), b.getExpireDate());
        append(sb, "rate", a.getRate(), b.getRate());
        append(sb, "sourceFileNo", a.getSourceFileNo(), b.getSourceFileNo());
        return sb.toString();
    }

    private void append(StringBuilder sb, String field, Object x, Object y) {
        String a = x == null ? "" : String.valueOf(x);
        String b = y == null ? "" : String.valueOf(y);
        if (Objects.equals(a, b)) {
            return;
        }
        if (sb.length() > 0) sb.append("；");
        sb.append(field).append(": ").append(a.isEmpty() ? "（空）" : a)
                .append(" → ").append(b.isEmpty() ? "（空）" : b);
    }

    private List<Map<String, String>> compareSnapshots(String fromJson, String toJson) {
        Map<String, String> from = parse(fromJson);
        Map<String, String> to = parse(toJson);
        List<String> keys = new ArrayList<>(from.keySet());
        to.keySet().forEach(k -> {
            if (!keys.contains(k)) keys.add(k);
        });
        List<Map<String, String>> fields = new ArrayList<>();
        for (String k : keys) {
            String x = from.getOrDefault(k, "");
            String y = to.getOrDefault(k, "");
            if (!Objects.equals(x, y)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("field", k);
                row.put("from", x);
                row.put("to", y);
                fields.add(row);
            }
        }
        return fields;
    }

    private Map<String, String> parse(String json) {
        Map<String, String> data = new LinkedHashMap<>();
        try {
            objectMapper.readTree(json == null || json.isEmpty() ? "{}" : json)
                    .fields().forEachRemaining(en ->
                            data.put(en.getKey(), en.getValue().isNull() ? "" : en.getValue().asText()));
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "快照解析失败");
        }
        return data;
    }

    private String toJson(MdmExchangeRate entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String f : SNAPSHOT_FIELDS.split(",")) {
            try {
                var getter = entity.getClass().getMethod("get" + Character.toUpperCase(f.charAt(0)) + f.substring(1));
                Object val = getter.invoke(entity);
                map.put(f, val == null ? "" : String.valueOf(val));
            } catch (ReflectiveOperationException ignore) {
                map.put(f, "");
            }
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new ServiceException(500, "快照序列化失败");
        }
    }

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
