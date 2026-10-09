package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.IntervalRules;
import com.erp.common.ServiceException;
import com.erp.common.TaxCodeRules;
import com.erp.dao.mdm.MdmTaxCodeDao;
import com.erp.dao.mdm.MdmTaxCodeVersionDao;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.entity.mdm.MdmTaxCodeVersion;
import com.erp.ops.OutboxPublisher;
import com.erp.service.mdm.MdmTaxCodeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MdmTaxCodeServiceImpl implements MdmTaxCodeService {

    private static final String SNAPSHOT_FIELDS =
            "taxCode,taxRate,effectiveDate,expireDate,scope,policyNo,calcType,rateKind";

    private final MdmTaxCodeDao taxDao;
    private final MdmTaxCodeVersionDao versionDao;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;

    public MdmTaxCodeServiceImpl(MdmTaxCodeDao taxDao,
                                 MdmTaxCodeVersionDao versionDao,
                                 OutboxPublisher outbox,
                                 ObjectMapper objectMapper) {
        this.taxDao = taxDao;
        this.versionDao = versionDao;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    // ---------- 分页（计算态按日期推导，不落状态列） ----------

    @Override
    public Page<Map<String, Object>> page(long current, long size, String keyword, String scope,
                                          String calcType, String lifecycle) {
        // MP eq/like 的值参数即使 condition=false 也会被求值 → null-safe 归一化
        String kw = isNotBlank(keyword) ? keyword.trim().toUpperCase() : null;
        String kwRaw = isNotBlank(keyword) ? keyword.trim() : null;
        String sc = isNotBlank(scope) ? scope : null;
        String ct = isNotBlank(calcType) ? calcType : null;
        LambdaQueryWrapper<MdmTaxCode> qw = new LambdaQueryWrapper<MdmTaxCode>()
                // 关键字整体包在 .and 内（税码大写匹配 ∨ 政策文号原样匹配），不破坏后续 eq 绑定
                .and(kwRaw != null, w -> w
                        .like(MdmTaxCode::getTaxCode, kw)
                        .or().like(MdmTaxCode::getPolicyNo, kwRaw))
                .eq(sc != null, MdmTaxCode::getScope, sc)
                .eq(ct != null, MdmTaxCode::getCalcType, ct)
                .and(isNotBlank(lifecycle), w -> {
                    LocalDate today = LocalDate.now();
                    if ("NOT_EFFECTIVE".equals(lifecycle)) {
                        w.gt(MdmTaxCode::getEffectiveDate, today);
                    } else if ("EFFECTIVE".equals(lifecycle)) {
                        w.le(MdmTaxCode::getEffectiveDate, today)
                                .ge(MdmTaxCode::getExpireDate, today);
                    } else if ("EXPIRED".equals(lifecycle)) {
                        w.lt(MdmTaxCode::getExpireDate, today);
                    }
                })
                .orderByDesc(MdmTaxCode::getTaxCode)
                .orderByDesc(MdmTaxCode::getEffectiveDate);
        Page<MdmTaxCode> raw = taxDao.selectPage(new Page<>(current, size), qw);
        Page<Map<String, Object>> result = new Page<>(raw.getCurrent(), raw.getSize(), raw.getTotal());
        List<Map<String, Object>> records = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (MdmTaxCode t : raw.getRecords()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.getId());
            row.put("taxCode", t.getTaxCode());
            row.put("taxRate", t.getTaxRate());
            row.put("effectiveDate", t.getEffectiveDate());
            row.put("expireDate", t.getExpireDate());
            row.put("scope", t.getScope());
            row.put("policyNo", t.getPolicyNo());
            row.put("calcType", t.getCalcType());
            row.put("rateKind", t.getRateKind());
            row.put("verNo", t.getVerNo());
            row.put("createBy", t.getCreateBy());
            row.put("createDate", t.getCreateDate());
            row.put("lifecycle", lifecycleOf(t, today));
            records.add(row);
        }
        result.setRecords(records);
        return result;
    }

    private String lifecycleOf(MdmTaxCode t, LocalDate today) {
        if (t.getEffectiveDate().isAfter(today)) return "NOT_EFFECTIVE";
        if (t.getExpireDate().isBefore(today)) return "EXPIRED";
        return "EFFECTIVE";
    }

    @Override
    public MdmTaxCode getById(String id) {
        MdmTaxCode t = taxDao.selectById(id);
        if (t == null) {
            throw new ServiceException(404, "税码记录不存在");
        }
        return t;
    }

    // ---------- 创建 ----------

    @Override
    @Transactional
    public MdmTaxCode create(MdmTaxCode tax) {
        return createInternal(tax, true);
    }

    @Override
    @Transactional
    public MdmTaxCode createSegment(MdmTaxCode tax) {
        // 计划化创建：区间判定由工作台 checkAdjacent 预检负责（design D4）
        return createInternal(tax, false);
    }

    private MdmTaxCode createInternal(MdmTaxCode tax, boolean checkGap) {
        requireFields(tax);
        tax.setId(null);
        tax.setTaxCode(tax.getTaxCode().trim().toUpperCase());
        tax.setTaxRate(TaxCodeRules.normalizeRate(tax.getTaxRate()));
        tax.setVerNo(0);
        if (checkGap) {
            requireNoGapOrOverlap(tax.getTaxCode(), tax.getEffectiveDate(), tax.getExpireDate(), null);
        }
        taxDao.insert(tax);
        saveSnapshot(tax, "CREATE", "新建税码 " + tax.getTaxCode(), null);
        outbox.publish("MDM.TAXCODE.CREATED", bizKey(tax), tax.getVerNo() + 1, null,
                tax.getTaxCode() + " " + tax.getTaxRate() + "%" + " @" + tax.getEffectiveDate());
        return tax;
    }

    // ---------- 变更 ----------

    @Override
    @Transactional
    public MdmTaxCode update(MdmTaxCode tax) {
        MdmTaxCode stored = getById(tax.getId());
        // 税码编号锁定（C-4.1-01，序列键）
        if (isNotBlank(tax.getTaxCode()) && !tax.getTaxCode().equals(stored.getTaxCode())) {
            throw new ServiceException(422, "税码编号创建后不可修改（C-4.1-01），请新建记录");
        }
        // 历史 append-only（BR-4.1-18）
        if (stored.getExpireDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "已失效历史区间不可篡改（BR-4.1-18），仅可查看");
        }
        String reason = isNotBlank(tax.getChangeReason()) ? tax.getChangeReason().trim() : "";
        if (reason.length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 字）");
        }
        requireFields(tax);
        tax.setTaxRate(TaxCodeRules.normalizeRate(tax.getTaxRate()));
        requireNoGapOrOverlap(stored.getTaxCode(), tax.getEffectiveDate(), tax.getExpireDate(), stored.getId());
        String diff = buildDiff(stored, tax);
        if (diff.isEmpty()) {
            throw new ServiceException(422, "无变更内容");
        }
        LambdaUpdateWrapper<MdmTaxCode> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmTaxCode::getId, stored.getId())
                .eq(MdmTaxCode::getVerNo, stored.getVerNo())
                .set(MdmTaxCode::getEffectiveDate, tax.getEffectiveDate())
                .set(MdmTaxCode::getExpireDate, tax.getExpireDate())
                .set(MdmTaxCode::getTaxRate, tax.getTaxRate())
                .set(MdmTaxCode::getScope, tax.getScope())
                .set(MdmTaxCode::getPolicyNo, tax.getPolicyNo())
                .set(MdmTaxCode::getCalcType, tax.getCalcType())
                .set(MdmTaxCode::getRateKind, tax.getRateKind())
                // changeReason 为 @TableField(exist=false)，不可进 update wrapper（MP lambda 缓存异常）
                .set(MdmTaxCode::getVerNo, stored.getVerNo() + 1);
        if (taxDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmTaxCode after = getById(stored.getId());
        saveSnapshot(after, "UPDATE", diff + "；原因：" + reason, reason);
        outbox.publish("MDM.TAXCODE.UPDATED", bizKey(after), after.getVerNo() + 1, null, diff);
        return after;
    }

    @Override
    @Transactional
    public void delete(String id) {
        MdmTaxCode stored = getById(id);
        if (stored.getExpireDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "已失效历史区间不可删除（BR-4.1-18）");
        }
        taxDao.deleteById(id); // @TableLogic 软删
        saveSnapshot(stored, "UPDATE", "删除税码记录 " + stored.getTaxCode(), null);
    }

    /**
     * 缩短区间（工作台 D4）：整计划的有效性由工作台 IntervalRules 预检负责，
     * 此处只做 攻缩合法性（非历史、新失效日 ∈ [生效日, 原失效日)、原因）与乐观锁。
     * 不复用 update()——其区间复验面向「新建/整体迁移」语义，对收缩中的中段会误判左缺口。
     */
    @Override
    @Transactional
    public MdmTaxCode shortenSegment(String id, LocalDate newExpireDate, String reason) {
        MdmTaxCode stored = getById(id);
        if (stored.getExpireDate().isBefore(LocalDate.now())) {
            throw new ServiceException(422, "已失效历史区间不可篡改（BR-4.1-18），仅可查看");
        }
        if (newExpireDate == null || newExpireDate.isBefore(stored.getEffectiveDate())
                || !newExpireDate.isBefore(stored.getExpireDate())) {
            throw new ServiceException(422, "新失效日须在 [生效日, 原失效日) 内（仅允许收缩）");
        }
        if (!isNotBlank(reason) || reason.trim().length() < 2) {
            throw new ServiceException(422, "变更原因必填（至少 2 字）");
        }
        LambdaUpdateWrapper<MdmTaxCode> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmTaxCode::getId, stored.getId())
                .eq(MdmTaxCode::getVerNo, stored.getVerNo())
                .set(MdmTaxCode::getExpireDate, newExpireDate)
                .set(MdmTaxCode::getVerNo, stored.getVerNo() + 1);
        if (taxDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmTaxCode after = getById(stored.getId());
        String diff = "expireDate: " + stored.getExpireDate() + " → " + newExpireDate;
        saveSnapshot(after, "UPDATE", diff + "；原因：" + reason.trim(), reason.trim());
        outbox.publish("MDM.TAXCODE.UPDATED", bizKey(after), after.getVerNo() + 1, null, diff);
        return after;
    }

    // ---------- 区间冲突与断档（IntervalRules 共享核心） ----------

    private void requireNoGapOrOverlap(String taxCode, LocalDate from, LocalDate to, String excludeId) {
        List<MdmTaxCode> seq = taxDao.selectSequence(taxCode, excludeId);
        List<IntervalRules.IntervalSeg> segs = seq.stream()
                .map(t -> new IntervalRules.IntervalSeg(t.getEffectiveDate(), t.getExpireDate()))
                .toList();
        String reason = IntervalRules.check(segs, from, to);
        if (reason != null) {
            throw new ServiceException(422, reason);
        }
    }

    // ---------- 试算（4.14 预铺） ----------

    @Override
    public Map<String, Object> trial(String taxCode, LocalDate date) {
        if (!isNotBlank(taxCode)) {
            throw new ServiceException(422, "税码编号必填");
        }
        String code = taxCode.trim().toUpperCase();
        LocalDate d = date == null ? LocalDate.now() : date;
        MdmTaxCode hit = taxDao.selectHit(code, d);
        if (hit != null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("applicable", true);
            result.put("taxCode", hit.getTaxCode());
            result.put("taxRate", hit.getTaxRate());
            result.put("rateKind", hit.getRateKind());
            result.put("calcType", hit.getCalcType());
            result.put("scope", hit.getScope());
            result.put("policyNo", hit.getPolicyNo());
            result.put("effectiveDate", hit.getEffectiveDate());
            result.put("expireDate", hit.getExpireDate());
            return result;
        }
        List<String> reasons = new ArrayList<>();
        if (taxDao.countCode(code) == 0) {
            reasons.add("税码（" + code + "）无任何记录");
        } else {
            reasons.add("税码存在记录但日期 " + d + " 不在任何生效区间内");
        }
        Map<String, Object> miss = new LinkedHashMap<>();
        miss.put("applicable", false);
        miss.put("message", "该日期无有效税码");
        miss.put("reasons", reasons);
        miss.put("hint", "请补录覆盖该日期的税码记录（附政策文号）后重试");
        return miss;
    }

    // ---------- 版本 ----------

    @Override
    public List<MdmTaxCodeVersion> versions(String entityId) {
        return versionDao.selectList(new LambdaQueryWrapper<MdmTaxCodeVersion>()
                .eq(MdmTaxCodeVersion::getEntityId, entityId)
                .orderByAsc(MdmTaxCodeVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmTaxCodeVersion vFrom = findVersion(entityId, from);
        MdmTaxCodeVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", Map.of("versionNo", vFrom.getVersionNo(), "opType", vFrom.getOpType()));
        result.put("to", Map.of("versionNo", vTo.getVersionNo(), "opType", vTo.getOpType()));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    // ---------- 私有工具 ----------

    private void requireFields(MdmTaxCode t) {
        if (isNotBlank(t.getTaxCode())) {
            t.setTaxCode(t.getTaxCode().trim().toUpperCase());
        }
        // 枚举缺省回填（spec：计税方式/税率类型缺省 GENERAL/STANDARD），须在校验前
        if (!isNotBlank(t.getCalcType())) {
            t.setCalcType("GENERAL");
        }
        if (!isNotBlank(t.getRateKind())) {
            t.setRateKind("STANDARD");
        }
        String reason = TaxCodeRules.validateFields(t);
        if (reason != null) {
            throw new ServiceException(422, reason);
        }
    }

    /**
     * 幂等键含记录身份（design D3）：同记录同版本重放被拒，软删后同税码同日期重建不撞键。
     */
    private String bizKey(MdmTaxCode t) {
        return t.getTaxCode() + ":" + t.getEffectiveDate() + ":" + t.getId().substring(0, 8);
    }

    private void saveSnapshot(MdmTaxCode source, String opType, String diff, String reason) {
        int next = versionDao.selectCount(new LambdaQueryWrapper<MdmTaxCodeVersion>()
                .eq(MdmTaxCodeVersion::getEntityId, source.getId())).intValue() + 1;
        MdmTaxCodeVersion v = new MdmTaxCodeVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diff);
        v.setOpType(opType);
        v.setChangeReason(reason);
        versionDao.insert(v);
    }

    private MdmTaxCodeVersion findVersion(String entityId, int versionNo) {
        MdmTaxCodeVersion v = versionDao.selectOne(
                new LambdaQueryWrapper<MdmTaxCodeVersion>()
                        .eq(MdmTaxCodeVersion::getEntityId, entityId)
                        .eq(MdmTaxCodeVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    private String buildDiff(MdmTaxCode a, MdmTaxCode b) {
        StringBuilder sb = new StringBuilder();
        append(sb, "effectiveDate", a.getEffectiveDate(), b.getEffectiveDate());
        append(sb, "expireDate", a.getExpireDate(), b.getExpireDate());
        append(sb, "taxRate", a.getTaxRate(), b.getTaxRate());
        append(sb, "scope", a.getScope(), b.getScope());
        append(sb, "policyNo", a.getPolicyNo(), b.getPolicyNo());
        append(sb, "calcType", a.getCalcType(), b.getCalcType());
        append(sb, "rateKind", a.getRateKind(), b.getRateKind());
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

    private String toJson(MdmTaxCode entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String f : SNAPSHOT_FIELDS.split(",")) {
            try {
                var getter = entity.getClass().getMethod("get" + Character.toUpperCase(f.charAt(0)) + f.substring(1));
                Object val = getter.invoke(entity);
                map.put(f, val == null ? "" : String.valueOf(val));
            } catch (ReflectiveOperationException e) {
                throw new ServiceException(500, "快照字段缺失：" + f);
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
