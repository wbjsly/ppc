package com.erp.service.impl.mdm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.erp.common.ServiceException;
import com.erp.dao.mdm.MdmTaxCodeDao;
import com.erp.dao.mdm.MdmTaxCodeVersionDao;
import com.erp.dao.mdm.MdmTaxPolicyDao;
import com.erp.dao.mdm.MdmTaxPolicyVersionDao;
import com.erp.entity.mdm.MdmTaxCode;
import com.erp.entity.mdm.MdmTaxCodeVersion;
import com.erp.entity.mdm.MdmTaxPolicy;
import com.erp.entity.mdm.MdmTaxPolicyVersion;
import com.erp.ops.OutboxPublisher;
import com.erp.service.mdm.MdmTaxPolicyService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
public class MdmTaxPolicyServiceImpl implements MdmTaxPolicyService {

    private static final String SNAPSHOT_FIELDS =
            "policyNo,policyName,issuer,issueDate,effectiveDate,summary,remark";

    private final MdmTaxPolicyDao policyDao;
    private final MdmTaxCodeDao taxDao;
    private final MdmTaxCodeVersionDao versionDao;
    private final MdmTaxPolicyVersionDao policyVersionDao;
    private final OutboxPublisher outbox;
    private final ObjectMapper objectMapper;

    public MdmTaxPolicyServiceImpl(MdmTaxPolicyDao policyDao,
                                   MdmTaxCodeDao taxDao,
                                   MdmTaxCodeVersionDao versionDao,
                                   MdmTaxPolicyVersionDao policyVersionDao,
                                   OutboxPublisher outbox,
                                   ObjectMapper objectMapper) {
        this.policyDao = policyDao;
        this.taxDao = taxDao;
        this.versionDao = versionDao;
        this.policyVersionDao = policyVersionDao;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Override
    public Page<MdmTaxPolicy> page(long current, long size, String keyword) {
        String kw = isNotBlank(keyword) ? keyword.trim() : null;
        return policyDao.selectPage(new Page<>(current, size),
                new LambdaQueryWrapper<MdmTaxPolicy>()
                        .and(kw != null, w -> w
                                .like(MdmTaxPolicy::getPolicyNo, kw)
                                .or().like(MdmTaxPolicy::getPolicyName, kw)
                                .or().like(MdmTaxPolicy::getIssuer, kw))
                        .orderByDesc(MdmTaxPolicy::getIssueDate));
    }

    @Override
    public MdmTaxPolicy getById(String id) {
        MdmTaxPolicy p = policyDao.selectById(id);
        if (p == null) {
            throw new ServiceException(404, "政策记录不存在");
        }
        return p;
    }

    @Override
    @Transactional
    public MdmTaxPolicy create(MdmTaxPolicy policy) {
        requireFields(policy);
        policy.setId(null);
        policy.setPolicyNo(policy.getPolicyNo().trim());
        policy.setVerNo(0);
        try {
            policyDao.insert(policy);
        } catch (DuplicateKeyException ex) {
            throw new ServiceException(409, "政策文号已登记：" + policy.getPolicyNo());
        }
        int v1 = saveSnapshot(policy, "CREATE", "新建政策 " + policy.getPolicyNo());
        outbox.publish("MDM.POLICY.CREATED", bizKey(policy), v1, null,
                policy.getPolicyNo() + " " + policy.getPolicyName());
        log.info("TAX.POLICY.CREATED no={} name={}", policy.getPolicyNo(), policy.getPolicyName());
        return policy;
    }

    @Override
    @Transactional
    public MdmTaxPolicy update(MdmTaxPolicy policy) {
        MdmTaxPolicy stored = getById(policy.getId());
        if (isNotBlank(policy.getPolicyNo()) && !policy.getPolicyNo().equals(stored.getPolicyNo())) {
            throw new ServiceException(422, "政策文号登记后不可修改");
        }
        requireFields(policy);
        StringBuilder diff = new StringBuilder();
        append(diff, "policyName", stored.getPolicyName(), policy.getPolicyName());
        append(diff, "issuer", stored.getIssuer(), policy.getIssuer());
        append(diff, "issueDate", stored.getIssueDate(), policy.getIssueDate());
        append(diff, "effectiveDate", stored.getEffectiveDate(), policy.getEffectiveDate());
        append(diff, "summary", stored.getSummary(), policy.getSummary());
        append(diff, "remark", stored.getRemark(), policy.getRemark());
        if (diff.length() == 0) {
            throw new ServiceException(422, "无变更内容");
        }
        LambdaUpdateWrapper<MdmTaxPolicy> uw = new LambdaUpdateWrapper<>();
        uw.eq(MdmTaxPolicy::getId, stored.getId())
                .eq(MdmTaxPolicy::getVerNo, stored.getVerNo())
                .set(MdmTaxPolicy::getPolicyName, policy.getPolicyName())
                .set(MdmTaxPolicy::getIssuer, policy.getIssuer())
                .set(MdmTaxPolicy::getIssueDate, policy.getIssueDate())
                .set(MdmTaxPolicy::getEffectiveDate, policy.getEffectiveDate())
                .set(MdmTaxPolicy::getSummary, policy.getSummary())
                .set(MdmTaxPolicy::getRemark, policy.getRemark())
                .set(MdmTaxPolicy::getVerNo, stored.getVerNo() + 1);
        if (policyDao.update(null, uw) == 0) {
            throw new ServiceException(409, "数据已被他人修改，请刷新后重试");
        }
        MdmTaxPolicy after = getById(stored.getId());
        int vN = saveSnapshot(after, "UPDATE", diff.toString());
        outbox.publish("MDM.POLICY.UPDATED", bizKey(after), vN, null, diff.toString());
        // 关键字段变更记录（L5 审计留痕，操作日志与快照双写）
        log.info("TAX.POLICY.UPDATED no={} diff={}", stored.getPolicyNo(), diff);
        return after;
    }

    @Override
    @Transactional
    public void delete(String id) {
        MdmTaxPolicy stored = getById(id);
        int refs = taxDao.countByPolicy(stored.getPolicyNo());
        if (refs > 0) {
            throw new ServiceException(422, "该政策被 " + refs + " 条税码记录引用，不可删除");
        }
        policyDao.hardDelete(id); // 台账无历史语义 → 硬删
        log.info("TAX.POLICY.DELETED no={} (unreferenced)", stored.getPolicyNo());
    }

    @Override
    public Map<String, Object> taxCodesOf(String id) {
        MdmTaxPolicy policy = getById(id);
        List<MdmTaxCode> codes = taxDao.selectList(new LambdaQueryWrapper<MdmTaxCode>()
                .eq(MdmTaxCode::getPolicyNo, policy.getPolicyNo())
                .orderByDesc(MdmTaxCode::getTaxCode)
                .orderByDesc(MdmTaxCode::getEffectiveDate));

        List<Map<String, Object>> flow = new ArrayList<>();
        for (MdmTaxCode c : codes) {
            List<MdmTaxCodeVersion> vs = versionDao.selectList(
                    new LambdaQueryWrapper<MdmTaxCodeVersion>()
                            .eq(MdmTaxCodeVersion::getEntityId, c.getId()));
            for (MdmTaxCodeVersion v : vs) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("taxCode", c.getTaxCode());
                item.put("taxRate", c.getTaxRate());
                item.put("effectiveDate", c.getEffectiveDate());
                item.put("expireDate", c.getExpireDate());
                item.put("versionNo", v.getVersionNo());
                item.put("opType", v.getOpType());
                item.put("diffSummary", v.getDiffSummary());
                item.put("changeReason", v.getChangeReason());
                item.put("createBy", v.getCreateBy());
                item.put("createDate", v.getCreateDate());
                flow.add(item);
            }
        }
        flow.sort((a, b) -> {
            int c = ((java.time.LocalDateTime) b.get("createDate"))
                    .compareTo((java.time.LocalDateTime) a.get("createDate"));
            if (c != 0) {
                return c;
            }
            return ((Integer) b.get("versionNo")).compareTo((Integer) a.get("versionNo"));
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("policy", policy);
        result.put("taxCodes", codes);
        result.put("flow", flow);
        if (codes.isEmpty()) {
            result.put("hint", "该政策暂无关联税码变更");
        }
        return result;
    }

    // ---------- 版本（1.6.2 增强） ----------

    @Override
    public List<MdmTaxPolicyVersion> versions(String entityId) {
        return policyVersionDao.selectList(new LambdaQueryWrapper<MdmTaxPolicyVersion>()
                .eq(MdmTaxPolicyVersion::getEntityId, entityId)
                .orderByAsc(MdmTaxPolicyVersion::getVersionNo));
    }

    @Override
    public Map<String, Object> diff(String entityId, int from, int to) {
        MdmTaxPolicyVersion vFrom = findVersion(entityId, from);
        MdmTaxPolicyVersion vTo = findVersion(entityId, to);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("from", Map.of("versionNo", vFrom.getVersionNo(), "opType", vFrom.getOpType()));
        result.put("to", Map.of("versionNo", vTo.getVersionNo(), "opType", vTo.getOpType()));
        result.put("fields", compareSnapshots(vFrom.getSnapshotJson(), vTo.getSnapshotJson()));
        return result;
    }

    private MdmTaxPolicyVersion findVersion(String entityId, int versionNo) {
        MdmTaxPolicyVersion v = policyVersionDao.selectOne(
                new LambdaQueryWrapper<MdmTaxPolicyVersion>()
                        .eq(MdmTaxPolicyVersion::getEntityId, entityId)
                        .eq(MdmTaxPolicyVersion::getVersionNo, versionNo));
        if (v == null) {
            throw new ServiceException(404, "版本不存在：" + entityId + " V" + versionNo);
        }
        return v;
    }

    /** 快照（D1：幂等键 N = 快照 VERSION_NO；政策无必填原因 → reason 置空），返回版本号 */
    private int saveSnapshot(MdmTaxPolicy source, String opType, String diff) {
        int next = policyVersionDao.selectCount(new LambdaQueryWrapper<MdmTaxPolicyVersion>()
                .eq(MdmTaxPolicyVersion::getEntityId, source.getId())).intValue() + 1;
        MdmTaxPolicyVersion v = new MdmTaxPolicyVersion();
        v.setEntityId(source.getId());
        v.setVersionNo(next);
        v.setSnapshotJson(toJson(source));
        v.setDiffSummary(diff);
        v.setOpType(opType);
        v.setChangeReason(null);
        policyVersionDao.insert(v);
        return next;
    }

    /** 幂等键含政策文号与记录身份（沿 tax-code D3 口径，文号可读检索） */
    private String bizKey(MdmTaxPolicy p) {
        return p.getPolicyNo() + ":" + p.getId().substring(0, 8);
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

    private String toJson(MdmTaxPolicy entity) {
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

    // ---------- 私有 ----------

    private void requireFields(MdmTaxPolicy p) {
        if (!isNotBlank(p.getPolicyNo())) {
            throw new ServiceException(422, "政策文号必填");
        }
        if (!isNotBlank(p.getPolicyName())) {
            throw new ServiceException(422, "政策名称必填");
        }
        if (!isNotBlank(p.getIssuer())) {
            throw new ServiceException(422, "发文机关必填");
        }
        if (p.getIssueDate() == null) {
            throw new ServiceException(422, "发布日期必填");
        }
        if (p.getEffectiveDate() == null) {
            throw new ServiceException(422, "生效日期必填");
        }
        if (p.getEffectiveDate().isBefore(p.getIssueDate())) {
            throw new ServiceException(422, "生效日期不得早于发布日期");
        }
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

    private boolean isNotBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }
}
